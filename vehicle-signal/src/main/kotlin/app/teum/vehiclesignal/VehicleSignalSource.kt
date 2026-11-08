package app.teum.vehiclesignal

import app.teum.core.dli.VehicleSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 차량 신호원. 값이 바뀔 때마다 그 시점의 전체 스냅샷을 내보낸다(모르는 필드는 null).
 * 5Hz 샘플링은 소비자(core-dli의 toLoadStates)가 한다.
 */
interface VehicleSignalSource {
    /** 디버그 화면 표시용 이름. */
    val id: String

    /** 신호원 연결 상태. 디버그 화면에 표시한다. */
    val status: StateFlow<SourceStatus>

    fun snapshots(): Flow<VehicleSnapshot>
}

sealed interface SourceStatus {
    data object Connecting : SourceStatus
    /** @param available 이 신호원에서 실제로 받을 수 있는 신호 이름 */
    data class Connected(val available: Set<String>) : SourceStatus
    data class Unavailable(val reason: String) : SourceStatus
}

/**
 * 두 스냅샷을 필드별로 합친다. [primary] 값이 있으면 그것을, 없으면 [fallback] 값을 쓴다.
 * AAOS 표준 우선 정책: primary = AAOS 표준 속성, fallback = Pleos Vehicle SDK.
 */
fun mergePreferPrimary(primary: VehicleSnapshot, fallback: VehicleSnapshot): VehicleSnapshot =
    VehicleSnapshot(
        timestampMs = maxOf(primary.timestampMs, fallback.timestampMs),
        speedKmh = primary.speedKmh ?: fallback.speedKmh,
        steeringAngleDeg = primary.steeringAngleDeg ?: fallback.steeringAngleDeg,
        longitudinalAccelMps2 = primary.longitudinalAccelMps2 ?: fallback.longitudinalAccelMps2,
        brakePressed = primary.brakePressed ?: fallback.brakePressed,
        turnSignalOn = primary.turnSignalOn ?: fallback.turnSignalOn,
        gear = primary.gear ?: fallback.gear,
        wiperLevel = primary.wiperLevel ?: fallback.wiperLevel,
        naviAvailable = primary.naviAvailable || fallback.naviAvailable,
        maneuver = primary.maneuver ?: fallback.maneuver,
    )
