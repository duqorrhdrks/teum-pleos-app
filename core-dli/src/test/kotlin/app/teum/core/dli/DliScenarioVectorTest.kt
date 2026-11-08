package app.teum.core.dli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 완료 기준 1: 정속 / 차선 변경 / 합류 / 신호 정차 테스트 벡터에서 기대 단계가 나온다. */
class DliScenarioVectorTest {

    @Test
    @DisplayName("정속 100km/h → 2초 뒤 틈")
    fun cruiseBecomesGap() {
        val states = DliEngine().run(Vectors.cruise(sec = 6f))
        // 첫 2초 동안은 아직 '연속 2초'를 채우지 못했으므로 주의
        assertEquals(Stage.CAUTION, states.first().stage)
        assertEquals(Stage.GAP, states.last().stage)
        // 정속 DLI = 속도 항목((100-60)/60×15=10) + 미세 조향(0.5°/0.2s=5°/s → 1.1점)
        assertTrue(states.last().dli in 9f..13f, "dli=${states.last().dli}")
    }

    @Test
    @DisplayName("차선 변경 → 틈에서 주의로 올라가고 집중까지는 가지 않는다")
    fun laneChangeIsCaution() {
        val engine = DliEngine()
        val warm = engine.run(Vectors.cruise(sec = 4f))
        assertEquals(Stage.GAP, warm.last().stage)

        val lane = engine.run(Vectors.laneChange(startMs = 4_000))
        assertTrue(lane.none { it.stage == Stage.FOCUS }, "차선 변경에서 집중이 나오면 안 된다")
        assertEquals(Stage.CAUTION, lane.last().stage)
        val peak = lane.maxOf { it.dli }
        assertTrue(peak in 30f..65f, "peak=$peak")
    }

    @Test
    @DisplayName("합류 → 집중 진입, 이후 정속 3초 이상이 지나야 이탈")
    fun mergeIsFocusThenReleases() {
        val engine = DliEngine()
        engine.run(Vectors.cruise(sec = 4f, speedKmh = 70f)) // 진입 램프
        val merge = engine.run(Vectors.merge(startMs = 4_000))
        assertEquals(Stage.FOCUS, merge.last().stage)
        assertTrue(merge.maxOf { it.dli } >= DliSpec.FOCUS_ENTER)

        // 정속 복귀: DLI가 50 아래로 내려간 뒤 3초가 지나야 집중을 벗어난다
        val after = engine.run(Vectors.cruise(startMs = 7_400, sec = 8f))
        val firstBelow50 = after.first { it.dli < 50f }
        val firstExit = after.first { it.stage != Stage.FOCUS }
        assertTrue(
            firstExit.timestampMs - firstBelow50.timestampMs >= DliSpec.FOCUS_EXIT_HOLD_MS,
            "집중 이탈이 너무 이르다: ${firstExit.timestampMs - firstBelow50.timestampMs}ms",
        )
        assertEquals(Stage.GAP, after.last().stage)
    }

    @Test
    @DisplayName("신호 정차(빗길): 감속 중에는 주의, 차속 0·기어 D가 되면 즉시 틈")
    fun signalStopIsGap() {
        val engine = DliEngine()
        val warm = engine.run(Vectors.cruise(sec = 3f, speedKmh = 50f, wiper = 3))
        assertEquals(Stage.GAP, warm.last().stage) // 시내 정속은 틈

        val frames = Vectors.signalStop(startMs = 3_000)
        val states = engine.run(frames)
        val stopIdx = frames.indexOfFirst { it.speedKmh == 0f }
        // 감속 1초 이후 정차 전까지는 주의
        assertTrue(states.subList(5, stopIdx).all { it.stage == Stage.CAUTION }, states.map { it.stage }.toString())
        // 정차 순간 DLI는 30 이상(저부하 규칙 불성립)인데도 정차 규칙으로 바로 틈
        assertTrue(states[stopIdx].dli >= DliSpec.GAP_BELOW, "dli=${states[stopIdx].dli}")
        assertEquals(Stage.GAP, states[stopIdx].stage, "정차 순간 바로 틈이어야 한다")
        assertEquals(Stage.GAP, states.last().stage)
    }

    @Test
    @DisplayName("정차 중이라도 기어가 P면 '차속 0 + D' 규칙은 적용하지 않는다")
    fun parkedDoesNotUseStopRule() {
        val engine = DliEngine()
        // 브레이크(10) + 와이퍼 3단(10) + 방향지시등(10) = 30 → 저부하 2초 규칙도 성립하지 않음
        val frames = segment(0, 1f) { _, ts ->
            base(ts).copy(
                speedKmh = 0f, gear = Gear.PARK, brakePressed = true, wiperLevel = 3, turnSignalOn = true,
            )
        }
        assertEquals(Stage.CAUTION, engine.run(frames).last().stage)
    }
}
