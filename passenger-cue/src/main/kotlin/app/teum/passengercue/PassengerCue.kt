package app.teum.passengercue

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.teum.core.dli.Stage

/** 단계별 색. 동승자가 곁눈으로도 구분하도록 색상(빨강·주황·청록)과 밝기 차이를 함께 둔다. */
object StageColors {
    val Focus = Color(0xFFFF5D4F)
    val Caution = Color(0xFFFFB627)
    val Gap = Color(0xFF2EC4A6)
    val Off = Color(0xFF8A93A6)

    fun of(stage: Stage?): Color = when (stage) {
        Stage.FOCUS -> Focus
        Stage.CAUTION -> Caution
        Stage.GAP -> Gap
        null -> Off
    }
}

/** 동승자용 단계 설명(아이도 읽을 수 있게 짧게). */
fun passengerMessage(stage: Stage?): String = when (stage) {
    Stage.FOCUS -> "쉿! 운전에 집중하는 중이에요"
    Stage.CAUTION -> "조금만 기다려 줘요"
    Stage.GAP -> "지금 말해도 좋아요!"
    null -> "틈이 쉬고 있어요"
}

/**
 * 화면 가장자리 앰비언트 바. [content]를 감싸고 네 변에 단계 색 띠를 그린다.
 * 깜빡임은 운전자 시선을 끌 수 있어 쓰지 않고, 색 전환만 0.6초에 걸쳐 부드럽게 한다.
 */
@Composable
fun AmbientEdgeBar(
    stage: Stage?,
    modifier: Modifier = Modifier,
    thickness: Dp = 10.dp,
    glow: Dp = 36.dp,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val color by animateColorAsState(StageColors.of(stage), tween(600), label = "edge")
    Box(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = "운전 부하 단계: ${stage?.label ?: "꺼짐"}" }
            .drawBehind { drawEdgeGlow(color, thickness.toPx(), glow.toPx()) },
        content = content,
    )
}

private fun DrawScope.drawEdgeGlow(color: Color, t: Float, g: Float) {
    val clear = color.copy(alpha = 0f)
    val soft = color.copy(alpha = 0.35f)
    // 안쪽으로 번지는 빛
    drawRect(Brush.verticalGradient(listOf(soft, clear), 0f, g), Offset.Zero, Size(size.width, g))
    drawRect(Brush.verticalGradient(listOf(clear, soft), size.height - g, size.height), Offset(0f, size.height - g), Size(size.width, g))
    drawRect(Brush.horizontalGradient(listOf(soft, clear), 0f, g), Offset.Zero, Size(g, size.height))
    drawRect(Brush.horizontalGradient(listOf(clear, soft), size.width - g, size.width), Offset(size.width - g, 0f), Size(g, size.height))
    // 선명한 띠
    drawRect(color, Offset.Zero, Size(size.width, t))
    drawRect(color, Offset(0f, size.height - t), Size(size.width, t))
    drawRect(color, Offset.Zero, Size(t, size.height))
    drawRect(color, Offset(size.width - t, 0f), Size(t, size.height))
}

/**
 * 아이용 '쉿 신호등' 위젯. 오리지널 캐릭터 '또리'(동그란 신호등 친구)가 단계에 따라 표정을 바꾼다.
 * - 집중: 빨간 불, 눈을 감고 입에 손가락 '쉿'
 * - 주의: 노란 불, 동그란 눈, 일자 입
 * - 틈: 초록 불, 웃는 눈과 입, 볼 홍조
 */
