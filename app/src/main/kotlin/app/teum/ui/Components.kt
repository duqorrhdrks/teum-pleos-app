package app.teum.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.teum.core.dli.DliSpec
import app.teum.core.dli.Stage
import app.teum.passengercue.StageColors
import kotlin.math.cos
import kotlin.math.sin

object TeumColors {
    val Bg = Color(0xFF0E121A)
    val Panel = Color(0xFF171D29)
    val PanelHigh = Color(0xFF212938)
    val Text = Color(0xFFEDF1F7)
    val TextDim = Color(0xFF9AA4B5)
    val Line = Color(0xFF2C3546)
}

@Composable
fun TeumTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = TeumColors.Bg, surface = TeumColors.Panel, primary = StageColors.Gap,
            onBackground = TeumColors.Text, onSurface = TeumColors.Text, secondary = StageColors.Caution,
        ),
        content = content,
    )
}

fun stageDescription(stage: Stage?): String = when (stage) {
    Stage.FOCUS -> "운전에 집중할 때예요. 음성과 알림을 멈췄어요."
    Stage.CAUTION -> "긴급한 알림만 전해요."
    Stage.GAP -> "여유 있는 틈이에요. 기다린 알림을 전해요."
    null -> "차량 신호를 기다리는 중"
}

/**
 * DLI 게이지: 240° 원호, 30·50·65 경계 눈금. 숫자는 크게, 글자는 최소로(주행 중 한눈에).
 */
@Composable
fun DliGauge(dli: Float?, stage: Stage?, modifier: Modifier = Modifier, diameter: Dp = 300.dp) {
    val target = (dli ?: 0f).coerceIn(0f, 100f)
    val value by animateFloatAsState(target, tween(180), label = "dli")
    val color by animateColorAsState(StageColors.of(stage), tween(500), label = "gaugeColor")
    Box(modifier.size(diameter).semantics { contentDescription = "운전 부하 지수 ${target.toInt()}" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val stroke = size.minDimension * 0.07f
            val inset = stroke / 2 + 8f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val start = 150f
            val sweep = 240f
            drawArc(TeumColors.Line, start, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(color, start, sweep * value / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            // 단계 경계 눈금
            val r = arcSize.width / 2
            val c = Offset(size.width / 2, size.height / 2)
            listOf(DliSpec.GAP_BELOW, DliSpec.FOCUS_EXIT_BELOW, DliSpec.FOCUS_ENTER).forEach { v ->
                val a = Math.toRadians((start + sweep * v / 100f).toDouble())
                val p1 = Offset(c.x + (r - stroke) * cos(a).toFloat(), c.y + (r - stroke) * sin(a).toFloat())
                val p2 = Offset(c.x + (r + stroke) * cos(a).toFloat(), c.y + (r + stroke) * sin(a).toFloat())
                drawLine(TeumColors.TextDim, p1, p2, 3f)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(dli?.toInt()?.toString() ?: "–", fontSize = 84.sp, fontWeight = FontWeight.Bold, color = TeumColors.Text)
            Text("DLI", fontSize = 18.sp, color = TeumColors.TextDim)
        }
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
    )
}

/** 주행 중 버튼: 큰 터치 영역(높이 76dp, AAOS 권장 최소 크기 이상). */
@Composable
fun RowScope.DriveButton(label: String, onClick: () -> Unit, enabled: Boolean = true, accent: Color = TeumColors.PanelHigh) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f).height(76.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = TeumColors.Text),
    ) { Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(24.dp)).background(TeumColors.Panel).padding(24.dp),
    ) { content() }
}

/** 단계별 시간 막대(브리핑). */
@Composable
fun StageBar(seconds: Map<Stage, Int>, modifier: Modifier = Modifier) {
    val total = seconds.values.sum().coerceAtLeast(1)
    Row(modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp))) {
        listOf(Stage.GAP, Stage.CAUTION, Stage.FOCUS).forEach { s ->
            val sec = seconds[s] ?: 0
            if (sec > 0) Box(Modifier.weight(sec.toFloat() / total).height(28.dp).background(StageColors.of(s)))
        }
    }
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        listOf(Stage.GAP, Stage.CAUTION, Stage.FOCUS).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(StageColors.of(s)))
                Text(" ${s.label} ${seconds[s] ?: 0}초", color = TeumColors.TextDim, fontSize = 16.sp)
            }
        }
    }
}

@Composable
fun FactorBar(label: String, points: Float, max: Float, missing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.width(150.dp), color = TeumColors.TextDim, fontSize = 16.sp)
        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(TeumColors.Line)) {
            Box(Modifier.fillMaxWidth((points / max).coerceIn(0f, 1f)).height(14.dp).background(StageColors.Caution))
        }
        Text(
            if (missing) "신호 없음" else "%.1f / %.0f".format(points, max),
            Modifier.width(130.dp).padding(start = 12.dp),
            color = if (missing) StageColors.Focus else TeumColors.Text, fontSize = 16.sp,
        )
    }
}
