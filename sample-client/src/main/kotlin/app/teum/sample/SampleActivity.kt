package app.teum.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.teum.loadaware.LoadAwareContract
import app.teum.loadaware.PublicLoadState
import app.teum.loadaware.TeumLoadClient

/**
 * 연동 예제 두 가지:
 * 1) 바운드 서비스(TeumLoadClient): 단계 구독 + 알림 전달 위임(submitMessage)
 * 2) 권한 보호 브로드캐스트(ACTION_STAGE_CHANGED): 런타임 등록 수신자로 단계 변경 수신
 * 3) 시스템 알림: 틈을 모르는 보통 앱처럼 Android 알림을 올린다. 틈의 알림 수집(NotificationListenerService)이
 *    이를 받아 운전 부하에 맞춰 전하는지 확인하는 용도. 자동화: --ez post_notification true
 */
class SampleActivity : ComponentActivity() {
    private lateinit var client: TeumLoadClient
    private var broadcastState by mutableStateOf<PublicLoadState?>(null)
    private var lastResult by mutableStateOf("")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            broadcastState = PublicLoadState.fromBundle(intent.extras)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = TeumLoadClient(this)
        val bound = client.connect()
        if (!bound) lastResult = "틈에 연결할 수 없어요(설치·권한 확인)"
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(LoadAwareContract.ACTION_STAGE_CHANGED),
            // 틈은 READ_LOAD_STATE를 가진 수신자에게만 보낸다(수신자 보호). 같은 액션을 다른 앱이 흉내 낼 수는 있으므로
            // 브로드캐스트는 '변경 알림'으로만 쓰고, 정확한 값은 바운드 서비스(client.state)를 기준으로 삼는다.
            ContextCompat.RECEIVER_EXPORTED,
        )
        if (intent.getBooleanExtra(EXTRA_POST_NOTIFICATION, false)) postSystemNotification()
        setContent {
            val state by client.state.collectAsStateWithLifecycle()
            val connected by client.connected.collectAsStateWithLifecycle()
            Column(
                Modifier.fillMaxSize().background(Color(0xFF0E121A)).padding(40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("틈 연동 예제", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("바인드: ${if (connected) "연결됨" else "끊김"}", fontSize = 20.sp, color = Color(0xFF9AA4B5))
                StageText("서비스 구독", state)
                StageText("브로드캐스트", broadcastState)
                Text(
                    when (state?.stage) {
                        "FOCUS" -> "→ 이 앱은 지금 알림을 띄우지 않고 기다립니다."
                        "CAUTION" -> "→ 긴급한 것만 띄웁니다."
                        "GAP" -> "→ 지금 알림을 띄워도 좋습니다."
                        else -> "→ 틈 상태를 모릅니다. 평소대로 동작합니다."
                    },
                    fontSize = 20.sp, color = Color.White,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = ::submit, modifier = Modifier.width(360.dp).height(76.dp)) {
                        Text("틈에 알림 전달 맡기기", fontSize = 20.sp)
                    }
                    Button(onClick = ::postSystemNotification, modifier = Modifier.width(360.dp).height(76.dp)) {
                        Text("시스템 알림 보내기", fontSize = 20.sp)
                    }
                }
                Text(lastResult, fontSize = 18.sp, color = Color(0xFFFFB627))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_POST_NOTIFICATION, false)) postSystemNotification()
    }

    private var notificationId = 100

    /** 틈과 무관한 보통 앱 알림. 틈은 알림 접근 권한이 있으면 이것을 모아 보류·요약한다. */
    private fun postSystemNotification() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "예제 알림", NotificationManager.IMPORTANCE_DEFAULT))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("김민수")
            .setContentText("이번 주 회의 자료 공유했어요.")
            .build()
        nm.notify(notificationId++, n)
        lastResult = "시스템 알림을 올렸어요. 틈 알림 수집이 켜져 있으면 틈이 받아 전해요."
    }

    private fun submit() {
        if (checkSelfPermission(LoadAwareContract.PERMISSION_SUBMIT_MESSAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(LoadAwareContract.PERMISSION_SUBMIT_MESSAGE), 1)
            return
        }
        lastResult = when (client.submitMessage("배달 앱", "주문하신 음식이 10분 뒤 도착해요.")) {
            LoadAwareContract.SUBMIT_ACCEPTED -> "틈이 받았어요. 운전 부하에 맞춰 전해 줄 거예요."
            LoadAwareContract.SUBMIT_DENIED_PERMISSION -> "권한이 없어요."
            LoadAwareContract.SUBMIT_RATE_LIMITED -> "너무 자주 보냈어요(1분에 10건)."
            LoadAwareContract.SUBMIT_INVALID -> "내용이 잘못됐어요."
            else -> "틈에 연결되지 않았어요."
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == 2) postSystemNotification() else submit()
        }
    }

    private companion object {
        const val CHANNEL = "sample"
        const val EXTRA_POST_NOTIFICATION = "post_notification"
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        client.disconnect()
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
private fun StageText(title: String, s: PublicLoadState?) {
    val color = when (s?.stage) {
        "FOCUS" -> Color(0xFFFF5D4F); "CAUTION" -> Color(0xFFFFB627); "GAP" -> Color(0xFF2EC4A6); else -> Color(0xFF8A93A6)
    }
    Text(
        "$title: " + (s?.let { "${it.stage} · DLI ${it.dli}" + (if (it.preHold) " · 사전 보류" else "") + (if (!it.teumEnabled) " · 틈 OFF" else "") } ?: "없음"),
        fontSize = 24.sp, color = color, fontWeight = FontWeight.SemiBold,
    )
}
