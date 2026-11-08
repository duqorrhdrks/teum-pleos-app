package app.teum.voicegate

import app.teum.core.dli.LoadState
import app.teum.core.dli.Stage
import app.teum.core.dli.VehicleSnapshot
import app.teum.core.dli.toLoadStates
import app.teum.vehiclesignal.ScenarioEvent
import app.teum.vehiclesignal.ScenarioParser
import app.teum.vehiclesignal.ScenarioReplaySource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 가상 시간으로 말하는 가짜 TTS. 한 글자에 [msPerChar]ms. */
class FakeEngine(private val scope: TestScope, private val msPerChar: Long = 160) : SpeechEngine {
    override val id = "fake"
    val log = mutableListOf<Pair<Long, String>>()
    private var current: CompletableDeferred<SpeakResult>? = null

    override suspend fun speak(sentence: String): SpeakResult {
        log += scope.testScheduler.currentTime to sentence
        val done = CompletableDeferred<SpeakResult>()
        current = done
        val timer = scope.launch { delay(sentence.length * msPerChar); done.complete(SpeakResult.DONE) }
        val r = done.await()
        timer.cancel()
        return r
    }

    override fun stop() {
        current?.complete(SpeakResult.STOPPED)
    }
}

private fun state(stage: Stage, preHold: Boolean = false) = LoadState(
    timestampMs = 0, dliRaw = 0f, dli = 0f, stage = stage, stageSinceMs = 0,
    contributions = emptyMap(), missing = emptySet(), maneuverEtaSec = null, preHold = preHold,
    snapshot = VehicleSnapshot(0),
)

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceGateTest {

    @Test
    fun splitsSentences() {
        assertEquals(
            listOf("오늘 일정 알려 드릴게요.", "열 시에 리뷰가 있어요!", "괜찮으세요?", "끝"),
            splitSentences("오늘 일정 알려 드릴게요. 열 시에 리뷰가 있어요! 괜찮으세요?\n끝"),
        )
    }

    @Test
    @DisplayName("엔진 사슬: 오류가 2번 연속이면 같은 문장을 다음 엔진(자막)으로 말한다")
    fun chainFallsBack() = runTest {
        var gleoCalls = 0
        val broken = object : SpeechEngine {
            override val id = "broken"
            override suspend fun speak(sentence: String): SpeakResult { gleoCalls++; return SpeakResult.ERROR }
            override fun stop() {}
        }
        val switched = mutableListOf<String>()
        val chain = ChainedSpeechEngine(listOf({ broken }, { null }, { CaptionOnlyEngine() }), onSwitch = { switched += it })
        assertEquals(SpeakResult.DONE, chain.speak("안녕하세요."))
        assertEquals(2, gleoCalls)
        assertEquals(listOf("broken", "caption-only"), switched)
        assertEquals("caption-only", chain.id)
    }

    @Test
    fun heuristicSummary() {
        assertEquals("아까 오늘 일정 이야기를 하던 중이었어요.", HeuristicSummarizer.summarize(listOf("오늘 일정 알려 드릴게요.")))
        assertEquals("아까 오늘의 주요 뉴스 이야기를 하던 중이었어요.", HeuristicSummarizer.summarize(listOf("오늘의 주요 뉴스를 요약해 드릴게요.")))
    }

    @Test
    @DisplayName("집중 진입 → 말하던 문장 즉시 중단, 이탈 → 요약 후 끊긴 문장부터 재개")
    fun interruptAndResume() = runTest {
        val engine = FakeEngine(this, msPerChar = 100)
        val load = MutableStateFlow<LoadState?>(state(Stage.GAP))
        val gate = VoiceGate(backgroundScope, engine, { _, _ -> "아까 일정 이야기 중이었어요." }, load, MutableStateFlow(true))
        gate.say(Utterance("첫째 문장입니다. 둘째 문장은 조금 깁니다. 셋째 문장입니다.", long = true))
        advanceTimeBy(1_200) // 첫 문장(9자=0.9s) 끝, 둘째 문장 중
        load.value = state(Stage.FOCUS)
        runCurrent()
        advanceTimeBy(5_000)
        assertEquals(2, engine.log.size, "집중 중에는 새 문장을 말하지 않는다")
        load.value = state(Stage.GAP)
        advanceTimeBy(10_000)

        val texts = engine.log.map { it.second }
        assertEquals(
            listOf("첫째 문장입니다.", "둘째 문장은 조금 깁니다.", "아까 일정 이야기 중이었어요.", "둘째 문장은 조금 깁니다.", "셋째 문장입니다."),
            texts,
        )
        val events = gate.events.replayCache
        assertTrue(events.any { it is VoiceEvent.Interrupted && it.atSentence == 1 })
        assertTrue(events.any { it is VoiceEvent.Resumed && it.fromSentence == 1 && it.summary != null })
        assertTrue(events.last() is VoiceEvent.Finished)
    }

    @Test
    @DisplayName("사전 보류 중에는 긴 발화를 시작하지 않고, 짧은 발화는 말한다")
    fun preHoldBlocksLongOnly() = runTest {
        val engine = FakeEngine(this)
        val load = MutableStateFlow<LoadState?>(state(Stage.CAUTION, preHold = true))
        val gate = VoiceGate(backgroundScope, engine, HeuristicSummarizer, load, MutableStateFlow(true))
        gate.say(Utterance("뉴스를 요약해 드릴게요. 첫 소식입니다.", long = true))
        gate.say(Utterance("엄마에게서 메시지가 왔어요.", long = false))
        advanceTimeBy(8_000)
        // 긴 발화는 보류, 짧은 알림은 먼저 지나간다
        assertEquals(listOf("엄마에게서 메시지가 왔어요."), engine.log.map { it.second })
        assertTrue(gate.events.replayCache.any { it is VoiceEvent.Held && it.reason.contains("사전 보류") })
        load.value = state(Stage.CAUTION, preHold = false)
        advanceTimeBy(20_000)
        assertEquals(listOf("엄마에게서 메시지가 왔어요.", "뉴스를 요약해 드릴게요.", "첫 소식입니다."), engine.log.map { it.second })
    }

    @Test
    @DisplayName("긴급 발화는 문장 경계에서 끼어들고, 원래 발화는 이어서 말한다")
    fun urgentPreempts() = runTest {
        val engine = FakeEngine(this, msPerChar = 100)
        val gate = VoiceGate(backgroundScope, engine, HeuristicSummarizer, MutableStateFlow(state(Stage.GAP)), MutableStateFlow(true))
        gate.say(Utterance("하나 둘 셋. 넷 다섯 여섯. 일곱 여덟."))
        advanceTimeBy(300)
        gate.say(Utterance("긴급: 엄마 전화", priority = Utterance.Priority.URGENT))
        advanceTimeBy(10_000)
        assertEquals(listOf("하나 둘 셋.", "긴급: 엄마 전화", "넷 다섯 여섯.", "일곱 여덟."), engine.log.map { it.second })
    }

    @Test
    @DisplayName("집중 중 쌓인 긴급·일반 알림: 이탈하면 긴급 먼저, 그다음 요약·대화 재개, 그다음 일반")
    fun focusBacklogOrder() = runTest {
        val engine = FakeEngine(this, msPerChar = 100)
        val load = MutableStateFlow<LoadState?>(state(Stage.GAP))
        val gate = VoiceGate(backgroundScope, engine, { _, _ -> "요약." }, load, MutableStateFlow(true))
        gate.say(Utterance("버려질 발화."))
        gate.clear() // 시나리오 시작 때처럼 먼저 정리(회귀: 이후 발화까지 버리면 안 됨)
        runCurrent()
        gate.say(Utterance("하나 문장. 둘 문장. 셋 문장.", long = true))
        advanceTimeBy(700) // '하나 문장.'(6자=0.6s) 끝, 둘 문장 중
        load.value = state(Stage.FOCUS)
        runCurrent()
        gate.say(Utterance("긴급 알림.", priority = Utterance.Priority.URGENT, tag = "notification"))
        gate.say(Utterance("긴급 전화.", priority = Utterance.Priority.URGENT, tag = "notification"))
        gate.say(Utterance("일반 알림.", tag = "notification"))
        gate.say(Utterance("뉴스 시작.", long = true))
        advanceTimeBy(3_000)
        load.value = state(Stage.GAP)
        advanceTimeBy(20_000)
        assertEquals(
            listOf("하나 문장.", "둘 문장.", "긴급 알림.", "긴급 전화.", "요약.", "둘 문장.", "셋 문장.", "일반 알림.", "뉴스 시작."),
            engine.log.map { it.second },
        )
    }

    /** 에뮬레이터에서 찾은 회귀: 알림 큐가 집중 이탈 30ms 뒤에 긴급 알림을 풀면, 대화가 먼저 재개돼 긴급이 15초 늦었다. */
    @Test
    @DisplayName("집중 이탈 직후(30ms 뒤) 풀린 긴급 알림도 대화 재개보다 먼저 말한다")
    fun urgentReleasedJustAfterFocusGoesFirst() = runTest {
        val engine = FakeEngine(this, msPerChar = 100)
        val load = MutableStateFlow<LoadState?>(state(Stage.GAP))
        val gate = VoiceGate(backgroundScope, engine, { _, _ -> "요약." }, load, MutableStateFlow(true))
        gate.say(Utterance("하나 문장. 둘 문장은 아주 길게 이어지는 문장입니다. 셋 문장.", long = true))
        advanceTimeBy(700)
        load.value = state(Stage.FOCUS)
        advanceTimeBy(3_000)
        load.value = state(Stage.GAP)
        advanceTimeBy(30)
        gate.say(Utterance("긴급 알림.", priority = Utterance.Priority.URGENT, tag = "notification"))
        advanceTimeBy(20_000)
        assertEquals(
            listOf("하나 문장.", "둘 문장은 아주 길게 이어지는 문장입니다.", "긴급 알림.", "요약.", "둘 문장은 아주 길게 이어지는 문장입니다.", "셋 문장."),
            engine.log.map { it.second },
        )
    }

    @Test
    @DisplayName("틈 OFF(기준 비교): 집중 단계에서도 그대로 말한다")
    fun teumOffPassesThrough() = runTest {
        val engine = FakeEngine(this)
        val gate = VoiceGate(backgroundScope, engine, HeuristicSummarizer, MutableStateFlow(state(Stage.FOCUS, preHold = true)), MutableStateFlow(false))
        gate.say(Utterance("하나. 둘.", long = true))
        advanceTimeBy(5_000)
        assertEquals(listOf("하나.", "둘."), engine.log.map { it.second })
    }

    /**
     * 완료 기준 4: 시나리오의 합류 구간에서 음성이 멈췄다가 정속 구간에서 요약 문장과 함께 재개된다.
     */
    @Test
    @DisplayName("고속도로 시나리오: 합류 구간 중단 → 정속 구간 요약과 함께 재개, 사전 보류 뉴스는 합류 후 시작")
    fun highwayScenarioVoice() = runTest {
        val scenario = ScenarioParser.parse(
            requireNotNull(javaClass.getResource("/highway_demo.json")).readText(),
        )
        val src = ScenarioReplaySource(scenario, nowMs = { testScheduler.currentTime })
        val states = src.snapshots()
            .toLoadStates(nowMs = { testScheduler.currentTime })
            .stateIn(backgroundScope, SharingStarted.Eagerly, null)
        val engine = FakeEngine(this)
        val gate = VoiceGate(backgroundScope, engine, HeuristicSummarizer, states, MutableStateFlow(true))
        backgroundScope.launch {
            src.events.filter { it.kind == ScenarioEvent.Kind.SPEECH }
                .collect { gate.say(Utterance(it.text, long = it.long)) }
        }

        val stageLog = mutableListOf<Pair<Long, Stage>>()
        backgroundScope.launch {
            states.collect { s -> s?.let { if (stageLog.lastOrNull()?.second != it.stage) stageLog += it.timestampMs to it.stage } }
        }

        advanceTimeBy(100_000)

        val events = gate.events.replayCache
        val interrupted = events.filterIsInstance<VoiceEvent.Interrupted>()
        val resumed = events.filterIsInstance<VoiceEvent.Resumed>()
        val focusStart = stageLog.first { it.second == Stage.FOCUS }.first
        val focusEnd = stageLog.first { it.first > focusStart && it.second != Stage.FOCUS }.first

        assertEquals(1, interrupted.size, "중단 1회: $events")
        assertEquals(1, resumed.size)
        assertNotNull(resumed.single().summary)
        // 중단은 집중 진입 시각, 재개는 집중 이탈 이후
        val cutAt = engine.log.last { it.first <= focusStart }.first
        assertTrue(cutAt < focusStart)
        val summaryAt = engine.log.first { it.second == resumed.single().summary }.first
        assertTrue(summaryAt >= focusEnd, "요약은 집중 이탈($focusEnd) 뒤에: $summaryAt")
        assertEquals(Stage.GAP, stageLog.last { it.first <= summaryAt }.second, "재개는 정속(틈) 구간에서")
        // 끊긴 문장을 요약 바로 뒤에 다시 말한다
        val idx = engine.log.indexOfFirst { it.second == resumed.single().summary }
        assertEquals(engine.log[idx - 1].second, engine.log[idx + 1].second)

        // 사전 보류: 29초에 들어온 긴 뉴스는 합류 기동이 끝난(41.2초) 뒤에 시작
        val newsAt = engine.log.first { it.second.startsWith("오늘의 주요 뉴스") }.first
        assertTrue(newsAt > 41_200, "뉴스 시작 $newsAt")
        assertTrue(events.any { it is VoiceEvent.Held })
    }
}
