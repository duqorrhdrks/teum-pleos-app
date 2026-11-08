package app.teum.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.teum.passengercue.StageColors
import kotlin.math.roundToInt

/** 처음 실행 안내에서 가리키는 화면 위치. */
enum class GuideSpot { GAUGE, STAGE, STATUS, LIGHT, BUTTONS }

data class GuideStep(val spot: GuideSpot, val title: String, val body: String)

/** 처음 실행 안내 문구. 주행 화면 위에서 차례대로 말풍선으로 보여 준다. */
val FIRST_RUN_GUIDE = listOf(
    GuideStep(
        GuideSpot.GAUGE, "운전 부하 지수(DLI)",
        "속도·운전대·브레이크·방향지시등·날씨·내비 안내를 모아 지금 운전이 얼마나 바쁜지 0~100으로 보여 줘요.",
    ),
    GuideStep(
        GuideSpot.STAGE, "지금 단계",
        "여유 있는 '틈', 조심할 '주의', 운전에 몰두할 '집중' 세 단계예요. 화면 테두리 색도 같이 바뀌어요.",
    ),
    GuideStep(
        GuideSpot.STATUS, "음성과 알림",
        "집중할 때는 AI가 말을 멈추고 알림을 모아 둬요. 틈이 생기면 하던 이야기를 요약해서 이어 가고, 모인 알림을 한꺼번에 전해요.",
    ),
    GuideStep(
        GuideSpot.LIGHT, "쉿 신호등 '또리'",
        "동승자, 특히 아이가 지금 말을 걸어도 되는지 신호등 색으로 알려 줘요. 초록이면 말해도 돼요.",
    ),
    GuideStep(
        GuideSpot.BUTTONS, "직접 해 보기",
        "'시나리오 재생'을 누르면 100초 고속도로 주행을 보여 줘요. '데모 메시지'로 알림이 어떻게 보류되는지도 볼 수 있어요.",
    ),
)

/** 안내 대상 위치를 모아 둔다. 주행 화면 요소에 [guideTarget]으로 붙인다. */
class GuideTargets {
    val bounds = mutableStateMapOf<GuideSpot, Rect>()
}

fun Modifier.guideTarget(targets: GuideTargets, spot: GuideSpot): Modifier =
    onGloballyPositioned { targets.bounds[spot] = it.boundsInRoot() }

private val BubbleColor = Color(0xFFF4F7FB)
private val BubbleText = Color(0xFF0E121A)
private val BubbleTextDim = Color(0xFF4A5468)

/**
 * 처음 실행 안내: 화면을 어둡게 덮고, 설명할 부분만 밝게 뚫은 뒤 말풍선으로 설명한다.
 * 정차 중에만 띄운다(호출하는 쪽에서 CarUxRestrictions 확인). 덮개는 뒤쪽 버튼 터치를 막는다.
 * 이 Composable은 화면 전체(루트 좌표 원점)를 채우는 Box 안에 둬야 [guideTarget] 좌표와 맞는다.
 */
@Composable
fun FirstRunGuide(targets: GuideTargets, onFinish: () -> Unit, steps: List<GuideStep> = FIRST_RUN_GUIDE) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = steps[index.coerceIn(steps.indices)]
    val target = targets.bounds[step.spot] ?: return // 아직 화면 배치 전
    var bubble by remember(index) { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }, // 뒤 화면 터치 막기
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val margin = with(density) { 24.dp.toPx() }
        val gap = with(density) { 26.dp.toPx() }
        val tail = with(density) { 16.dp.toPx() }
        val hole = target.inflate(with(density) { 12.dp.toPx() })
        // 대상이 화면 위쪽 절반이면 말풍선을 아래에, 아니면 위에
        val below = hole.center.y < h / 2f
        val bx = (hole.center.x - bubble.width / 2f).coerceIn(margin, (w - bubble.width - margin).coerceAtLeast(margin))
        val by = (if (below) hole.bottom + gap else hole.top - gap - bubble.height)
            .coerceIn(margin, (h - bubble.height - margin).coerceAtLeast(margin))
        val measured = bubble != IntSize.Zero

        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val radius = CornerRadius(20.dp.toPx())
            drawRect(Color.Black.copy(alpha = 0.66f))
            drawRoundRect(Color.Transparent, hole.topLeft, hole.size, radius, blendMode = BlendMode.Clear)
            drawRoundRect(StageColors.Gap, hole.topLeft, hole.size, radius, style = Stroke(3.dp.toPx()))
            if (measured) {
                // 말풍선 꼬리: 말풍선 가장자리에서 대상 쪽으로
                val tipX = hole.center.x.coerceIn(bx + tail * 2, bx + bubble.width - tail * 2)
                val baseY = if (below) by + 1f else by + bubble.height - 1f
                val tipY = if (below) hole.bottom + 4f else hole.top - 4f
                drawPath(
                    Path().apply {
                        moveTo(tipX - tail, baseY)
                        lineTo(tipX + tail, baseY)
                        lineTo(tipX, tipY)
                        close()
                    },
                    BubbleColor,
                )
            }
        }

        Column(
            Modifier
                .offset { IntOffset(bx.roundToInt(), by.roundToInt()) }
                .widthIn(max = 560.dp)
                .onSizeChanged { bubble = it }
                .alpha(if (measured) 1f else 0f)
                .clip(RoundedCornerShape(24.dp))
                .background(BubbleColor)
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.indices.forEach { i ->
                    Box(
                        Modifier.size(if (i == index) 10.dp else 8.dp).clip(CircleShape)
                            .background(if (i == index) StageColors.Gap else BubbleTextDim.copy(alpha = 0.3f)),
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text("${index + 1} / ${steps.size}", fontSize = 16.sp, color = BubbleTextDim)
            }
            Spacer(Modifier.height(10.dp))
            Text(step.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = BubbleText)
            Spacer(Modifier.height(8.dp))
            Text(
                step.body, fontSize = 21.sp, lineHeight = 30.sp, color = BubbleText,
                // 한국어가 낱말 중간에서 줄바뀜하지 않도록 어절 단위로 끊는다
                style = LocalTextStyle.current.copy(lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase)),
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (index < steps.lastIndex) {
                    TextButton(onClick = onFinish, modifier = Modifier.height(64.dp)) {
                        Text("건너뛰기", fontSize = 20.sp, color = BubbleTextDim)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (index > 0) {
                    TextButton(onClick = { index-- }, modifier = Modifier.height(64.dp)) {
                        Text("이전", fontSize = 20.sp, color = BubbleText)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = { if (index < steps.lastIndex) index++ else onFinish() },
                    modifier = Modifier.height(64.dp).widthIn(min = 140.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StageColors.Gap, contentColor = BubbleText),
                ) {
                    Text(if (index < steps.lastIndex) "다음" else "시작하기", fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
