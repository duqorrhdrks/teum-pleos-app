package app.teum.core.dli

/**
 * 운전 부하 지수(DLI) 공통 사양. 시연 시뮬레이터와 같은 상수를 쓴다.
 * 값을 바꾸면 scenarios/ 의 기대 결과와 시뮬레이터도 함께 바꿔야 한다.
 */
object DliSpec {
    /** 계산 주기 5Hz. */
    const val PERIOD_MS: Long = 200L

    // 정규화 기준
    const val SPEED_OFFSET_KMH = 60f
    const val SPEED_RANGE_KMH = 60f
    const val STEERING_RATE_FULL_DEG_S = 90f
    const val ACCEL_FULL_MPS2 = 3f
    const val MANEUVER_RANGE_M = 500f

    /** 와이퍼 단계 0/1/2/3 → 0, 0.3, 0.6, 1.0 */
    val WIPER_LEVEL_TO_RAIN = floatArrayOf(0f, 0.3f, 0.6f, 1.0f)

    /** 지수이동평균 계수. */
    const val EMA_ALPHA = 0.3f

    // 단계 판정(히스테리시스)
    const val FOCUS_ENTER = 65f
    const val FOCUS_EXIT_BELOW = 50f
    const val FOCUS_EXIT_HOLD_MS = 3_000L
    const val GAP_BELOW = 30f
    const val GAP_HOLD_MS = 2_000L

    /**
     * '차속 0' 판정 허용 오차(km/h). 실차 차속 센서는 정차 중에도 0.1~0.3 수준의 잡음이 있어서
     * 0.5 미만을 0으로 본다.
     */
    const val STOP_SPEED_EPS_KMH = 0.5f

    /** 사전 보류: 다음 기동 도달 예상 시간이 이 값 이내면 긴 음성 시작을 보류한다. */
    const val PRE_HOLD_ETA_SEC = 15f

    /**
     * 미분값(조향 속도, 가속도)을 계산할 수 있는 최대 샘플 간격.
     * 신호가 이보다 오래 끊겼다가 돌아오면 순간 미분값이 튀므로 그 샘플은 0으로 둔다.
     */
    const val MAX_DERIVATIVE_GAP_MS: Long = 1_000L
}
