package app.teum.core.dli

import kotlin.math.abs

/**
 * 한 번 계산한 DLI 결과.
 * @param raw 스무딩 전 합산값(0~100)
 * @param contributions 항목별 기여 점수
 * @param missing 이번 샘플에서 '신호 없음'인 신호
 */
data class DliSample(
    val timestampMs: Long,
    val raw: Float,
    val contributions: Map<Factor, Float>,
    val missing: Set<Signal>,
)

internal fun clamp01(x: Float): Float = x.coerceIn(0f, 1f)

/**
 * 공통 사양의 항목별 점수를 계산한다. 조향 속도와 가속도는 직전 샘플과의 차이로 구하므로 상태가 있다.
 * 스레드 안전하지 않다. 한 신호 흐름에 하나씩 쓴다.
 */
class DliCalculator {
    private var prevTimestampMs: Long? = null
    private var prevSteeringDeg: Float? = null
    private var prevSpeedKmh: Float? = null

    fun reset() {
        prevTimestampMs = null
        prevSteeringDeg = null
        prevSpeedKmh = null
    }

    fun compute(s: VehicleSnapshot): DliSample {
        val missing = mutableSetOf<Signal>()
        val dtMs = prevTimestampMs?.let { s.timestampMs - it }
        val derivativeOk = dtMs != null && dtMs > 0 && dtMs <= DliSpec.MAX_DERIVATIVE_GAP_MS
        val dtSec = (dtMs ?: 0L) / 1000f

        // 속도
        val speedPts = s.speedKmh?.let {
            clamp01((it - DliSpec.SPEED_OFFSET_KMH) / DliSpec.SPEED_RANGE_KMH) * Factor.SPEED.maxPoints
        } ?: 0f.also { missing += Signal.SPEED }

        // 조향 속도 |dθ/dt|
        val steeringPts = if (s.steeringAngleDeg == null) {
            missing += Signal.STEERING
            0f
        } else {
            val prev = prevSteeringDeg
            if (derivativeOk && prev != null) {
                val rate = abs(s.steeringAngleDeg - prev) / dtSec
                clamp01(rate / DliSpec.STEERING_RATE_FULL_DEG_S) * Factor.STEERING_RATE.maxPoints
            } else 0f
        }

        // 종방향 가속도: 센서값 우선, 없으면 차속 변화로 계산
        val accel: Float? = s.longitudinalAccelMps2 ?: run {
            val v = s.speedKmh
            val pv = prevSpeedKmh
            when {
                v == null -> null
                derivativeOk && pv != null -> (v - pv) / 3.6f / dtSec
                else -> 0f // 첫 샘플: 신호는 있으나 아직 미분할 수 없음
            }
        }
        val accelPts = accel?.let {
            clamp01(abs(it) / DliSpec.ACCEL_FULL_MPS2) * Factor.LONG_ACCEL.maxPoints
        } ?: 0f.also { missing += Signal.ACCEL }

        val brakePts = when (s.brakePressed) {
            null -> 0f.also { missing += Signal.BRAKE }
            true -> Factor.BRAKE.maxPoints
            false -> 0f
        }

        val turnPts = when (s.turnSignalOn) {
            null -> 0f.also { missing += Signal.TURN_SIGNAL }
            true -> Factor.TURN_SIGNAL.maxPoints
            false -> 0f
        }

        val maneuverPts = if (!s.naviAvailable) {
            missing += Signal.NAVI
            0f
        } else {
            s.maneuver?.let { m ->
                if (m.distanceM < DliSpec.MANEUVER_RANGE_M) {
                    clamp01(1f - m.distanceM.coerceAtLeast(0f) / DliSpec.MANEUVER_RANGE_M) *
                        m.type.complexity * Factor.MANEUVER.maxPoints
                } else 0f
            } ?: 0f
        }

        val weatherPts = s.wiperLevel?.let {
            DliSpec.WIPER_LEVEL_TO_RAIN[it.coerceIn(0, 3)] * Factor.WEATHER.maxPoints
        } ?: 0f.also { missing += Signal.WIPER }

        if (s.gear == null) missing += Signal.GEAR

        prevTimestampMs = s.timestampMs
        prevSteeringDeg = s.steeringAngleDeg
        prevSpeedKmh = s.speedKmh

        val contributions = linkedMapOf(
            Factor.SPEED to speedPts,
            Factor.STEERING_RATE to steeringPts,
            Factor.LONG_ACCEL to accelPts,
            Factor.BRAKE to brakePts,
            Factor.TURN_SIGNAL to turnPts,
            Factor.MANEUVER to maneuverPts,
            Factor.WEATHER to weatherPts,
        )
        return DliSample(
            timestampMs = s.timestampMs,
            raw = contributions.values.sum().coerceIn(0f, 100f),
            contributions = contributions,
            missing = missing,
        )
    }
}

/** 지수이동평균. 첫 값은 그대로 쓴다. */
class Ema(private val alpha: Float = DliSpec.EMA_ALPHA) {
    private var value: Float? = null

    fun update(x: Float): Float {
        val next = value?.let { alpha * x + (1 - alpha) * it } ?: x
        value = next
        return next
    }

    fun reset() {
        value = null
    }
}
