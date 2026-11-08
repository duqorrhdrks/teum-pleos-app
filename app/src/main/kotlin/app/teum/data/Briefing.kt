package app.teum.data

import android.content.Context
import app.teum.core.dli.Gear
import app.teum.core.dli.LoadState
import app.teum.core.dli.Stage
import app.teum.notificationqueue.Delivery
import app.teum.notificationqueue.QueueStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BriefingItem(val sender: String, val text: String, val urgent: Boolean, val heldSec: Int, val batch: Boolean)

/** 주행 후 틈 브리핑. 기기 내부 파일에만 저장한다. */
@Serializable
data class Briefing(
    val endedAtEpochMs: Long,
    val durationSec: Int,
    val stageSec: Map<Stage, Int>,
    val focusEntries: Int,
    val voiceInterruptions: Int,
    val voiceResumedWithSummary: Int,
    val received: Int,
    val held: Int,
    val urgentPassed: Int,
    val batches: Int,
    val longestHoldSec: Int,
    val items: List<BriefingItem>,
    val teumEnabled: Boolean,
) {
    val headline: String
        get() = buildString {
            append("${durationSec / 60}분 ${durationSec % 60}초 주행. ")
            append("집중 ${focusEntries}회")
            if (held > 0) append(", 알림 ${held}건을 기다렸다가 전달")
            if (voiceInterruptions > 0) append(", 음성 ${voiceInterruptions}번 멈춤")
            append(".")
        }
}

/**
 * 주행 하나를 기록한다. 순수 로직이라 단위 테스트한다.
 * 주행 끝: 한 번이라도 움직인 뒤(차속 > 5km/h) P단 + 정지가 3초 유지되면.
 */
class TripRecorder(private val nowEpochMs: () -> Long = System::currentTimeMillis) {
    private var startMs: Long? = null
    private var lastMs: Long? = null
    private var lastStage: Stage? = null
    private val stageMs = mutableMapOf<Stage, Long>()
    private var focusEntries = 0
    private var moved = false
    private var parkedSinceMs: Long? = null
    private val items = mutableListOf<BriefingItem>()
    var interruptions = 0
    var resumedWithSummary = 0

    val active: Boolean get() = startMs != null

    /** @return 주행이 방금 끝났으면 true */
    fun onLoadState(s: LoadState): Boolean {
        val t = s.timestampMs
        if (startMs == null) startMs = t
        lastMs?.let { prev -> lastStage?.let { st -> stageMs[st] = (stageMs[st] ?: 0) + (t - prev).coerceIn(0, 2_000) } }
        if (s.stage == Stage.FOCUS && lastStage != Stage.FOCUS) focusEntries++
        lastMs = t
        lastStage = s.stage

        val v = s.snapshot.speedKmh ?: 0f
        if (v > 5f) moved = true
        val parked = s.snapshot.gear == Gear.PARK && v < 0.5f
        parkedSinceMs = if (parked) parkedSinceMs ?: t else null
        return moved && parkedSinceMs?.let { t - it >= 3_000 } == true
    }

    fun onDelivery(d: Delivery, heldUrgentFlags: (Long) -> Boolean = { d.urgent }) {
        d.messages.forEach { m ->
            items += BriefingItem(
                sender = m.sender, text = m.text, urgent = heldUrgentFlags(m.id),
                heldSec = ((d.deliveredAtMs - m.receivedAtMs) / 1000).toInt().coerceAtLeast(0), batch = d.batch,
            )
        }
    }

    fun finish(stats: QueueStats, teumEnabled: Boolean): Briefing {
        val dur = ((lastMs ?: 0) - (startMs ?: 0)) / 1000
        val b = Briefing(
            endedAtEpochMs = nowEpochMs(),
            durationSec = dur.toInt(),
            stageSec = Stage.entries.associateWith { ((stageMs[it] ?: 0) / 1000).toInt() },
            focusEntries = focusEntries,
            voiceInterruptions = interruptions,
            voiceResumedWithSummary = resumedWithSummary,
            received = stats.received,
            held = stats.held,
            urgentPassed = stats.urgentPassed,
            batches = stats.batches,
            longestHoldSec = (stats.longestHoldMs / 1000).toInt(),
            items = items.toList(),
            teumEnabled = teumEnabled,
        )
        reset()
        return b
    }

    fun reset() {
        startMs = null; lastMs = null; lastStage = null; stageMs.clear(); focusEntries = 0
        moved = false; parkedSinceMs = null; items.clear(); interruptions = 0; resumedWithSummary = 0
    }
}

/** 마지막 브리핑을 앱 내부 저장소에 둔다(외부 전송·백업 없음). */
@Singleton
class BriefingStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = File(context.filesDir, "briefing/last.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun save(b: Briefing) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(Briefing.serializer(), b))
    }

    fun load(): Briefing? = runCatching { json.decodeFromString(Briefing.serializer(), file.readText()) }.getOrNull()
}
