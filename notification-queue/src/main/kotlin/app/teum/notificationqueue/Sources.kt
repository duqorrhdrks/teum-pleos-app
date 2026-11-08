package app.teum.notificationqueue

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 메시지 유입 통로. 데모 소스, 알림 수집 서비스, 부하 인지 API(다른 앱 위임)가 모두 여기로 보낸다.
 * 시스템이 만드는 서비스(NotificationListenerService, 바운드 서비스)와 앱 그래프를 잇기 위한 프로세스 단일 객체다.
 */
object MessageBus {
    private val _incoming = MutableSharedFlow<TeumMessage>(extraBufferCapacity = 64)
    val incoming: SharedFlow<TeumMessage> = _incoming.asSharedFlow()

    fun post(m: TeumMessage): Boolean = _incoming.tryEmit(m)
}

/** 시연용 메시지 소스. 버튼이나 시나리오 이벤트로 메시지를 만든다(실제 전화·문자 제어 없음). */
class DemoMessageSource(private val nowMs: () -> Long = SystemClock::elapsedRealtime) {
    private val samples = listOf(
        "김팀장" to "내일 회의 자료 확인 부탁드려요.",
        "엄마" to "도착하면 연락 줘.",
        "택배" to "주문하신 상품이 배송 완료되었습니다.",
        "박대리" to "견적서 수정본 보냈습니다.",
        "민지" to "주말에 시간 돼?",
    )
    private var i = 0

    fun next(): TeumMessage {
        val (s, t) = samples[i++ % samples.size]
        return message(s, t)
    }

    fun message(sender: String, text: String, call: Boolean = false, source: TeumMessage.Source = TeumMessage.Source.DEMO) =
        TeumMessage(
            sender = sender, text = text, receivedAtMs = nowMs(), source = source,
            kind = if (call) TeumMessage.Kind.CALL else TeumMessage.Kind.MESSAGE,
        )
}

/**
 * 다른 앱 알림을 수집해 [MessageBus]로 보낸다. 원 알림은 건드리지 않는다(취소·스누즈 없음).
 * AAOS에서 서드파티 앱의 알림 접근 허용 여부는 [NotificationAccess.isGranted]로 먼저 확인한다.
 */
/** 시스템이 사람에게 온 메시지가 아닌 것으로 표시하는 알림 분류. 운전자에게 읽어 줄 필요가 없다. */
private val NON_MESSAGE_CATEGORIES = setOf(
    Notification.CATEGORY_SERVICE, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT,
    Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS, Notification.CATEGORY_NAVIGATION,
)

/**
 * 틈이 모아서 전할 알림인지 판단한다.
 * "○○이(가) 실행 중입니다" 같은 포그라운드 서비스 알림, 지울 수 없는 알림, 그룹 요약,
 * 재생·진행률·내비 같은 상태 알림은 메시지가 아니므로 거른다(2026-11-08 에뮬레이터에서
 * MusicVisualizer 실행 알림을 메시지로 읽은 문제).
 */
fun isPersonMessage(flags: Int, category: String?): Boolean {
    val skipFlags = Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_NO_CLEAR or
        Notification.FLAG_ONGOING_EVENT or Notification.FLAG_GROUP_SUMMARY
    return flags and skipFlags == 0 && category !in NON_MESSAGE_CATEGORIES
}

class TeumNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if (!isPersonMessage(n.flags, n.category)) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val isCall = n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_MISSED_CALL
        MessageBus.post(
            TeumMessage(
                sender = title.ifEmpty { appLabel }, text = text, receivedAtMs = SystemClock.elapsedRealtime(),
                source = TeumMessage.Source.NOTIFICATION_LISTENER,
                kind = if (isCall) TeumMessage.Kind.CALL else TeumMessage.Kind.MESSAGE,
                app = appLabel,
            ),
        )
    }
}

object NotificationAccess {
    fun component(context: Context) = ComponentName(context, TeumNotificationListener::class.java)

    /** 알림 접근이 허용되었는지. API 27 미만이면 enabled_notification_listeners 설정으로 대신 확인한다. */
    fun isGranted(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            nm.isNotificationListenerAccessGranted(component(context))
        } else {
            android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.contains(component(context).flattenToString()) == true
        }
    }

    const val ADB_GRANT_HINT =
        "adb shell cmd notification allow_listener app.teum/app.teum.notificationqueue.TeumNotificationListener"
}
