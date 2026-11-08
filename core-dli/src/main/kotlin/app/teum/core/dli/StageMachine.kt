package app.teum.core.dli

/**
 * 단계 판정 상태머신(히스테리시스).
 *
 * - 집중: 스무딩 DLI ≥ 65가 되면 즉시 진입한다. DLI < 50이 3초 연속 유지되어야 이탈한다.
 * - 틈: DLI < 30이 2초 연속 유지되거나, 차속 0이면서 기어 D이면 틈이다.
 * - 주의: 그 외.
 *
 * 집중을 유지하는 조건이 틈 조건보다 우선한다. 집중 중에 신호 대기로 정차해도 3초 규칙을 채워야 내려간다.
 */
class StageMachine(initial: Stage = Stage.CAUTION) {
    var stage: Stage = initial
        private set

    private var below50SinceMs: Long? = null
    private var below30SinceMs: Long? = null

    fun reset(initial: Stage = Stage.CAUTION) {
        stage = initial
        below50SinceMs = null
        below30SinceMs = null
    }

    /**
     * @param dli 스무딩된 DLI
     * @param stoppedInDrive 차속 0 + 기어 D (신호 없음이면 false)
     */
    fun update(timestampMs: Long, dli: Float, stoppedInDrive: Boolean): Stage {
        below50SinceMs = if (dli < DliSpec.FOCUS_EXIT_BELOW) below50SinceMs ?: timestampMs else null
        below30SinceMs = if (dli < DliSpec.GAP_BELOW) below30SinceMs ?: timestampMs else null

        if (dli >= DliSpec.FOCUS_ENTER) {
            stage = Stage.FOCUS
            return stage
        }

        if (stage == Stage.FOCUS) {
            val since = below50SinceMs
            val canExit = since != null && timestampMs - since >= DliSpec.FOCUS_EXIT_HOLD_MS
            if (!canExit) return stage
        }

        val gapByLowLoad = below30SinceMs?.let { timestampMs - it >= DliSpec.GAP_HOLD_MS } ?: false
        stage = if (gapByLowLoad || stoppedInDrive) Stage.GAP else Stage.CAUTION
        return stage
    }
}

/** 사전 보류 판정: 다음 기동 지점 도달 예상 시간이 15초 이내면 긴 음성 시작을 보류한다. */
object PreHoldPolicy {
    fun shouldHoldLongSpeech(etaSec: Float?): Boolean =
        etaSec != null && etaSec <= DliSpec.PRE_HOLD_ETA_SEC
}
