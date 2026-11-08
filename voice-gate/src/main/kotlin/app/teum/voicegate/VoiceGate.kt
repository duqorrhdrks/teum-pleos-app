package app.teum.voicegate

import app.teum.core.dli.LoadState
import app.teum.core.dli.Stage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** 들려줄 발화 하나. */
data class Utterance(
    val text: String,
    /** 긴 음성: 다음 기동 15초 이내(사전 보류)에는 시작하지 않는다. */
    val long: Boolean = false,
    val priority: Priority = Priority.NORMAL,
    val tag: String = "conversation",
    val id: Long = nextId.incrementAndGet(),
) {
    enum class Priority { NORMAL, URGENT }

    val sentences: List<String> by lazy { splitSentences(text) }

    companion object {
        private val nextId = AtomicLong()
    }
}

/** 문장 단위로 자른다(마침표·물음표·느낌표·줄바꿈 뒤). TTS 큐의 단위다. */
fun splitSentences(text: String): List<String> =
    text.split(Regex("(?<=[.!?。？！])\\s+|\\n+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

sealed interface VoiceEvent {
    val utteranceId: Long
    data class Started(override val utteranceId: Long, val tag: String) : VoiceEvent
    data class Sentence(override val utteranceId: Long, val index: Int, val text: String) : VoiceEvent
    /** 시작을 보류함(사전 보류 또는 집중 단계). */
    data class Held(override val utteranceId: Long, val reason: String) : VoiceEvent
    /** 말하던 중 집중 단계 진입으로 끊음. */
    data class Interrupted(override val utteranceId: Long, val atSentence: Int) : VoiceEvent
    /** 다시 이어 말함. [summary]는 중단된 맥락 한 문장 요약(없으면 null). */
    data class Resumed(override val utteranceId: Long, val summary: String?, val fromSentence: Int) : VoiceEvent
    data class Finished(override val utteranceId: Long) : VoiceEvent
}

data class VoiceStatus(
    val speaking: Boolean = false,
    val current: String? = null,
    val pending: Int = 0,
    val heldReason: String? = null,
    /** 집중이 끝나 끊긴 이야기를 요약하는 중(요약을 말하기 전). */
    val preparingResume: Boolean = false,
)

/**
 * 운전 부하에 따라 음성 출력을 조율한다.
 *
 * - 출력은 문장 단위 큐로 관리한다.
 * - 집중 단계에 들어가면 말하던 문장을 즉시 멈춘다(엔진 stop).
 * - 집중을 벗어나면, 끊긴 맥락을 [ContextSummarizer]로 한 문장 요약해 먼저 말한 뒤 끊긴 문장부터 이어 말한다.
 * - 사전 보류(다음 기동 15초 이내)에는 긴 발화를 새로 시작하지 않는다. 이미 말하던 것은 계속한다.
 * - 긴급 발화는 지금 문장이 끝나면 끼어든다(집중 단계에서는 긴급도 기다린다).
 * - 틈 OFF(기준 비교 모드)면 모든 발화를 즉시 통과시킨다.
 */
class VoiceGate(
    scope: CoroutineScope,
    private val engine: SpeechEngine,
    private val summarizer: ContextSummarizer,
    loadStates: Flow<LoadState?>,
    teumEnabled: Flow<Boolean>,
    private val summaryTimeoutMs: Long = 3_000L,
    private val log: (String) -> Unit = {},
) {
    private data class Gate(val enabled: Boolean, val stage: Stage?, val preHold: Boolean) {
        /** 문장을 말해도 되는가 */
        val canSpeak get() = !enabled || stage != Stage.FOCUS
        /** 새 긴 발화를 시작해도 되는가 */
        fun canStart(u: Utterance) = !enabled || (canSpeak && !(u.long && preHold))
        fun holdReason(u: Utterance) = when {
            !canSpeak -> "집중 단계"
            u.long && preHold -> "다음 기동 지점 15초 이내(사전 보류)"
            else -> null
        }
    }

    /** 첫 부하 상태가 오기 전에는 null. 그동안은 아무것도 시작하지 않는다. */
    private val gateOrNull: StateFlow<Gate?> = combine(loadStates, teumEnabled) { st, en ->
        Gate(enabled = en, stage = st?.stage, preHold = st?.preHold == true)
    }.stateIn(scope, SharingStarted.Eagerly, null)
    private val gateValue: Gate get() = gateOrNull.value ?: Gate(enabled = true, stage = Stage.FOCUS, preHold = true)

    /** Utterance 또는 [ClearToken]. 정리 요청도 같은 큐로 보내 '그 이전 것만' 지운다. */
    private val inbox = Channel<Any>(Channel.UNLIMITED)
    private object ClearToken
    private val resume = ArrayDeque<Any>()
    private val normal = ArrayDeque<Utterance>()
    private val urgent = ArrayDeque<Utterance>()

    private val _events = MutableSharedFlow<VoiceEvent>(replay = 32, extraBufferCapacity = 64)
    val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()
    private val _status = MutableStateFlow(VoiceStatus())
    val status: StateFlow<VoiceStatus> = _status.asStateFlow()

    /** 진행 상태: 다음에 말할 문장 위치와 요약 필요 여부. */
    private class Progress(
        val u: Utterance,
        val gen: Int,
        var index: Int = 0,
        var started: Boolean = false,
        var interrupted: Boolean = false,
    )

    private val worker: Job = scope.launch { run() }

    fun say(u: Utterance) {
        inbox.trySend(u)
    }

    @Volatile private var generation = 0

    /** 대기 중인 발화를 모두 버리고 지금 말하는 것도 멈춘다. 이후에 들어온 발화는 그대로 처리한다. */
    fun clear() {
        generation++ // 진행 중인 발화(이전 세대)는 다음 문장 경계에서 그만둔다
        inbox.trySend(ClearToken)
        engine.stop()
    }

    private fun drainInbox() {
        while (true) {
            when (val x = inbox.tryReceive().getOrNull() ?: break) {
                ClearToken -> dropQueued()
                is Utterance -> enqueue(x)
            }
        }
        _status.value = _status.value.copy(pending = normal.size + urgent.size)
    }

    private fun enqueue(u: Utterance) {
        if (u.priority == Utterance.Priority.URGENT) urgent.addLast(u) else normal.addLast(u)
    }

    private fun dropQueued() {
        log("clear: dropping resume=${resume.size} normal=${normal.size} urgent=${urgent.size}")
        resume.clear(); normal.clear(); urgent.clear()
    }

    private suspend fun nextUtterance(): Progress {
        drainInbox()
        while (urgent.isEmpty() && normal.isEmpty()) {
            when (val x = inbox.receive()) {
                ClearToken -> dropQueued()
                is Utterance -> enqueue(x)
            }
            drainInbox()
        }
        // 사전 보류는 '긴 음성의 시작'만 막는다. 맨 앞의 긴 발화가 보류 중이면 지금 시작할 수 있는 짧은 발화를 먼저 꺼낸다.
        val g = gateOrNull.value
        val u = urgent.removeFirstOrNull()
            ?: normal.firstOrNull { g == null || g.canStart(it) }?.also { normal.remove(it) }
            ?: normal.removeFirst()
        _status.value = _status.value.copy(pending = normal.size + urgent.size)
        return Progress(u, gen = generation)
    }

    private suspend fun run() {
        while (true) {
            drainInbox()
            // 긴급 발화가 있으면 끼어든 자리(resume)보다 먼저 말한다
            @Suppress("UNCHECKED_CAST")
            val p = urgent.removeFirstOrNull()?.let { Progress(it, gen = generation) }
                ?: (resume.removeFirstOrNull() as Progress?)
                ?: nextUtterance()
            if (p.gen != generation) { log("skip stale utterance ${p.u.id}"); continue }
            log("pick ${p.u.id} idx=${p.index} urgent=${urgent.size} normal=${normal.size} resume=${resume.size}")
            try {
                speakUtterance(p)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                log("utterance ${p.u.id} failed: $t")
            }
        }
    }

    /** [p]를 끝까지 말한다. 긴급 발화가 끼어들면 [resume] 앞에 자기를 넣고 돌아간다. */
    private suspend fun speakUtterance(p: Progress) {
        val u = p.u
        if (!p.started) {
            if (!awaitStart(u)) {
                // 보류 중에 지금 시작할 수 있는 발화가 들어왔다: 이 발화는 줄 맨 앞으로 되돌리고 그것부터 말한다
                normal.addFirst(u)
                return
            }
            p.started = true
            _events.emit(VoiceEvent.Started(u.id, u.tag))
        }
        while (p.index < u.sentences.size) {
            if (p.gen != generation) return // clear()로 취소됨
            awaitGate(u) { it.canSpeak }

            if (p.interrupted && u.priority != Utterance.Priority.URGENT) {
                // 집중이 막 끝났다. 알림 큐는 같은 순간에 보류했던 긴급 알림을 풀어 주므로 잠깐 기다렸다가,
                // 긴급 알림이 있으면 대화 재개보다 먼저 말한다(README 단계표: '집중을 벗어나면 가장 먼저').
                delay(RESUME_SETTLE_MS)
                drainInbox()
                if (urgent.isNotEmpty()) {
                    log("resume ${u.id} after urgent=${urgent.size}")
                    resume.addFirst(p)
                    return
                }
                if (!gateValue.canSpeak) continue // 그새 다시 집중
            }

            if (p.interrupted) {
                p.interrupted = false
                val spoken = u.sentences.take(p.index)
                val summary = if (gateValue.enabled && spoken.isNotEmpty()) {
                    _status.value = _status.value.copy(preparingResume = true, heldReason = null)
                    try {
                        withTimeoutOrNull(summaryTimeoutMs) {
                            summarizer.summarize(spoken, u.sentences.drop(p.index))
                        } ?: HeuristicSummarizer.summarize(spoken)
                    } finally {
                        _status.value = _status.value.copy(preparingResume = false)
                    }
                } else null
                _events.emit(VoiceEvent.Resumed(u.id, summary, p.index))
                if (summary != null) {
                    // 요약 문장도 화면의 '말하는 중'에 보여 준다
                    _status.value = _status.value.copy(speaking = true, current = summary, heldReason = null)
                    val cut = speakGuarded(summary) == Outcome.CUT
                    _status.value = _status.value.copy(speaking = false, current = null)
                    if (cut) {
                        p.interrupted = true // 요약 중에 다시 집중: 다음에 또 요약부터
                        continue
                    }
                }
            }

            val sentence = u.sentences[p.index]
            _status.value = _status.value.copy(speaking = true, current = sentence, heldReason = null)
            when (speakGuarded(sentence)) {
                Outcome.DONE -> {
                    _events.emit(VoiceEvent.Sentence(u.id, p.index, sentence))
                    p.index++
                }
                Outcome.CUT -> {
                    p.interrupted = true
                    _events.emit(VoiceEvent.Interrupted(u.id, p.index))
                }
                Outcome.FAILED -> p.index++ // 엔진 오류 문장은 건너뛴다
            }
            _status.value = _status.value.copy(speaking = false, current = null)

            // 긴급 발화 끼어들기(문장 경계에서)
            drainInbox()
            if (u.priority != Utterance.Priority.URGENT && urgent.isNotEmpty() && p.index < u.sentences.size) {
                log("preempt ${u.id} at ${p.index} for urgent=${urgent.size}")
                resume.addFirst(p)
                return
            }
        }
        _events.emit(VoiceEvent.Finished(u.id))
    }

    private enum class Outcome { DONE, CUT, FAILED }

    /** 한 문장을 말한다. 말하는 도중 게이트가 닫히면 엔진을 멈추고 CUT을 돌려준다. */
    private suspend fun speakGuarded(sentence: String): Outcome = coroutineScope {
        val result = CompletableDeferred<SpeakResult>()
        val job = launch { result.complete(engine.speak(sentence)) }
        val closed = launch { gateOrNull.first { it == null || !it.canSpeak } }
        val outcome = select {
            result.onAwait { r ->
                when (r) {
                    SpeakResult.DONE -> Outcome.DONE
                    SpeakResult.STOPPED -> Outcome.CUT
                    SpeakResult.ERROR -> Outcome.FAILED
                }
            }
            closed.onJoin {
                engine.stop()
                job.join()
                Outcome.CUT
            }
        }
        closed.cancel()
        job.cancel()
        outcome
    }

    /**
     * 시작 허용을 기다린다. 기다리는 동안 지금 시작할 수 있는 다른 발화(짧은 알림, 긴급)가 들어오면 false.
     */
    private suspend fun awaitStart(u: Utterance): Boolean {
        val g0 = gateOrNull.value ?: gateOrNull.first { it != null }!!
        if (g0.canStart(u)) return true
        val reason = g0.holdReason(u) ?: "보류"
        _status.value = _status.value.copy(heldReason = reason)
        _events.emit(VoiceEvent.Held(u.id, reason))
        while (true) {
            val ok = withTimeoutOrNull(HOLD_POLL_MS) { gateOrNull.first { it != null && it.canStart(u) } }
            if (ok != null) break
            drainInbox()
            val g = gateOrNull.value
            if (urgent.isNotEmpty() || normal.any { g != null && g.canStart(it) }) {
                _status.value = _status.value.copy(heldReason = null)
                return false
            }
        }
        _status.value = _status.value.copy(heldReason = null)
        return true
    }

    private suspend fun awaitGate(u: Utterance, ok: (Gate) -> Boolean) {
        val g = gateOrNull.value ?: gateOrNull.first { it != null }!!
        if (ok(g)) return
        val reason = g.holdReason(u) ?: "보류"
        _status.value = _status.value.copy(heldReason = reason)
        _events.emit(VoiceEvent.Held(u.id, reason))
        gateOrNull.first { it != null && ok(it) }
        _status.value = _status.value.copy(heldReason = null)
    }

    private companion object {
        const val HOLD_POLL_MS = 200L
        /** 집중 이탈 직후 재개 전에 쉬는 시간. 같은 순간 풀린 긴급 알림이 도착할 여유. */
        const val RESUME_SETTLE_MS = 300L
    }

    fun close() {
        worker.cancel()
        engine.stop()
    }
}
