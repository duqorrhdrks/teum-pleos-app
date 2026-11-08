package app.teum.loadaware

import android.os.Bundle

/**
 * 다른 앱에 공개하는 부하 상태. 개인 주행 데이터(차속·위치·조향 등 원시 신호)는 넣지 않는다.
 * @param stage "FOCUS" | "CAUTION" | "GAP"
 * @param dli 0~100 정수로 반올림한 DLI
 * @param stageSinceElapsedMs 현재 단계 시작 시각(SystemClock.elapsedRealtime 기준)
 * @param preHold 다음 기동 지점이 15초 안이라 긴 음성을 보류해야 하는지
 * @param teumEnabled 틈이 켜져 있는지(OFF면 다른 앱도 기준 동작을 하면 된다)
 */
data class PublicLoadState(
    val stage: String,
    val dli: Int,
    val stageSinceElapsedMs: Long,
    val preHold: Boolean,
    val teumEnabled: Boolean,
) {
    fun toBundle(): Bundle = Bundle().apply {
        putInt(LoadAwareContract.KEY_API_VERSION, LoadAwareContract.API_VERSION)
        putString(LoadAwareContract.KEY_STAGE, stage)
        putInt(LoadAwareContract.KEY_DLI, dli)
        putLong(LoadAwareContract.KEY_STAGE_SINCE, stageSinceElapsedMs)
        putBoolean(LoadAwareContract.KEY_PRE_HOLD, preHold)
        putBoolean(LoadAwareContract.KEY_TEUM_ENABLED, teumEnabled)
    }

    companion object {
        fun fromBundle(b: Bundle?): PublicLoadState? {
            val stage = b?.getString(LoadAwareContract.KEY_STAGE) ?: return null
            return PublicLoadState(
                stage = stage,
                dli = b.getInt(LoadAwareContract.KEY_DLI),
                stageSinceElapsedMs = b.getLong(LoadAwareContract.KEY_STAGE_SINCE),
                preHold = b.getBoolean(LoadAwareContract.KEY_PRE_HOLD),
                teumEnabled = b.getBoolean(LoadAwareContract.KEY_TEUM_ENABLED, true),
            )
        }
    }
}

/** 부하 인지 API 계약. 연동 앱은 이 상수만 알면 된다. */
object LoadAwareContract {
    const val API_VERSION = 1
    const val TEUM_PACKAGE = "app.teum"

    /** 상태 조회·구독(바인드, 브로드캐스트 수신)에 필요. protectionLevel=normal. */
    const val PERMISSION_READ_LOAD_STATE = "app.teum.permission.READ_LOAD_STATE"
    /** 알림 위임(운전자에게 음성으로 들려줄 수 있음)에 필요. protectionLevel=dangerous(사용자 동의). */
    const val PERMISSION_SUBMIT_MESSAGE = "app.teum.permission.SUBMIT_MESSAGE"

    const val ACTION_BIND = "app.teum.action.BIND_LOAD_AWARE"
    /**
     * 단계 변경 브로드캐스트. READ_LOAD_STATE를 가진 수신자에게만 간다.
     * Android 8+ 암시적 브로드캐스트 제한 때문에 매니페스트 수신자는 받지 못하므로 런타임 등록 수신자로 받는다.
     */
    const val ACTION_STAGE_CHANGED = "app.teum.action.LOAD_STAGE_CHANGED"

    const val KEY_API_VERSION = "apiVersion"
    const val KEY_STAGE = "stage"
    const val KEY_DLI = "dli"
    const val KEY_STAGE_SINCE = "stageSinceElapsedMs"
    const val KEY_PRE_HOLD = "preHold"
    const val KEY_TEUM_ENABLED = "teumEnabled"

    const val SUBMIT_ACCEPTED = 0
    const val SUBMIT_DENIED_PERMISSION = 1
    const val SUBMIT_RATE_LIMITED = 2
    const val SUBMIT_INVALID = 3

    const val MAX_TEXT_LENGTH = 200
    const val MAX_SUBMITS_PER_MINUTE = 10
}

/** 앱(uid)별 1분 창 제출 횟수 제한. 바인더 스레드에서 불리므로 동기화한다. */
class SubmitRateLimiter(
    private val maxPerMinute: Int = LoadAwareContract.MAX_SUBMITS_PER_MINUTE,
    private val nowMs: () -> Long,
) {
    private val log = HashMap<Int, ArrayDeque<Long>>()

    @Synchronized
    fun tryAcquire(uid: Int): Boolean {
        val now = nowMs()
        val q = log.getOrPut(uid) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
        if (q.size >= maxPerMinute) return false
        q.addLast(now)
        return true
    }
}

/** 제출 내용 검증: 비어 있지 않고 길이 제한 안. 제어 문자는 공백으로 바꾼다. */
fun sanitizeSubmission(sender: String?, text: String?): Pair<String, String>? {
    val s = sender?.replace(Regex("\\p{Cntrl}"), " ")?.trim()?.take(40).orEmpty()
    val t = text?.replace(Regex("\\p{Cntrl}"), " ")?.trim().orEmpty()
    if (s.isEmpty() || t.isEmpty() || t.length > LoadAwareContract.MAX_TEXT_LENGTH) return null
    return s to t
}
