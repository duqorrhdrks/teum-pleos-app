package app.teum.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.teum.core.dli.DliEngine
import app.teum.core.dli.LoadState
import app.teum.core.dli.VehicleSnapshot
import app.teum.core.dli.toLoadStates
import app.teum.data.Briefing
import app.teum.data.BriefingStore
import app.teum.data.SettingsRepository
import app.teum.data.SignalMode
import app.teum.data.TeumSettings
import app.teum.data.TripRecorder
import app.teum.loadaware.LoadStageBroadcaster
import app.teum.loadaware.PublicLoadState
import app.teum.navisignal.NaviSignal
import app.teum.navisignal.NoNaviSource
import app.teum.navisignal.PleosNaviHelperSource
import app.teum.navisignal.StaleManeuverFilter
import app.teum.navisignal.withNavi
import app.teum.notificationqueue.DemoMessageSource
import app.teum.notificationqueue.MessageBus
import app.teum.notificationqueue.NotificationQueue
import app.teum.notificationqueue.TeumMessage
import app.teum.vehiclesignal.AaosCarPropertySource
import app.teum.vehiclesignal.CompositeVehicleSource
import app.teum.vehiclesignal.PleosVehicleSource
import app.teum.vehiclesignal.Scenario
import app.teum.vehiclesignal.ScenarioEvent
import app.teum.vehiclesignal.ScenarioParser
import app.teum.vehiclesignal.ScenarioReplaySource
import app.teum.vehiclesignal.SourceStatus
import app.teum.voicegate.ContextSummarizer
import app.teum.voicegate.FallbackSpeechEngine
import app.teum.voicegate.GleoChat
import app.teum.voicegate.GleoLlmSummarizer
import app.teum.voicegate.GleoSpeechInput
import app.teum.voicegate.HeuristicSummarizer
import app.teum.voicegate.Utterance
import app.teum.voicegate.VoiceEvent
import app.teum.voicegate.VoiceGate
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** 디버그 화면용 신호원 상태. */
data class SourceInfo(val id: String, val status: SourceStatus)

