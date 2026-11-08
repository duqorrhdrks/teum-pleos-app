package app.teum.core.dli

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 한 주기의 부하 상태. 앱 전체(음성·알림·동승자 신호·외부 API)가 이 값을 구독한다.
 *
 * @param dli 스무딩된 DLI(0~100). 단계 판정에 쓰는 값.
 * @param stageSinceMs 현재 단계에 들어온 시각
 * @param maneuverEtaSec 다음 기동 지점 도달 예상 시간(초). 모르면 null
 * @param preHold 긴 음성 시작을 보류해야 하는지
 */
data class LoadState(
    val timestampMs: Long,
    val dliRaw: Float,
    val dli: Float,
    val stage: Stage,
    val stageSinceMs: Long,
    val contributions: Map<Factor, Float>,
    val missing: Set<Signal>,
    val maneuverEtaSec: Float?,
    val preHold: Boolean,
    val snapshot: VehicleSnapshot,
)

/** DLI 계산 → EMA → 단계 판정 → 사전 보류를 묶은 엔진. 스레드 안전하지 않다. */
class DliEngine {
    private val calculator = DliCalculator()
    private val ema = Ema()
    private val machine = StageMachine()
    private var stageSinceMs: Long? = null

    fun reset() {
        calculator.reset()
        ema.reset()
        machine.reset()
        stageSinceMs = null
    }

    fun process(s: VehicleSnapshot): LoadState {
        val sample = calculator.compute(s)
        val smoothed = ema.update(sample.raw)
        val stoppedInDrive = s.speedKmh != null && s.speedKmh < DliSpec.STOP_SPEED_EPS_KMH &&
            s.gear == Gear.DRIVE
        val before = machine.stage
        val stage = machine.update(s.timestampMs, smoothed, stoppedInDrive)
        if (stageSinceMs == null || stage != before) stageSinceMs = s.timestampMs

        val eta = if (s.naviAvailable) s.maneuver?.estimatedEtaSec(s.speedKmh) else null
        return LoadState(
            timestampMs = s.timestampMs,
            dliRaw = sample.raw,
            dli = smoothed,
            stage = stage,
            stageSinceMs = stageSinceMs!!,
            contributions = sample.contributions,
            missing = sample.missing,
            maneuverEtaSec = eta,
            preHold = PreHoldPolicy.shouldHoldLongSpeech(eta),
            snapshot = s,
        )
    }
}

/**
 * 신호 흐름을 5Hz로 샘플링해 [LoadState] 흐름으로 바꾼다.
 * 신호원은 값이 바뀔 때만 내보내므로 마지막 값을 붙잡아 두었다가 주기마다 현재 시각으로 다시 찍어 계산한다.
 * 첫 신호가 오기 전에는 아무것도 내보내지 않는다.
 *
 * @param nowMs 시각 공급자. 앱에서는 SystemClock.elapsedRealtime, 테스트에서는 가상 시간.
 */
fun Flow<VehicleSnapshot>.toLoadStates(
    engine: DliEngine = DliEngine(),
    periodMs: Long = DliSpec.PERIOD_MS,
    nowMs: () -> Long,
): Flow<LoadState> = channelFlow {
    val latest = MutableStateFlow<VehicleSnapshot?>(null)
    launch { collect { latest.value = it } }
    launch {
        while (isActive) {
            latest.value?.let { send(engine.process(it.copy(timestampMs = nowMs()))) }
            delay(periodMs)
        }
    }
    awaitClose()
}
