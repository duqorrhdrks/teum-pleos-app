package app.teum.navisignal

import app.teum.core.dli.ManeuverInfo
import app.teum.core.dli.ManeuverType
import app.teum.core.dli.VehicleSnapshot
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class StaleManeuverFilterTest {
    private fun snap(t: Long, kmh: Float, dist: Float?) = VehicleSnapshot(
        timestampMs = t, speedKmh = kmh, naviAvailable = true,
        maneuver = dist?.let { ManeuverInfo(it, ManeuverType.OTHER) },
    )

    @Test
    @DisplayName("에뮬레이터 회귀: 100km/h로 달리는데 '4m 앞 좌회전'이 그대로면 2초 안에 버린다")
    fun stuckDistanceWhileMovingIsDropped() {
        val f = StaleManeuverFilter()
        var out = f.apply(snap(0, 100f, 4f))
        assertNotNull(out.maneuver)
        for (t in 200L..2_000L step 200) out = f.apply(snap(t, 100f, 4f))
        assertNull(out.maneuver, "54m 넘게 달렸는데 4m 남았다는 값은 쓰지 않는다")
    }

    @Test
    @DisplayName("거리가 줄어드는 정상 안내는 그대로 쓴다(1초마다 갱신)")
    fun decreasingDistanceKept() {
        val f = StaleManeuverFilter()
        var dist = 400f
        for (t in 0L..10_000L step 200) {
            if (t % 1_000 == 0L) dist -= 27.8f // 100km/h
            assertNotNull(f.apply(snap(t, 100f, dist)).maneuver, "t=$t")
        }
    }

    @Test
    @DisplayName("정차 중에는 거리가 그대로여도 버리지 않는다")
    fun stoppedKept() {
        val f = StaleManeuverFilter()
        for (t in 0L..60_000L step 200) assertNotNull(f.apply(snap(t, 0f, 30f)).maneuver)
    }
}
