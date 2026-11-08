package app.teum.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.teum.core.dli.Factor
import app.teum.core.dli.Signal
import app.teum.data.SignalMode
import app.teum.engine.LoadEngine
import app.teum.notificationqueue.NotificationAccess
import app.teum.passengercue.PassengerCueScreen
import app.teum.passengercue.StageColors
import app.teum.ux.UxRestrictions
import app.teum.ux.UxState
import app.teum.vehiclesignal.SourceStatus
import app.teum.voicegate.Utterance
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** 동승자 화면(distractionOptimized): 앰비언트 바 + 쉿 신호등. 터치하면 닫힌다. */
@AndroidEntryPoint
class PassengerActivity : ComponentActivity() {
    @Inject lateinit var engine: LoadEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TeumTheme {
                val st by engine.loadState.collectAsStateWithLifecycle()
                val settings by engine.settings.collectAsStateWithLifecycle()
                Box(
                    Modifier.fillMaxSize().background(TeumColors.Bg)
                        .padding(0.dp),
                ) {
                    PassengerCueScreen(if (settings.teumEnabled) st?.stage else null)
                    Row(Modifier.align(Alignment.BottomCenter).padding(32.dp).width(420.dp)) {
                        DriveButton("닫기", { finish() })
                    }
                }
            }
        }
    }
}

/**
 * 설정 + 디버그. distractionOptimized를 선언하지 않아 주행 중에는 시스템이 띄우지 않는다.
 * 그래도 CarUxRestrictions를 다시 확인해 주행 중이면 편집을 막는다(정차 시에만 편집 가능).
 */
@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {
    @Inject lateinit var engine: LoadEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TeumTheme { SettingsScreen(engine, onClose = ::finish) } }
    }
}

@Composable
private fun SettingsScreen(engine: LoadEngine, onClose: () -> Unit) {
    val context = LocalContext.current
    val settings by engine.settings.collectAsStateWithLifecycle()
    val st by engine.loadState.collectAsStateWithLifecycle()
    val sources by engine.sources.collectAsStateWithLifecycle()
    val tts by engine.activeTts.collectAsStateWithLifecycle()
    val uxFlow = remember { UxRestrictions.flow(context) }
    val ux by uxFlow.collectAsState(UxState.PARKED_UNKNOWN)
    var contacts by rememberSaveable(settings.emergencyContacts) { mutableStateOf(settings.emergencyContacts.joinToString(", ")) }
    var keywords by rememberSaveable(settings.keywords) { mutableStateOf(settings.keywords.joinToString(", ")) }
    val listenerGranted = remember { NotificationAccess.isGranted(context) }

    Column(Modifier.fillMaxSize().background(TeumColors.Bg).padding(40.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("설정", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = TeumColors.Text, modifier = Modifier.weight(1f))
            Row(Modifier.width(200.dp)) { DriveButton("닫기", onClose) }
        }
        if (!ux.canEdit) {
            Text("주행 중에는 설정을 바꿀 수 없어요. 정차한 뒤에 바꿔 주세요.", color = StageColors.Focus, fontSize = 20.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
        Spacer(Modifier.height(16.dp))
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("틈 켜기", fontSize = 22.sp, color = TeumColors.Text)
                        Text("끄면 '틈 OFF(기준 비교)': 모든 알림과 음성을 즉시 통과시켜요.", fontSize = 16.sp, color = TeumColors.TextDim)
                    }
                    Switch(settings.teumEnabled, { engine.setTeumEnabled(it) }, enabled = ux.canEdit)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("처음 안내 다시 보기", fontSize = 22.sp, color = TeumColors.Text)
                        Text("주행 화면에서 각 부분을 말풍선으로 다시 설명해요.", fontSize = 16.sp, color = TeumColors.TextDim)
                    }
                    Row(Modifier.width(220.dp)) {
                        DriveButton("안내 보기", { engine.setGuideSeen(false); onClose() }, enabled = ux.canEdit)
                    }
                }
                Text("신호 출처", fontSize = 22.sp, color = TeumColors.Text)
                SignalMode.entries.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(settings.signalMode == m, {
                            if (m == SignalMode.SCENARIO) engine.playScenario() else engine.useVehicleSignals()
                        }, enabled = ux.canEdit)
                        Text(m.label, fontSize = 20.sp, color = TeumColors.Text)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("긴급 규칙", fontSize = 22.sp, color = TeumColors.Text)
                Text("긴급 연락처·키워드에 해당하거나, 같은 사람이 3분 안에 3번 보내면 '주의' 단계에서도 바로 전해요.", fontSize = 16.sp, color = TeumColors.TextDim)
                OutlinedTextField(contacts, { contacts = it }, label = { Text("긴급 연락처(쉼표로 구분)") }, enabled = ux.canEdit, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(keywords, { keywords = it }, label = { Text("긴급 키워드(쉼표로 구분)") }, enabled = ux.canEdit, modifier = Modifier.fillMaxWidth())
                Row(Modifier.width(260.dp)) {
                    DriveButton("저장", {
                        engine.saveSettings(
                            contacts.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                            keywords.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        )
                    }, enabled = ux.canEdit, accent = StageColors.Gap.copy(alpha = 0.4f))
                }
                Text("기기 안에만 저장해요. Pleos Sync SDK를 찾지 못해 휴대폰 동기화는 하지 않아요.", fontSize = 14.sp, color = TeumColors.TextDim)
            }
        }
        Spacer(Modifier.height(16.dp))
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("알림 수집", fontSize = 22.sp, color = TeumColors.Text)
                Text(
                    if (listenerGranted) "다른 앱 알림 접근 허용됨" else "다른 앱 알림 접근이 허용되지 않았어요. 데모 메시지와 부하 인지 API 위임으로 동작해요.",
                    fontSize = 16.sp, color = if (listenerGranted) StageColors.Gap else StageColors.Caution,
                )
                if (!listenerGranted) Text("개발용 허용: ${NotificationAccess.ADB_GRANT_HINT}", fontSize = 14.sp, color = TeumColors.TextDim)
            }
        }
        Spacer(Modifier.height(16.dp))
        // 디버그: 신호원·항목별 기여도·신호 없음
        Panel(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("디버그", fontSize = 22.sp, color = TeumColors.Text)
                Text("음성 엔진: $tts · UX 제한: ${ux.source}", fontSize = 16.sp, color = TeumColors.TextDim)
                sources.forEach { s ->
                    val (txt, color) = when (val status = s.status) {
                        is SourceStatus.Connected -> "연결됨 · ${status.available.joinToString()}" to StageColors.Gap
                        is SourceStatus.Unavailable -> "사용 불가 · ${status.reason}" to StageColors.Focus
                        SourceStatus.Connecting -> "연결 중" to StageColors.Caution
                    }
                    Text("${s.id}: $txt", fontSize = 16.sp, color = color)
                }
                Spacer(Modifier.height(8.dp))
                val missing = st?.missing.orEmpty()
                val factorSignal = mapOf(
                    Factor.SPEED to Signal.SPEED, Factor.STEERING_RATE to Signal.STEERING, Factor.LONG_ACCEL to Signal.ACCEL,
                    Factor.BRAKE to Signal.BRAKE, Factor.TURN_SIGNAL to Signal.TURN_SIGNAL, Factor.MANEUVER to Signal.NAVI,
                    Factor.WEATHER to Signal.WIPER,
                )
                Factor.entries.forEach { f ->
                    FactorBar(f.label, st?.contributions?.get(f) ?: 0f, f.maxPoints, st == null || factorSignal[f] in missing)
                }
                Text(
                    "DLI 원값 ${"%.1f".format(st?.dliRaw ?: 0f)} · 스무딩 ${"%.1f".format(st?.dli ?: 0f)} · 기어 ${st?.snapshot?.gear ?: "신호 없음"}" +
                        if (Signal.GEAR in missing) "" else "",
                    fontSize = 16.sp, color = TeumColors.TextDim,
                )
            }
        }
    }
}

