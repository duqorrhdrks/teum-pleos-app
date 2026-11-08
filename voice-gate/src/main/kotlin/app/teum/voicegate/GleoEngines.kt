@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.teum.voicegate

import ai.pleos.playground.llm.LLM
import ai.pleos.playground.llm.listener.ResultListener
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import ai.pleos.playground.llm.constant.Mode as LlmMode
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.ResultListener as SttResultListener
import ai.pleos.playground.tts.constant.Mode as TtsMode

/**
 * Gleo AI TTS(Pleos TextToSpeech SDK 3.3.1). ON_DEVICE 모드로만 쓴다(주행 데이터·대화의 외부 전송 없음).
 *
 * 확인한 API(공식 문서 TextToSpeech/start-and-stop, 에뮬레이터 실행):
 * - TextToSpeech(context, Mode.ON_DEVICE), initialize(OnInitializeListener), release()
 * - addEventListener(EventListener: onStart/onReady/onDone/onError/onStop/onUpdatedRms)
 * - speak(text, isFinal): isFinal=true(기본)면 한 번의 완결 요청. 문장 단위 큐이므로 항상 true.
 * - stop() → EventListener.onStop()
 * - setLocale(Locale)
 */
class GleoTtsEngine(context: Context) : SpeechEngine {
    override val id = "gleo-tts"

    private val ready = CompletableDeferred<Boolean>()
    private val current = AtomicReference<CompletableDeferred<SpeakResult>?>(null)

    private val tts = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)

    private val events = object : EventListener {
        override fun onStart() { started?.complete(Unit) }
        override fun onReady() {}
        override fun onDone() { current.getAndSet(null)?.complete(SpeakResult.DONE) }
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "TTS error $errorType")
            current.getAndSet(null)?.complete(SpeakResult.ERROR)
        }
        override fun onStop() { current.getAndSet(null)?.complete(SpeakResult.STOPPED) }
        override fun onUpdatedRms(rms: Double) {}
    }

    init {
        // 순서 주의: initialize()가 끝난 뒤에 addEventListener()를 불러야 한다
        // (먼저 부르면 SDK 내부 lateinit이 초기화되지 않아 예외가 난다. 에뮬레이터 v2.1.0에서 확인).
        try {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    Log.i(TAG, "Gleo TTS initialized")
                    runCatching {
                        tts.addEventListener(events)
                        tts.setLocale(Locale.KOREA)
                    }.onFailure { Log.w(TAG, "listener/locale failed", it) }
                    ready.complete(true)
                }
                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "TTS init failed $errorType")
                    ready.complete(false)
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "TTS init threw", t)
            ready.complete(false)
        }
    }

    /** 초기화 결과를 기다린다. Pleos Speech Service가 없으면 false. */
    suspend fun awaitReady(timeoutMs: Long = 3_000): Boolean = withTimeoutOrNull(timeoutMs) { ready.await() } ?: false

    /** 재생 시작 신호(onStart). 끝 신호(onDone)가 늦거나 빠질 때 끝을 추정하는 기준점이다. */
    @Volatile private var started: CompletableDeferred<Unit>? = null

    override suspend fun speak(sentence: String): SpeakResult {
        if (!ready.await()) return SpeakResult.ERROR
        val done = CompletableDeferred<SpeakResult>()
        val begun = CompletableDeferred<Unit>()
        started = begun
        current.getAndSet(done)?.complete(SpeakResult.STOPPED)
        try {
            tts.speak(sentence, true)
        } catch (t: Throwable) {
            Log.w(TAG, "speak threw", t)
            current.compareAndSet(done, null)
            return SpeakResult.ERROR
        }
        // 1) onStart를 기다린다(첫 발화는 모델 로딩으로 수 초 걸릴 수 있음)
        val startedOk = withTimeoutOrNull(START_TIMEOUT_MS) { kotlinx.coroutines.selects.select {
            begun.onAwait { true }
            done.onAwait { false }
        } }
        if (startedOk != true) {
            if (done.isCompleted) return done.getCompleted()
            Log.w(TAG, "onStart 없음: 오류로 처리")
            current.compareAndSet(done, null)
            return SpeakResult.ERROR
        }
        // 2) onDone/onStop/onError를 기다린다. 에뮬레이터 v2.1.0에서 음성 모델이 정상이면 문장마다 onDone이 오고,
        //    stop()에는 onStop이 온다(2026-11-07 확인). 모델이 깨져 있을 때는 onDone이 오지 않았으므로,
        //    안전장치로 문장 길이(+여유)를 넘기면 끝난 것으로 본다. 이때 stop()은 부르지 않는다(말을 끊지 않음).
        val estimateMs = (MIN_SENTENCE_MS + sentence.length * MS_PER_CHAR) * 2
        return try {
            withTimeoutOrNull(estimateMs) { done.await() } ?: SpeakResult.DONE
        } finally {
            current.compareAndSet(done, null)
        }
    }

    override fun stop() {
        runCatching { tts.stop() }
    }

    override fun release() {
        stop()
        runCatching { tts.removeEventListener(events) }
        runCatching { tts.release() }
    }

    private companion object {
        const val TAG = "GleoTtsEngine"
        const val START_TIMEOUT_MS = 8_000L
        const val MIN_SENTENCE_MS = 700L
        /** 한국어 TTS 약 6음절/초 */
        const val MS_PER_CHAR = 170L
    }
}