/**
 * 틈의 중심: 신호원 → DLI(5Hz) → 음성 게이트·알림 큐·동승자 신호·외부 API를 잇는다.
 * [LoadEngineService]가 시작·정지를 맡고, 화면은 상태 흐름만 구독한다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LoadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val briefingStore: BriefingStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings: StateFlow<TeumSettings> = settingsRepo.settings(scope)
    private val enabled: StateFlow<Boolean> =
        settings.map { it.teumEnabled }.stateIn(scope, SharingStarted.Eagerly, true)

    private val now: () -> Long = SystemClock::elapsedRealtime

    // ── 신호원 ─────────────────────────────────────────────
    private val aaos = AaosCarPropertySource(context)
    private val pleos = PleosVehicleSource(context)
    private val vehicle = CompositeVehicleSource(aaos, pleos)
    private val navi = if (AaosCarPropertySource.isAutomotive(context)) PleosNaviHelperSource(context) else NoNaviSource()

    val scenario: Scenario by lazy {
        ScenarioParser.parse(context.assets.open("highway_demo.json").bufferedReader().readText())
    }

    /** 시나리오 재생 요청 번호. 바뀔 때마다 처음부터 다시 재생한다. */
    private val scenarioRun = MutableStateFlow(0)
    private val _replay = MutableStateFlow<ScenarioReplaySource?>(null)
    val replay: StateFlow<ScenarioReplaySource?> = _replay.asStateFlow()

    private fun snapshotsFor(mode: SignalMode, run: Int): Flow<VehicleSnapshot> = when (mode) {
        SignalMode.VEHICLE -> {
            _replay.value = null
            combine(
                vehicle.snapshots(),
                navi.signals().onStart { emit(NaviSignal(available = false, detail = "연결 중")) },
            ) { v, n -> v.withNavi(n) }.let { flow ->
                val stale = StaleManeuverFilter()
                flow.map { stale.apply(it) }
            }
        }
        SignalMode.SCENARIO -> if (run == 0) emptyFlow() else channelFlow {
            val src = ScenarioReplaySource(scenario, nowMs = now)
            _replay.value = src
            // 이벤트 수집은 이 재생과 함께 끝난다(flatMapLatest가 취소)
            launch { src.events.collect(::onScenarioEvent) }
            src.snapshots().collect { send(it) }
        }
    }

    /** 5Hz 부하 상태. 신호원이 바뀌면 DLI 엔진을 새로 만든다(스무딩·히스테리시스 초기화). */
    val loadState: StateFlow<LoadState?> =
        combine(settings.map { it.signalMode }.distinctUntilChanged(), scenarioRun) { m, r -> m to r }
            .flatMapLatest { (m, r) ->
                val src = snapshotsFor(m, r)
                if (m == SignalMode.SCENARIO && r == 0) flowOf(null) else src.toLoadStates(DliEngine(), nowMs = now)
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val sources: StateFlow<List<SourceInfo>> = combine(aaos.status, pleos.status, navi.state) { a, p, n ->
        listOf(
            SourceInfo("AAOS 표준(CarPropertyManager)", a),
            SourceInfo("Pleos Vehicle SDK", p),
            SourceInfo("Pleos NaviHelper", if (n.available) SourceStatus.Connected(setOf(n.detail)) else SourceStatus.Unavailable(n.detail)),
        )
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // ── 음성 ───────────────────────────────────────────────
    private val speech = FallbackSpeechEngine.create(context)
    val activeTts: StateFlow<String> = speech.active
    // Pleos SDK 객체는 메인 스레드(Looper)에서 만든다
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun <T> onMain(block: () -> T): T = if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block() else {
        val latch = java.util.concurrent.CountDownLatch(1)
        var r: Result<T>? = null
        mainHandler.post { r = runCatching(block); latch.countDown() }
        latch.await()
        r!!.getOrThrow()
    }
    private val llmSummarizer by lazy { onMain { GleoLlmSummarizer(context) } }
    private val summarizer = ContextSummarizer { spoken, remaining ->
        llmSummarizer.summarize(spoken, remaining) ?: HeuristicSummarizer.summarize(spoken)
    }
    val voice = VoiceGate(scope, speech, summarizer, loadState, enabled, log = { Log.d("VoiceGate", it) })
    private val speechInput by lazy { onMain { GleoSpeechInput(context) } }
    private val chat by lazy { onMain { GleoChat(context) } }
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    // ── 알림 ───────────────────────────────────────────────
    private val rules = settings.map { it.urgencyRules }.stateIn(scope, SharingStarted.Eagerly, settings.value.urgencyRules)
    val queue = NotificationQueue(scope, loadState, enabled, rules, now)
    private val demo = DemoMessageSource(now)

    // ── 브리핑 ─────────────────────────────────────────────
    private val recorder = TripRecorder()
    private val _lastBriefing = MutableStateFlow(briefingStore.load())
    val lastBriefing: StateFlow<Briefing?> = _lastBriefing.asStateFlow()

    // ── 외부 공개 상태(부하 인지 API) ──────────────────────
    val publicState: StateFlow<PublicLoadState?> = combine(loadState, enabled) { st, en ->
        st?.let { PublicLoadState(it.stage.name, it.dli.roundToInt(), it.stageSinceMs, it.preHold, en) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private var started: Job? = null

    /** 서비스가 부른다. 여러 번 불러도 한 번만 시작한다. */
    @Synchronized
    fun start() {
        if (started?.isActive == true) return
        started = scope.launch {
            // 시나리오는 일회성 시연이다. 앱이 새로 시작됐는데 재생 요청이 없으면 차량 신호로 돌아간다
            // (그대로 두면 부하 상태가 비어 알림이 계속 보류된다).
            if (settingsRepo.current().signalMode == SignalMode.SCENARIO && !scenarioRequested) {
                settingsRepo.setMode(SignalMode.VEHICLE)
            }
            launch { MessageBus.incoming.collect { queue.submit(it) } }
            launch {
                queue.deliveries.collect { d ->
                    Log.i(TAG, "deliver urgent=${d.urgent} batch=${d.batch} ${d.display}")
                    recorder.onDelivery(d)
                    voice.say(
                        Utterance(
                            d.spoken, long = false, tag = "notification",
                            priority = if (d.urgent) Utterance.Priority.URGENT else Utterance.Priority.NORMAL,
                        ),
                    )
                }
            }
            launch { activeTts.collect { Log.i(TAG, "speech engine: $it") } }
            // 디버그 빌드: 1초마다 DLI와 항목별 기여도를 남긴다(시연·문제 확인용. adb logcat -s TeumDli)
            if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) launch {
                var lastMs = 0L
                loadState.filterNotNull().collect { st ->
                    if (st.timestampMs - lastMs < 1_000) return@collect
                    lastMs = st.timestampMs
                    val v = st.snapshot
                    Log.d(
                        "TeumDli",
                        "dli=%.1f raw=%.1f %s | v=%s brake=%s turn=%s gear=%s wiper=%s | %s".format(
                            st.dli, st.dliRaw, st.stage, v.speedKmh, v.brakePressed, v.turnSignalOn, v.gear, v.wiperLevel,
                            st.contributions.entries.filter { it.value > 0.05f }.joinToString(" ") { "${it.key.name}=%.1f".format(it.value) },
                        ),
                    )
                }
            }
            launch { loadState.filterNotNull().distinctUntilChangedBy { it.stage }.collect { Log.i(TAG, "stage ${it.stage} dli=${"%.1f".format(it.dli)}") } }
            launch {
                voice.events.collect { e ->
                    Log.i(TAG, "voice $e")
                    when (e) {
                        is VoiceEvent.Interrupted -> recorder.interruptions++
                        is VoiceEvent.Resumed -> if (e.summary != null) recorder.resumedWithSummary++
                        else -> Unit
                    }
                }
            }
            launch {
                loadState.filterNotNull().collect { st ->
                    if (recorder.onLoadState(st)) finishTrip()
                }
            }
            launch {
                publicState.filterNotNull()
                    .distinctUntilChangedBy { Triple(it.stage, it.preHold, it.teumEnabled) }
                    .collect { LoadStageBroadcaster.send(context, it) }
            }
        }
    }

    fun stop() {
        started?.cancel()
        started = null
        voice.clear()
    }

    private fun finishTrip() {
        val b = recorder.finish(queue.stats.value, enabled.value)
        queue.resetStats()
        briefingStore.save(b)
        _lastBriefing.value = b
        Log.i(TAG, "trip finished: ${b.headline}")
    }

    private fun onScenarioEvent(e: ScenarioEvent) {
        when (e.kind) {
            ScenarioEvent.Kind.SPEECH -> voice.say(Utterance(e.text, long = e.long))
            ScenarioEvent.Kind.MESSAGE, ScenarioEvent.Kind.CALL -> MessageBus.post(
                demo.message(
                    e.sender ?: "알 수 없음", e.text, call = e.kind == ScenarioEvent.Kind.CALL,
                    source = TeumMessage.Source.SCENARIO,
                ),
            )
        }
    }

    // ── 화면 동작 ─────────────────────────────────────────
    @Volatile private var scenarioRequested = false

    fun playScenario() {
        scenarioRequested = true
        scope.launch {
            voice.clear()
            recorder.reset()
            queue.resetStats()
            settingsRepo.setMode(SignalMode.SCENARIO)
            scenarioRun.value = scenarioRun.value + 1
        }
    }

    fun useVehicleSignals() {
        scope.launch { settingsRepo.setMode(SignalMode.VEHICLE) }
    }

    fun setTeumEnabled(v: Boolean) {
        scope.launch { settingsRepo.setEnabled(v) }
    }

    /** 처음 실행 안내 말풍선: true = 다 봄(다시 안 띄움), false = 다음에 주행 화면을 열면 다시 띄움 */
    fun setGuideSeen(v: Boolean) {
        scope.launch { settingsRepo.setGuideSeen(v) }
    }

    fun sendDemoMessage() {
        MessageBus.post(demo.next())
    }

    /** 위임 API로 들어온 메시지 */
    fun submitDelegated(app: String, sender: String, text: String, isCall: Boolean) {
        MessageBus.post(
            TeumMessage(
                sender = sender, text = text, receivedAtMs = now(), source = TeumMessage.Source.LOAD_AWARE_API,
                kind = if (isCall) TeumMessage.Kind.CALL else TeumMessage.Kind.MESSAGE, app = app,
            ),
        )
    }

    /** '말하기': Gleo STT로 한 번 듣고 Gleo LLM 답을 음성 게이트로 보낸다. */
    fun talk() {
        if (_listening.value) return
        scope.launch {
            _listening.value = true
            try {
                val q = speechInput.listenOnce()
                if (q.isNullOrBlank()) {
                    voice.say(Utterance("잘 듣지 못했어요.", tag = "assistant"))
                    return@launch
                }
                val answer = chat.answer(q) ?: "지금은 답을 만들 수 없어요."
                voice.say(Utterance(answer, long = app.teum.voicegate.splitSentences(answer).size > 2, tag = "assistant"))
            } finally {
                _listening.value = false
            }
        }
    }

    fun saveSettings(contacts: List<String>, keywords: List<String>) {
        scope.launch {
            settingsRepo.setContacts(contacts)
            settingsRepo.setKeywords(keywords)
        }
    }

    fun shutdown() {
        stop()
        scope.coroutineContext.cancelChildren()
        speech.release()
    }

    private companion object { const val TAG = "LoadEngine" }
}
