package app.teum.vehiclesignal

import app.teum.core.dli.VehicleSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart

/**
 * 실차 신호원: AAOS 표준 속성과 Pleos Vehicle SDK를 필드별 우선순위로 합친다.
 *
 * | 필드 | 1순위 | 2순위 |
 * |---|---|---|
 * | 차속 | AAOS PERF_VEHICLE_SPEED | – |
 * | 기어 | AAOS GEAR_SELECTION | – (Pleos Gear API는 표준 사용 권고로 deprecated) |
 * | 방향지시등 | AAOS TURN_SIGNAL_STATE | Pleos TurnSignal |
 * | 운전대 각도 | Pleos SteeringWheel (운전대 각) | AAOS PERF_STEERING_ANGLE × 조향비 |
 * | 브레이크 | Pleos Brake | – (공개 표준 속성 없음) |
 * | 와이퍼 | Pleos Wiper | – (공개 표준 속성 없음) |
 * | 가속도 | – | core-dli가 차속 변화로 계산 |
 */
class CompositeVehicleSource(
    private val aaos: VehicleSignalSource,
    private val pleos: VehicleSignalSource,
) : VehicleSignalSource {
    override val id: String = "composite(${aaos.id}+${pleos.id})"
    /** 대표 상태는 표준 쪽을 따른다. 상세는 [sources]로 각각 본다. */
    override val status: StateFlow<SourceStatus> = aaos.status
    val sources: List<VehicleSignalSource> get() = listOf(aaos, pleos)

    override fun snapshots(): Flow<VehicleSnapshot> = combine(
        aaos.snapshots().onStart { emit(VehicleSnapshot(0)) },
        pleos.snapshots().onStart { emit(VehicleSnapshot(0)) },
    ) { a, p -> merge(a, p) }

    companion object {
        fun merge(aaos: VehicleSnapshot, pleos: VehicleSnapshot): VehicleSnapshot =
            mergePreferPrimary(aaos, pleos).copy(
                // 운전대 각은 Pleos 우선(표준 속성은 앞바퀴 각 환산값)
                steeringAngleDeg = pleos.steeringAngleDeg ?: aaos.steeringAngleDeg,
            )
    }
}