@Composable
fun QuietTrafficLight(stage: Stage?, modifier: Modifier = Modifier, height: Dp = 260.dp, showCaption: Boolean = true) {
    Column(
        modifier.semantics { contentDescription = "쉿 신호등: ${passengerMessage(stage)}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Canvas(Modifier.size(width = height * 0.5f, height = height)) { drawToriLight(stage) }
        if (showCaption) {
            Text(
                passengerMessage(stage),
                color = StageColors.of(stage),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private val Body = Color(0xFF263045)
private val BodyEdge = Color(0xFF3A4660)
private val Face = Color(0xFF1B2233)
private val Cheek = Color(0xFFFF8FA3)

private fun DrawScope.drawToriLight(stage: Stage?) {
    val w = size.width
    val h = size.height
    val bodyH = h * 0.86f
    // 발
    val footW = w * 0.22f
    drawRoundRect(BodyEdge, Offset(w * 0.18f, bodyH - 6f), Size(footW, h - bodyH + 6f), CornerRadius(footW / 2))
    drawRoundRect(BodyEdge, Offset(w * 0.82f - footW, bodyH - 6f), Size(footW, h - bodyH + 6f), CornerRadius(footW / 2))
    // 몸통
    drawRoundRect(Body, Offset.Zero, Size(w, bodyH), CornerRadius(w * 0.5f))
    drawRoundRect(BodyEdge, Offset.Zero, Size(w, bodyH), CornerRadius(w * 0.5f), style = Stroke(w * 0.035f))

    val lamps = listOf(Stage.FOCUS to StageColors.Focus, Stage.CAUTION to StageColors.Caution, Stage.GAP to StageColors.Gap)
    val r = w * 0.32f
    val gap = (bodyH - 6 * r) / 4f
    lamps.forEachIndexed { i, (s, c) ->
        val center = Offset(w / 2, gap * (i + 1) + r * (2 * i + 1))
        val on = s == stage
        if (on) drawCircle(c.copy(alpha = 0.35f), r * 1.25f, center)
        drawCircle(if (on) c else c.copy(alpha = 0.18f), r, center)
        if (on) drawFace(s, center, r)
    }
}

private fun DrawScope.drawFace(stage: Stage, c: Offset, r: Float) {
    val eyeDx = r * 0.38f
    val eyeY = c.y - r * 0.12f
    val stroke = Stroke(width = r * 0.11f, cap = StrokeCap.Round)
    when (stage) {
        Stage.FOCUS -> {
            // 감은 눈(아래로 볼록한 호)
            listOf(-1, 1).forEach { s ->
                drawArc(Face, 20f, 140f, false, Offset(c.x + s * eyeDx - r * 0.16f, eyeY - r * 0.12f), Size(r * 0.32f, r * 0.24f), style = stroke)
            }
            // 오므린 입 + 세운 손가락
            drawCircle(Face, r * 0.08f, Offset(c.x, c.y + r * 0.38f))
            drawRoundRect(Color(0xFFFFE3D0), Offset(c.x - r * 0.07f, c.y + r * 0.08f), Size(r * 0.14f, r * 0.5f), CornerRadius(r * 0.07f))
        }
        Stage.CAUTION -> {
            listOf(-1, 1).forEach { s -> drawCircle(Face, r * 0.1f, Offset(c.x + s * eyeDx, eyeY)) }
            drawLine(Face, Offset(c.x - r * 0.2f, c.y + r * 0.35f), Offset(c.x + r * 0.2f, c.y + r * 0.35f), stroke.width, StrokeCap.Round)
        }
        Stage.GAP -> {
            // 웃는 눈(위로 볼록한 호)
            listOf(-1, 1).forEach { s ->
                drawArc(Face, 200f, 140f, false, Offset(c.x + s * eyeDx - r * 0.16f, eyeY - r * 0.06f), Size(r * 0.32f, r * 0.24f), style = stroke)
            }
            drawArc(Face, 20f, 140f, false, Offset(c.x - r * 0.3f, c.y + r * 0.05f), Size(r * 0.6f, r * 0.45f), style = stroke)
            listOf(-1, 1).forEach { s -> drawCircle(Cheek.copy(alpha = 0.7f), r * 0.11f, Offset(c.x + s * r * 0.62f, c.y + r * 0.2f)) }
        }
    }
}

/** 동승자용 전체 화면: 앰비언트 바 안에 쉿 신호등. */
@Composable
fun PassengerCueScreen(stage: Stage?, modifier: Modifier = Modifier) {
    AmbientEdgeBar(stage, modifier) {
        QuietTrafficLight(stage, Modifier.align(Alignment.Center))
    }
}