/**
 * Gleo TTS → Android TextToSpeech → 자막 순서로 쓸 수 있는 엔진을 고른다.
 * [active]는 디버그 화면·주행 화면에 지금 쓰는 엔진을 보여 준다.
 */
class FallbackSpeechEngine private constructor(
    private val chain: ChainedSpeechEngine,
    private val _active: MutableStateFlow<String>,
) : SpeechEngine by chain {
    val active: StateFlow<String> = _active.asStateFlow()

    companion object {
        fun create(context: Context): FallbackSpeechEngine {
            val active = MutableStateFlow("선택 전")
            val main = kotlinx.coroutines.Dispatchers.Main
            val chain = ChainedSpeechEngine(
                engines = listOf(
                    {
                        // SDK 객체는 Looper가 있는 메인 스레드에서 만든다
                        val g = kotlinx.coroutines.withContext(main) { GleoTtsEngine(context) }
                        if (g.awaitReady()) g else { Log.w("FallbackSpeechEngine", "Gleo TTS 준비 실패"); g.release(); null }
                    },
                    {
                        val a = kotlinx.coroutines.withContext(main) { AndroidTtsEngine(context) }
                        if (a.isAvailable()) a else { a.release(); null }
                    },
                    { CaptionOnlyEngine() },
                ),
                onSwitch = { id -> Log.i("FallbackSpeechEngine", "speech engine -> $id"); active.value = id },
            )
            return FallbackSpeechEngine(chain, active)
        }
    }
}

/**
 * Gleo AI LLM(Pleos LLM SDK 2.1.3.2)으로 중단된 맥락을 요약한다. ON_DEVICE 모드.
 *
 * 확인한 API(SDK 공개 API로 컴파일, 에뮬레이터 실행): LLM(context, Mode.ON_DEVICE), initialize(), release(),
 * generateContent(prompt, ResultListener{ onResponse(text, Boolean), onError(String) }).
 * TODO(verify-sdk): onResponse 두 번째 인자의 의미(완료 여부로 추정)와 initialize() 완료 시점은 문서로 확인하지 못했다.
 */
class GleoLlmSummarizer(context: Context) : ContextSummarizer {
    private val llm: LLM? = try {
        LLM(context.applicationContext, LlmMode.ON_DEVICE).also { it.initialize() }
    } catch (t: Throwable) {
        Log.w(TAG, "LLM 사용 불가", t)
        null
    }

