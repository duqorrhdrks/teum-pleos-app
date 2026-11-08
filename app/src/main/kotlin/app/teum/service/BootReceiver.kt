package app.teum.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 시동(부팅) 시 엔진 서비스를 자동 시작한다. AAOS에서는 시동이 곧 부팅/사용자 시작이다.
 * specialUse 포그라운드 서비스는 BOOT_COMPLETED에서 시작할 수 있다(Android 15 제한 대상 유형 아님).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) LoadEngineService.start(context)
    }
}
