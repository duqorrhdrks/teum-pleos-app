package app.teum.core.dli

/**
 * 테스트 벡터 생성기. 200ms 간격 스냅샷을 만든다.
 * [frame]은 구간 시작부터의 경과 초(t)를 받아 스냅샷 필드를 채운다.
 */
internal fun segment(
    startMs: Long,
    durationSec: Float,
    frame: (t: Float, tsMs: Long) -> VehicleSnapshot,
): List<VehicleSnapshot> {
    val n = (durationSec * 1000 / DliSpec.PERIOD_MS).toInt()
    return (0 until n).map { i ->
        val ts = startMs + i * DliSpec.PERIOD_MS
        frame(i * DliSpec.PERIOD_MS / 1000f, ts)
    }
}

/** 모든 신호가 있는 기본 스냅샷(고속도로 100km/h 정속, 맑음, 경로 안내 중 다음 기동 없음). */
internal fun base(ts: Long) = VehicleSnapshot(
    timestampMs = ts,
    speedKmh = 100f,
    steeringAngleDeg = 0f,
    longitudinalAccelMps2 = null,
    brakePressed = false,
    turnSignalOn = false,
    gear = Gear.DRIVE,
    wiperLevel = 0,
    naviAvailable = true,
    maneuver = null,
)

internal object Vectors {
    /** 정속: 직진, 미세 조향(±0.5°). */
    fun cruise(startMs: Long = 0, sec: Float = 10f, speedKmh: Float = 100f, wiper: Int = 0) =
        segment(startMs, sec) { _, ts ->
            val i = (ts - startMs) / DliSpec.PERIOD_MS
            base(ts).copy(
                speedKmh = speedKmh,
                wiperLevel = wiper,
                steeringAngleDeg = if (i % 2 == 0L) 0.5f else -0.5f,
            )
        }

    /**
     * 차선 변경(100km/h): 방향지시등 ON, 조향 0→+25°(0.5초)→-25°(1초)→0°(0.5초) = 50°/s,
     * 가볍게 가속(1 m/s²) 후 0.5초 직진. 부하가 올라가지만 집중까지는 가지 않아야 한다(주의).
     */
    fun laneChange(startMs: Long = 0, sec: Float = 2.6f) = segment(startMs, sec) { t, ts ->
        val angle = when {
            t < 0.5f -> 50f * t
            t < 1.5f -> 25f - 50f * (t - 0.5f)
            t < 2.0f -> -25f + 50f * (t - 1.5f)
            else -> 0f
        }
        base(ts).copy(
            speedKmh = 100f + 3.6f * t,
            steeringAngleDeg = angle,
            turnSignalOn = true,
        )
    }

    /**
     * 합류(진입 램프 → 본선): 70→103km/h 가속(≈2.8 m/s²), 방향지시등 ON,
     * 조향 0.2초마다 15°씩 왕복(75°/s), 다음 기동 '합류'까지 300m→0m(3초에 합류 지점 도달).
     * 집중으로 들어가야 한다.
     */
    fun merge(startMs: Long = 0, sec: Float = 3.4f) = segment(startMs, sec) { t, ts ->
        val i = ((ts - startMs) / DliSpec.PERIOD_MS).toInt()
        val angle = when (i % 4) { 0 -> 0f; 1 -> 15f; 2 -> 30f; else -> 15f }
        base(ts).copy(
            speedKmh = 70f + 10f * t,
            steeringAngleDeg = angle,
            turnSignalOn = true,
            maneuver = ManeuverInfo(distanceM = (300f - 100f * t).coerceAtLeast(0f), type = ManeuverType.MERGE),
        )
    }

    /**
     * 신호 정차(빗길, 와이퍼 3단): 50km/h에서 브레이크로 4초간 감속(≈3.5 m/s²) 후 D단 정차 4초.
     * 감속 중에는 주의, 차속 0이 되는 순간 '차속 0 + D' 규칙으로 바로 틈이어야 한다.
     */
    fun signalStop(startMs: Long = 0) =
        segment(startMs, 4f) { t, ts ->
            base(ts).copy(speedKmh = 50f - 12.5f * t, brakePressed = true, wiperLevel = 3)
        } + segment(startMs + 4_000, 4f) { _, ts ->
            base(ts).copy(speedKmh = 0f, brakePressed = true, wiperLevel = 3, gear = Gear.DRIVE)
        }
}

internal fun DliEngine.run(frames: List<VehicleSnapshot>): List<LoadState> = frames.map { process(it) }