    override suspend fun summarize(spoken: List<String>, remaining: List<String>): String? {
        val model = llm ?: return null
        val prompt = summaryPrompt(spoken, remaining)
        val result = CompletableDeferred<String?>()
        val buffer = StringBuilder()
        try {
            model.generateContent(prompt, object : ResultListener {
                override fun onResponse(text: String, isFinal: Boolean) {
                    buffer.append(text)
                    if (isFinal) result.complete(buffer.toString())
                }
                override fun onError(message: String) {
                    Log.w(TAG, "LLM error $message")
                    result.complete(null)
                }
            })
        } catch (t: Throwable) {
            return null
        }
        return cleanSummary(result.await())
    }

    fun release() { runCatching { llm?.release() } }

    private companion object { const val TAG = "GleoLlmSummarizer" }
}

/**
 * Gleo AI STT(Pleos SpeechToText SDK 3.3.1). 운전자가 '말하기'를 눌렀을 때 한 번 인식한다. ON_DEVICE 모드.
 * 마이크는 차량 Speech Service가 연다(앱은 RECORD_AUDIO를 쓰지 않음).
 *
 * 확인한 API(공식 문서 SpeechToText, 에뮬레이터 실행): SpeechToText(context, Mode), initialize(OnInitializeListener), addListener(ResultListener),
 * request(), stop(), setLocale(Locale), release(). ResultListener.onUpdated(text, isFinal).
 */
class GleoSpeechInput(context: Context) {
    private val stt: SpeechToText? = try {
        SpeechToText(context.applicationContext, SttMode.ON_DEVICE).also { s ->
            s.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                override fun onSuccess() { runCatching { s.setLocale(Locale.KOREA) } }
                override fun onFailure(errorType: SttErrorType) { Log.w(TAG, "STT init failed $errorType") }
            })
        }
    } catch (t: Throwable) {
        Log.w(TAG, "STT 사용 불가", t)
        null
    }

    val available: Boolean get() = stt != null

    /** 한 번 인식해 최종 문장을 돌려준다. 실패·시간 초과면 null. */
    suspend fun listenOnce(timeoutMs: Long = 10_000): String? {
        val s = stt ?: return null
        val result = CompletableDeferred<String?>()
        val listener = object : SttResultListener {
            override fun onUpdated(text: String, isFinal: Boolean) { if (isFinal) result.complete(text) }
            override fun onUpdatedEpdData(start: Long, end: Long) {}
            override fun onStartedRecognition() {}
            override fun onEndedRecognition() { if (!result.isCompleted) result.complete(null) }
            override fun onError(errorType: SttErrorType) { result.complete(null) }
            override fun onReady() {}
            override fun onUpdatedRms(rms: Double) {}
        }
        s.addListener(listener)
        return try {
            s.request()
            withTimeoutOrNull(timeoutMs) { result.await() }
        } finally {
            runCatching { s.stop() }
            s.removeListener(listener)
        }
    }

    fun release() { runCatching { stt?.release() } }

    private companion object { const val TAG = "GleoSpeechInput" }
}

/**
 * '말하기' 대화용 Gleo LLM 응답. 운전 중이므로 두 문장 이내로 답하게 한다. ON_DEVICE 모드.
 * 차량 신호·위치는 프롬프트에 넣지 않는다.
 */
class GleoChat(context: Context) {
    private val llm: LLM? = try {
        LLM(context.applicationContext, LlmMode.ON_DEVICE).also { it.initialize() }
    } catch (t: Throwable) {
        null
    }

    suspend fun answer(question: String, timeoutMs: Long = 8_000): String? {
        val model = llm ?: return null
        val prompt = "너는 차량 음성 비서다. 운전 중인 사용자의 질문에 한국어 존댓말 두 문장 이내로 짧게 답하라.\n질문: $question"
        val result = CompletableDeferred<String?>()
        val buf = StringBuilder()
        runCatching {
            model.generateContent(prompt, object : ResultListener {
                override fun onResponse(text: String, isFinal: Boolean) {
                    buf.append(text)
                    if (isFinal) result.complete(buf.toString())
                }
                override fun onError(message: String) { result.complete(null) }
            })
        }.onFailure { return null }
        return withTimeoutOrNull(timeoutMs) { result.await() }?.trim()?.takeIf { it.isNotEmpty() }
    }
}
