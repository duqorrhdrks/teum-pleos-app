package app.teum.core.dli

/** 기어 위치. 신호 출처(AAOS 표준 / Pleos)와 무관한 틈 내부 표현. */
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/**
 * 다음 기동 지점의 유형과 DLI 복잡도.
 * 복잡도 값은 공통 사양(합류 1.0, 분기 0.9, 차로 감소 0.8, 출구 0.7, 직진 0)을 그대로 따른다.
 * 사양에 없는 유형(교차로 좌·우회전 등)은 [OTHER]로 두고 복잡도 0을 쓴다.
 */
enum class ManeuverType(val complexity: Float) {
    MERGE(1.0f),
    DIVERGE(0.9f),
    LANE_REDUCTION(0.8f),
    EXIT(0.7f),
    STRAIGHT(0.0f),
    OTHER(0.0f),
}

/**
 * 다음 기동 지점 정보.
 * @param distanceM 다음 기동까지 남은 거리(m)
 * @param etaSec 도달 예상 시간(초). 내비가 주지 않으면 null이고, 그때는 차속으로 추정한다([estimatedEtaSec]).
 */
data class ManeuverInfo(
    val distanceM: Float,
    val type: ManeuverType,
    val etaSec: Float? = null,
) {
    /** 내비 ETA가 있으면 그 값을, 없으면 거리/차속으로 추정한다. 정차 중(1m/s 미만)에는 추정하지 않는다. */
    fun estimatedEtaSec(speedKmh: Float?): Float? {
        etaSec?.let { return it }
        val v = (speedKmh ?: return null) / 3.6f
        if (v < 1f) return null
        return distanceM / v
    }
}

/**
 * 한 시점의 차량 신호 묶음. 각 필드가 null이면 '신호 없음'이다.
 * DLI 계산에서 해당 항목 기여도는 0이 되고, [DliSample.missing]에 기록된다.
 *
 * @param longitudinalAccelMps2 가속도 센서 값. 있으면 차속 미분값보다 우선한다.
 * @param wiperLevel 와이퍼 단계 0(꺼짐)~3(최고)
 * @param naviAvailable 내비 신호원 자체가 동작하는지. false면 maneuver는 의미가 없다.
 * @param maneuver 다음 기동 지점. 경로 안내가 없거나 다음 기동이 없으면 null.
 */
data class VehicleSnapshot(
    val timestampMs: Long,
    val speedKmh: Float? = null,
    val steeringAngleDeg: Float? = null,
    val longitudinalAccelMps2: Float? = null,
    val brakePressed: Boolean? = null,
    val turnSignalOn: Boolean? = null,
    val gear: Gear? = null,
    val wiperLevel: Int? = null,
    val naviAvailable: Boolean = false,
    val maneuver: ManeuverInfo? = null,
)

/** 디버그 화면의 '신호 없음' 표시용 신호 목록. */
enum class Signal(val label: String) {
    SPEED("차속"),
    STEERING("조향각"),
    ACCEL("종방향 가속도"),
    BRAKE("브레이크"),
    TURN_SIGNAL("방향지시등"),
    GEAR("기어"),
    WIPER("와이퍼"),
    NAVI("내비 기동 정보"),
}

/** DLI 구성 항목. 최대 기여도 합은 100이다. */
enum class Factor(val label: String, val maxPoints: Float) {
    SPEED("속도", 15f),
    STEERING_RATE("조향 속도", 20f),
    LONG_ACCEL("종방향 가속도", 15f),
    BRAKE("브레이크", 10f),
    TURN_SIGNAL("방향지시등", 10f),
    MANEUVER("기동 지점 근접", 20f),
    WEATHER("날씨(와이퍼)", 10f),
}

/** 운전 부하 단계. */
enum class Stage(val label: String) {
    /** 집중: 알림·음성을 모두 멈춘다. */
    FOCUS("집중"),
    /** 주의: 긴급한 것만 통과시킨다. */
    CAUTION("주의"),
    /** 틈: 보류한 것을 모아서 전달한다. */
    GAP("틈"),
}
