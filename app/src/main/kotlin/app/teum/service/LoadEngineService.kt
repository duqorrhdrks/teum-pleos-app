package app.teum.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import app.teum.R
import app.teum.engine.LoadEngine
import app.teum.ui.DrivingActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 시동 중 계속 도는 포그라운드 서비스. DLI 엔진·음성 게이트·알림 큐를 살려 둔다.
 * 알림은 중요도 LOW(소리·헤드업 없음)로 단계만 조용히 보여 준다.
 */
@AndroidEntryPoint
class LoadEngineService : LifecycleService() {
    @Inject lateinit var engine: LoadEngine

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("시작하는 중"), type)
        engine.start()
        lifecycleScope.launch {
            engine.loadState.map { it?.stage?.label ?: "신호 대기" }.distinctUntilChanged().collect { label ->
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(label))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, getString(R.string.fgs_channel), NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun notification(stage: String): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_teum)
            .setContentTitle("틈 · $stage")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, DrivingActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    companion object {
        private const val CHANNEL = "teum_engine"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, LoadEngineService::class.java))
        }
    }
}
