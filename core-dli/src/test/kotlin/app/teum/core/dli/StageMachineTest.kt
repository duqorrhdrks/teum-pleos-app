package app.teum.core.dli

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StageMachineTest {

    /** 200ms 간격으로 같은 DLI를 [ms] 동안 넣고 마지막 단계를 돌려준다. */
    private fun StageMachine.hold(fromMs: Long, ms: Long, dli: Float, stopped: Boolean = false): Pair<Long, Stage> {
        var t = fromMs
        var st = stage
        while (t < fromMs + ms) {
            st = update(t, dli, stopped)
            t += DliSpec.PERIOD_MS
        }
        return t to st
    }

    @Test
    @DisplayName("65 이상이면 즉시 집중")
    fun focusImmediate() {
        val m = StageMachine()
        assertEquals(Stage.FOCUS, m.update(0, 65f, false))
    }

    @Test
    @DisplayName("집중 이탈: 50 미만 2.8초는 유지, 3초가 되면 이탈")
    fun focusExitNeeds3s() {
        val m = StageMachine()
        m.update(0, 80f, false)
        // t=200..3000: 49 유지. 200ms 시작이므로 3200에 3초 충족
        var t = 200L
        while (t < 3_200L) {
            assertEquals(Stage.FOCUS, m.update(t, 49f, false), "t=$t")
            t += 200
        }
        assertEquals(Stage.CAUTION, m.update(3_200, 49f, false))
    }

    @Test
    @DisplayName("집중 이탈 대기 중 50 이상으로 튀면 타이머 초기화")
    fun focusExitTimerResets() {
        val m = StageMachine()
        m.update(0, 80f, false)
        m.hold(200, 2_000, 40f)
        m.update(2_200, 55f, false) // 65 미만이지만 50 이상 → 타이머 초기화, 집중 유지
        assertEquals(Stage.FOCUS, m.stage)
        val (_, st) = m.hold(2_400, 2_800, 40f)
        assertEquals(Stage.FOCUS, st)
        assertEquals(Stage.CAUTION, m.update(5_400, 40f, false))
    }

    @Test
    @DisplayName("틈: 30 미만 2초 연속")
    fun gapNeeds2s() {
        val m = StageMachine()
        val (_, st) = m.hold(0, 2_000, 29f)
        assertEquals(Stage.CAUTION, st) // 0..1800ms: 1.8초
        assertEquals(Stage.GAP, m.update(2_000, 29f, false))
        assertEquals(Stage.CAUTION, m.update(2_200, 31f, false))
    }

    @Test
    @DisplayName("틈: 차속 0 + 기어 D면 즉시 틈")
    fun gapWhenStoppedInDrive() {
        val m = StageMachine()
        assertEquals(Stage.GAP, m.update(0, 45f, true))
    }

    @Test
    @DisplayName("집중 중에는 정차해도 3초 규칙을 채워야 틈으로 간다")
    fun focusBeatsStopRule() {
        val m = StageMachine()
        m.update(0, 70f, false)
        assertEquals(Stage.FOCUS, m.update(200, 45f, true))
        val (t, _) = m.hold(400, 2_800, 45f, stopped = true)
        assertEquals(Stage.GAP, m.update(t, 45f, true))
    }

    @Test
    @DisplayName("사전 보류: ETA 15초 이내면 보류")
    fun preHold() {
        assertTrue(PreHoldPolicy.shouldHoldLongSpeech(15f))
        assertTrue(PreHoldPolicy.shouldHoldLongSpeech(3f))
        assertFalse(PreHoldPolicy.shouldHoldLongSpeech(15.1f))
        assertFalse(PreHoldPolicy.shouldHoldLongSpeech(null))
    }

    @Test
    @DisplayName("ETA 추정: 내비 ETA 우선, 없으면 거리/차속, 정차 중에는 모름")
    fun etaEstimate() {
        assertEquals(10f, ManeuverInfo(500f, ManeuverType.EXIT, etaSec = 10f).estimatedEtaSec(36f))
        assertEquals(12f, ManeuverInfo(300f, ManeuverType.EXIT).estimatedEtaSec(90f)!!, 1e-3f)
        assertNull(ManeuverInfo(300f, ManeuverType.EXIT).estimatedEtaSec(0f))
        assertNull(ManeuverInfo(300f, ManeuverType.EXIT).estimatedEtaSec(null))
    }

    @Test
    @DisplayName("엔진: 100km/h에서 합류 지점 400m(14.4초) → 사전 보류")
    fun enginePreHold() {
        val st = DliEngine().process(base(0).copy(maneuver = ManeuverInfo(400f, ManeuverType.MERGE)))
        assertTrue(st.preHold)
        val far = DliEngine().process(base(0).copy(maneuver = ManeuverInfo(450f, ManeuverType.MERGE)))
        assertFalse(far.preHold) // 16.2초
    }

    @Test
    @DisplayName("5Hz 티커: 값이 바뀌지 않아도 200ms마다 계산하고, 신호 전에는 내보내지 않는다")
    fun tickerRuns5Hz() = runTest {
        val src = MutableSharedFlow<VehicleSnapshot>(replay = 1)
        src.toLoadStates(nowMs = { testScheduler.currentTime }).test {
            expectNoEvents()
            src.emit(base(0))
            val first = awaitItem()
            val second = awaitItem()
            assertEquals(200L, second.timestampMs - first.timestampMs)
            val n = (1..12).map { awaitItem() }.last()
            assertEquals(Stage.GAP, n.stage) // 100km/h 정속 2초 이상
            cancelAndIgnoreRemainingEvents()
        }
    }
}
