package app.teum.voicegate

/** 문장 하나 발화 결과. */
enum class SpeakResult { DONE, STOPPED, ERROR }

/** TTS 엔진 어댑터. [speak]는 문장이 끝나거나 멈출 때까지 일시 중단한다. */
interface SpeechEngine {
    val id: String
    suspend fun speak(sentence: String): SpeakResult
    /** 지금 말하는 문장을 멈춘다. 진행 중인 [speak]는 STOPPED를 돌려준다. */
    fun stop()
    fun release() {}
}

/** 중단된 맥락을 한 문장으로 요약한다. 실패하면 null. */
fun interface ContextSummarizer {
    suspend fun summarize(spoken: List<String>, remaining: List<String>): String?
}

/**
 * LLM을 쓸 수 없을 때의 요약. 첫 문장의 화제를 짚어 준다.
 * 예: "오늘 일정 알려 드릴게요." → "아까 오늘 일정 이야기를 하던 중이었어요."
 */
object HeuristicSummarizer : ContextSummarizer {
    private val tails = listOf(
        "알려 드릴게요", "알려드릴게요", "요약해 드릴게요", "말씀드릴게요", "말씀 드릴게요", "안내해 드릴게요",
        "을 알려 드릴게요", "를 알려 드릴게요",
    )

    fun summarize(spoken: List<String>): String? {
        val first = spoken.firstOrNull()?.trimEnd('.', '!', '?', ' ') ?: return null
        var topic = first
        tails.sortedByDescending { it.length }.firstOrNull { first.endsWith(it) }?.let {
            topic = first.removeSuffix(it).trimEnd().removeSuffix("을").removeSuffix("를").trimEnd()
        }
        if (topic.length > 24) topic = topic.take(24) + "…"
        return "아까 $topic 이야기를 하던 중이었어요."
    }

    override suspend fun summarize(spoken: List<String>, remaining: List<String>): String? = summarize(spoken)
}

/** 요약 프롬프트. 운전 데이터는 넣지 않는다(대화 문장만). */
internal fun summaryPrompt(spoken: List<String>, remaining: List<String>): String = buildString {
    appendLine("운전자에게 음성으로 들려주던 안내가 주행 상황 때문에 중간에 끊겼다.")
    appendLine("이미 말한 부분의 맥락을 한국어 한 문장(35자 이내)으로 요약하라. '아까'로 시작하고 존댓말로 끝내라.")
    appendLine("설명이나 따옴표 없이 요약 문장만 출력하라.")
    appendLine("[이미 말한 부분]")
    spoken.forEach { appendLine(it) }
    if (remaining.isNotEmpty()) {
        appendLine("[이어서 말할 부분]")
        appendLine(remaining.first())
    }
}

/** LLM 출력 정리: 첫 줄, 따옴표 제거, 길이 제한. 쓸 수 없으면 null. */
internal fun cleanSummary(raw: String?): String? {
    val line = raw?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() } ?: return null
    val s = line.trim('"', '\'', '“', '”', '「', '」')
    return s.takeIf { it.length in 4..80 }
}

/**
 * 소리를 내지 않고 문장 길이만큼 기다리는 '자막' 엔진. TTS를 하나도 쓸 수 없는 환경
 * (예: Gleo 기기 내 모델이 로드되지 않는 에뮬레이터, Android TTS 엔진 없음)에서도
 * 음성 게이트의 중단·요약·재개가 화면 자막으로 보이도록 한다.
 */
class CaptionOnlyEngine(
    private val msPerChar: Long = 170L,
    private val minMs: Long = 700L,
) : SpeechEngine {
    override val id = "caption-only"
    @Volatile private var current: kotlinx.coroutines.CompletableDeferred<SpeakResult>? = null

    override suspend fun speak(sentence: String): SpeakResult {
        val done = kotlinx.coroutines.CompletableDeferred<SpeakResult>()
        current = done
        return kotlinx.coroutines.withTimeoutOrNull(minMs + sentence.length * msPerChar) { done.await() } ?: SpeakResult.DONE
    }

    override fun stop() {
        current?.complete(SpeakResult.STOPPED)
    }
}

/**
 * 엔진 사슬: 한 문장이 같은 엔진에서 [maxErrors]번 연속 오류면 그 엔진을 포기하고, 같은 문장을 다음 엔진으로 다시 말한다.
 * 예: Gleo TTS → Android TTS → 자막.
 */
class ChainedSpeechEngine(
    private val engines: List<suspend () -> SpeechEngine?>,
    private val maxErrors: Int = 2,
    private val onSwitch: (String) -> Unit = {},
) : SpeechEngine {
    private var index = 0
    private var engine: SpeechEngine? = null
    override val id: String get() = engine?.id ?: "선택 전"

    private suspend fun current(): SpeechEngine? {
        while (engine == null && index < engines.size) {
            engine = runCatching { engines[index]() }.getOrNull()
            if (engine == null) index++ else onSwitch(engine!!.id)
        }
        return engine
    }

    override suspend fun speak(sentence: String): SpeakResult {
        var attempts = 0
        while (true) {
            val e = current() ?: return SpeakResult.ERROR
            val r = e.speak(sentence)
            if (r != SpeakResult.ERROR) return r
            if (++attempts < maxErrors) continue // 같은 엔진으로 한 번 더
            // 이 엔진은 포기하고 다음 엔진으로 같은 문장을 다시 말한다
            runCatching { e.release() }
            engine = null
            attempts = 0
            index++
        }
    }

    override fun stop() { engine?.stop() }
    override fun release() { engine?.release() }
}
