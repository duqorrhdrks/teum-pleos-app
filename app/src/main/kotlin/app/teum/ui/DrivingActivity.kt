package app.teum.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.teum.core.dli.Stage
import app.teum.data.SignalMode
import app.teum.engine.LoadEngine
import app.teum.passengercue.AmbientEdgeBar
import app.teum.passengercue.QuietTrafficLight
import app.teum.passengercue.StageColors
import app.teum.service.LoadEngineService
import app.teum.ux.UxRestrictions
import app.teum.ux.UxState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

/**
 * 주행 중 화면(distractionOptimized). 텍스트 입력·스크롤 목록이 없고, 큰 숫자·색·큰 버튼만 쓴다.
 * CarUxRestrictions의 LIMIT_CONTENT가 켜지면 메시지 미리보기 같은 글은 숨기고 건수만 보인다.
 */
@AndroidEntryPoint
class DrivingActivity : ComponentActivity() {
    @Inject lateinit var engine: LoadEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LoadEngineService.start(this)
        requestNeededPermissions()
        handleDemoIntent(intent)
        setContent { TeumTheme { DrivingScreen(engine) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDemoIntent(intent)
    }

    /**
     * 시연 자동화: adb shell am start -n app.teum/.ui.DrivingActivity --ez play_scenario true | --ez use_vehicle true [--ez teum_off true]
     * 처음 실행 안내 다시 띄우기: --ez show_guide true
     */
    private fun handleDemoIntent(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra(EXTRA_SHOW_GUIDE, false)) engine.setGuideSeen(false)
        if (intent.hasExtra(EXTRA_TEUM_OFF)) engine.setTeumEnabled(!intent.getBooleanExtra(EXTRA_TEUM_OFF, false))
        if (intent.getBooleanExtra(EXTRA_PLAY_SCENARIO, false)) engine.playScenario()
        if (intent.getBooleanExtra(EXTRA_USE_VEHICLE, false)) engine.useVehicleSignals()
    }