/** 주행 후 틈 브리핑(정차 중 화면). */
@AndroidEntryPoint
class BriefingActivity : ComponentActivity() {
    @Inject lateinit var engine: LoadEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TeumTheme { BriefingScreen(engine, ::finish) } }
    }
}

@Composable
private fun BriefingScreen(engine: LoadEngine, onClose: () -> Unit) {
    val b by engine.lastBriefing.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(TeumColors.Bg).padding(40.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("틈 브리핑", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = TeumColors.Text, modifier = Modifier.weight(1f))
            Row(Modifier.width(420.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DriveButton("읽어 주기", { b?.let { engine.voice.say(Utterance("주행 브리핑이에요. " + it.headline, tag = "briefing")) } }, enabled = b != null)
                DriveButton("닫기", onClose)
            }
        }
        val br = b
        if (br == null) {
            Text("아직 끝난 주행이 없어요.", fontSize = 22.sp, color = TeumColors.TextDim)
            return@Column
        }
        Spacer(Modifier.height(12.dp))
        Text(br.headline, fontSize = 24.sp, color = TeumColors.Text)
        if (!br.teumEnabled) Pill("틈 OFF(기준 비교) 주행", StageColors.Off, Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(20.dp))
        StageBar(br.stageSec)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(
                "받은 알림" to "${br.received}건", "기다렸다 전달" to "${br.held}건", "긴급 바로 전달" to "${br.urgentPassed}건",
                "일괄 요약" to "${br.batches}번", "가장 오래 기다림" to "${br.longestHoldSec}초",
                "음성 멈춤/요약 재개" to "${br.voiceInterruptions}/${br.voiceResumedWithSummary}",
            ).forEach { (k, v) ->
                Panel(Modifier.weight(1f)) {
                    Column {
                        Text(v, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TeumColors.Text)
                        Text(k, fontSize = 15.sp, color = TeumColors.TextDim)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("운전 중 받은 알림", fontSize = 22.sp, color = TeumColors.Text)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(br.items) { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.sender, Modifier.width(140.dp), fontSize = 20.sp, color = if (item.urgent) StageColors.Focus else TeumColors.Text, fontWeight = FontWeight.SemiBold)
                    Text(item.text, Modifier.weight(1f), fontSize = 20.sp, color = TeumColors.Text)
                    Text(
                        (if (item.batch) "일괄 · " else "") + if (item.heldSec > 0) "${item.heldSec}초 기다림" else "바로 전달",
                        fontSize = 16.sp, color = TeumColors.TextDim,
                    )
                }
            }
        }
    }
}
