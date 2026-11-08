package app.teum.voicegate

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Gleo(Pleos TTS)를 쓸 수 없을 때의 대체 엔진: Android 표준 TextToSpeech. */
class AndroidTtsEngine(context: Context, locale: Locale = Locale.KOREA) : SpeechEngine {
    override val id = "android-tts"

    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<SpeakResult>>()
    private var seq = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        android.util.Log.i("AndroidTtsEngine", "init status=$status")
        ready.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                pending.remove(utteranceId)?.complete(SpeakResult.DONE)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                pending.remove(utteranceId)?.complete(SpeakResult.ERROR)
            }
            override fun onStop(utteranceId: String, interrupted: Boolean) {
                pending.remove(utteranceId)?.complete(SpeakResult.STOPPED)
            }
        })
    }

    init {
        // 초기화 콜백 뒤에 언어를 맞춘다
        ready.invokeOnCompletion { if (ready.getCompleted()) tts.language = locale }
    }

    suspend fun isAvailable(): Boolean = ready.await()

    override suspend fun speak(sentence: String): SpeakResult {
        if (!ready.await()) return SpeakResult.ERROR
        val id = "teum-${seq++}"
        val done = CompletableDeferred<SpeakResult>()
        pending[id] = done
        val r = tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (r != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return SpeakResult.ERROR
        }
        return suspendCancellableCoroutine { cont ->
            done.invokeOnCompletion { cont.resume(done.getCompleted()) }
            cont.invokeOnCancellation { tts.stop() }
        }
    }

    override fun stop() {
        tts.stop()
        // stop()은 onStop을 부르지만 엔진에 따라 빠질 수 있어 직접 정리한다
        pending.keys.toList().forEach { pending.remove(it)?.complete(SpeakResult.STOPPED) }
    }

    override fun release() {
        stop()
        tts.shutdown()
    }
}