    private fun requestNeededPermissions() {
        val wanted = buildList {
            add(CAR_SPEED) // PERF_VEHICLE_SPEED: 런타임 권한
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    private companion object {
        const val CAR_SPEED = "android.car.permission.CAR_SPEED"
        const val EXTRA_PLAY_SCENARIO = "play_scenario"
        const val EXTRA_TEUM_OFF = "teum_off"
        const val EXTRA_USE_VEHICLE = "use_vehicle"
        const val EXTRA_SHOW_GUIDE = "show_guide"
    }
}

@Composable
fun DrivingScreen(engine: LoadEngine) {
    val context = LocalContext.current
    val st by engine.loadState.collectAsStateWithLifecycle()
    val settings by engine.settings.collectAsStateWithLifecycle()
    val voice by engine.voice.status.collectAsStateWithLifecycle()
    val held by engine.queue.held.collectAsStateWithLifecycle()
    val replay by engine.replay.collectAsStateWithLifecycle()
    val listening by engine.listening.collectAsStateWithLifecycle()
    val briefing by engine.lastBriefing.collectAsStateWithLifecycle()
    val ttsEngine by engine.activeTts.collectAsStateWithLifecycle()
    val uxFlow = remember { UxRestrictions.flow(context) }
    val ux by uxFlow.collectAsState(UxState.PARKED_UNKNOWN)
    val position by (replay?.positionSec ?: remember { flowOf(0f) }).collectAsState(0f)

    val stage = if (settings.teumEnabled) st?.stage else null
    val showText = !ux.limitContent
    val guideTargets = remember { GuideTargets() }
    // 설정을 디스크에서 읽기 전 기본값(안내 안 봄)으로 잠깐 뜨는 것을 막고, 화면 배치가 끝난 뒤 띄운다.
    var guideReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(800); guideReady = true }
    // 처음 실행 안내는 정차 중에만, 시나리오 시연 중이 아닐 때만 띄운다.
    val showGuide = guideReady && !settings.guideSeen && !ux.requiresDistractionOptimization &&
        settings.signalMode != SignalMode.SCENARIO

    AmbientEdgeBar(stage) {
        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 28.dp)) {
            // 상단: 이름 + 모드
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("틈", fontSize = 34.sp, fontWeight = FontWeight.Black, color = TeumColors.Text)
                Pill(settings.signalMode.label, TeumColors.TextDim)
                if (!settings.teumEnabled) Pill("틈 OFF · 기준 비교", StageColors.Off)
                when (ttsEngine) {
                    "gleo-tts" -> Pill("음성: Gleo", TeumColors.TextDim)
                    "android-tts" -> Pill("음성: Android TTS", TeumColors.TextDim)
                    "caption-only" -> Pill("음성 엔진 없음 · 자막으로 표시", StageColors.Caution)
                }
                if (st?.missing?.isNotEmpty() == true && showText) {
                    Pill("신호 없음: " + st!!.missing.joinToString { it.label }, StageColors.Focus)
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                DliGauge(st?.dli, stage, Modifier.guideTarget(guideTargets, GuideSpot.GAUGE))
                Spacer(Modifier.width(40.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Column(Modifier.guideTarget(guideTargets, GuideSpot.STAGE)) {
                        Text(
                            stage?.label ?: if (settings.teumEnabled) "대기" else "OFF",
                            fontSize = 72.sp, fontWeight = FontWeight.Black, color = StageColors.of(stage),
                        )
                        Text(
                            if (settings.teumEnabled) stageDescription(stage) else "모든 알림과 음성을 바로 전달해요(기준 비교).",
                            fontSize = 22.sp, color = TeumColors.TextDim,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Column(Modifier.guideTarget(guideTargets, GuideSpot.STATUS)) {
                    StatusLine(
                        title = "음성",
                        value = when {
                            listening -> "듣는 중…"
                            voice.heldReason != null -> "보류 · ${voice.heldReason}"
                            voice.speaking -> if (showText) "말하는 중 · ${voice.current}" else "말하는 중"
                            voice.pending > 0 -> "대기 ${voice.pending}건"
                            else -> "조용함"
                        },
                    )
                    StatusLine(
                        title = "알림",
                        value = if (held.isEmpty()) "기다리는 알림 없음" else buildString {
                            append("기다리는 알림 ${held.size}건")
                            if (showText) append(" · " + held.map { it.message.sender }.distinct().take(3).joinToString(", "))
                        },
                    )
                    st?.maneuverEtaSec?.let { eta ->
                        if (eta < 60) StatusLine("다음 기동", "${eta.toInt()}초" + if (st!!.preHold) " · 긴 음성 보류" else "")
                    }
                    }
                }
                QuietTrafficLight(stage, Modifier.guideTarget(guideTargets, GuideSpot.LIGHT), height = 260.dp, showCaption = false)
            }

            // 시나리오 진행(시연)
            val r = replay
            if (settings.signalMode == SignalMode.SCENARIO && r != null) {
                val expected = r.expectedStageAt(position)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
                    LinearProgressIndicator(
                        progress = { (position / r.scenario.durationSec).coerceIn(0f, 1f) },
                        modifier = Modifier.weight(1f).height(8.dp),
                        color = StageColors.of(stage), trackColor = TeumColors.Line,
                    )
                    Text(
                        "  ${position.toInt()}초 / ${r.scenario.durationSec.toInt()}초" + (expected?.let { " · 기대 ${it.label}" } ?: ""),
                        color = TeumColors.TextDim, fontSize = 18.sp,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().guideTarget(guideTargets, GuideSpot.BUTTONS),
            ) {
                DriveButton(if (listening) "듣는 중" else "말하기", engine::talk, enabled = !listening, accent = Color(0xFF24414A))
                DriveButton("데모 메시지", engine::sendDemoMessage)
                DriveButton("시나리오 재생", engine::playScenario)
                DriveButton("동승자 화면", { context.startActivity(Intent(context, PassengerActivity::class.java)) })
                // 정차 중에만: 설정·브리핑(텍스트 입력·스크롤 목록이 있는 화면)
                DriveButton("설정", { context.startActivity(Intent(context, SettingsActivity::class.java)) }, enabled = !ux.requiresDistractionOptimization)
                DriveButton(
                    if (briefing != null) "틈 브리핑" else "브리핑 없음",
                    { context.startActivity(Intent(context, BriefingActivity::class.java)) },
                    enabled = briefing != null && !ux.requiresDistractionOptimization,
                )
            }
        }
        if (showGuide) FirstRunGuide(guideTargets, onFinish = { engine.setGuideSeen(true) })
    }
}

@Composable
private fun StatusLine(title: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(110.dp)) { Text(title, color = TeumColors.TextDim, fontSize = 20.sp) }
        Text(value, color = TeumColors.Text, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
