package app.teum.core.dli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class DliCalculatorTest {
    private val eps = 1e-3f

    private fun pts(sample: DliSample, f: Factor) = sample.contributions.getValue(f)

    @ParameterizedTest(name = "{0}km/h → {1}점")
    @CsvSource("0,0", "60,0", "90,7.5", "120,15", "180,15")
    fun speed(v: Float, expected: Float) {
        val s = DliCalculator().compute(base(0).copy(speedKmh = v))
        assertEquals(expected, pts(s, Factor.SPEED), eps)
    }

    @Test
    @DisplayName("조향 속도: 0.2초에 9° = 45°/s → 10점, 90°/s 이상 → 20점")
    fun steeringRate() {
        val c = DliCalculator()
        c.compute(base(0).copy(steeringAngleDeg = 0f))
        assertEquals(10f, pts(c.compute(base(200).copy(steeringAngleDeg = 9f)), Factor.STEERING_RATE), eps)
        assertEquals(20f, pts(c.compute(base(400).copy(steeringAngleDeg = -40f)), Factor.STEERING_RATE), eps)
    }

    @Test
    @DisplayName("종방향 가속도: 센서값이 있으면 차속 미분보다 우선")
    fun accelPrefersSensor() {
        val c = DliCalculator()
        c.compute(base(0).copy(speedKmh = 100f))
        // 차속 변화로는 0.2초에 -10.8km/h = -15m/s² (15점)이지만 센서값 1.5m/s²를 써야 한다
        val s = c.compute(base(200).copy(speedKmh = 89.2f, longitudinalAccelMps2 = 1.5f))
        assertEquals(7.5f, pts(s, Factor.LONG_ACCEL), eps)
    }

    @Test
    @DisplayName("종방향 가속도: 센서가 없으면 차속 변화로 계산(감속도 절댓값)")
    fun accelFromSpeed() {
        val c = DliCalculator()
        c.compute(base(0).copy(speedKmh = 50f))
        // 0.2초에 -2.16km/h = -0.6m/s / 0.2s = -3 m/s² → 15점
        val s = c.compute(base(200).copy(speedKmh = 47.84f))
        assertEquals(15f, pts(s, Factor.LONG_ACCEL), 0.01f)
    }

    @ParameterizedTest(name = "{0} {1}m → {2}점")
    @CsvSource(
        "MERGE,0,20", "MERGE,250,10", "MERGE,500,0", "MERGE,800,0",
        "DIVERGE,0,18", "LANE_REDUCTION,0,16", "EXIT,250,7", "STRAIGHT,0,0",
    )
    fun maneuver(type: ManeuverType, d: Float, expected: Float) {
        val s = DliCalculator().compute(base(0).copy(maneuver = ManeuverInfo(d, type)))
        assertEquals(expected, pts(s, Factor.MANEUVER), eps)
    }

    @ParameterizedTest(name = "와이퍼 {0}단 → {1}점")
    @CsvSource("0,0", "1,3", "2,6", "3,10")
    fun weather(level: Int, expected: Float) {
        val s = DliCalculator().compute(base(0).copy(wiperLevel = level))
        assertEquals(expected, pts(s, Factor.WEATHER), eps)
    }

    @Test
    @DisplayName("모든 항목 최대 → 100")
    fun maxIs100() {
        val c = DliCalculator()
        c.compute(base(0).copy(speedKmh = 130f, steeringAngleDeg = 0f))
        val s = c.compute(
            base(200).copy(
                speedKmh = 120f, steeringAngleDeg = 30f, brakePressed = true, turnSignalOn = true,
                wiperLevel = 3, maneuver = ManeuverInfo(0f, ManeuverType.MERGE),
            ),
        )
        assertEquals(100f, s.raw, eps)
    }

    @Test
    @DisplayName("신호 없음: 기여도 0, missing에 기록")
    fun missingSignals() {
        val s = DliCalculator().compute(VehicleSnapshot(timestampMs = 0))
        assertEquals(0f, s.raw, eps)
        assertEquals(Signal.entries.toSet(), s.missing)
    }

    @Test
    @DisplayName("내비 미지원이면 기동 항목 0 + NAVI 신호 없음, 경로 안내만 없으면 신호 없음 아님")
    fun naviMissingVsNoRoute() {
        val m = ManeuverInfo(0f, ManeuverType.MERGE)
        val unsupported = DliCalculator().compute(base(0).copy(naviAvailable = false, maneuver = m))
        assertEquals(0f, pts(unsupported, Factor.MANEUVER), eps)
        assertTrue(Signal.NAVI in unsupported.missing)

        val noRoute = DliCalculator().compute(base(0).copy(naviAvailable = true, maneuver = null))
        assertTrue(Signal.NAVI !in noRoute.missing)
    }

    @Test
    @DisplayName("신호가 1초 넘게 끊겼다 돌아오면 미분 항목은 0")
    fun derivativeGapReset() {
        val c = DliCalculator()
        c.compute(base(0).copy(steeringAngleDeg = 0f, speedKmh = 100f))
        val s = c.compute(base(1_500).copy(steeringAngleDeg = 90f, speedKmh = 50f))
        assertEquals(0f, pts(s, Factor.STEERING_RATE), eps)
        assertEquals(0f, pts(s, Factor.LONG_ACCEL), eps)
    }

    @Test
    @DisplayName("EMA α=0.3")
    fun ema() {
        val e = Ema()
        assertEquals(100f, e.update(100f), eps)
        assertEquals(70f, e.update(0f), eps)
        assertEquals(49f, e.update(0f), eps)
    }
}
