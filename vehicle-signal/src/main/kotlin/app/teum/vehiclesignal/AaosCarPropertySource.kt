package app.teum.vehiclesignal

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import app.teum.core.dli.Gear
import app.teum.core.dli.VehicleSnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update

/**
 * AAOS 표준 속성(CarPropertyManager)에서 차량 신호를 읽는다. Pleos 정책상 표준을 먼저 쓴다.
 *
 * | 신호 | 속성 | 권한 |
 * |---|---|---|
 * | 차속 | PERF_VEHICLE_SPEED (m/s) | android.car.permission.CAR_SPEED (런타임) |
 * | 조향각 | PERF_STEERING_ANGLE (°, 앞바퀴 각) | android.car.permission.READ_CAR_STEERING |
 * | 기어 | GEAR_SELECTION | android.car.permission.CAR_POWERTRAIN |
 * | 방향지시등 | TURN_SIGNAL_STATE | android.car.permission.CAR_EXTERIOR_LIGHTS (시스템 권한일 수 있음) |
 *
 * 브레이크 페달과 와이퍼 단계는 공개 VehiclePropertyIds(android-35 android.car.jar)에 없으므로
 * [PleosVehicleSource]가 맡는다. 가속도 센서 속성도 공개 API에 없어 core-dli가 차속 변화로 계산한다.
 *
 * 주의: PERF_STEERING_ANGLE은 '자전거 모델 앞바퀴 조향각'이라 운전대 각도가 아니다. DLI 조향 속도 기준(90°/s)은
 * 운전대 기준이므로, 운전대 각을 주는 Pleos SteeringWheel을 우선 쓰고([CompositeVehicleSource]),
 * 이 값은 [steeringRatio]를 곱해 운전대 각으로 환산한 대체값으로만 쓴다.
 *
 * 권한이 없거나 속성이 없으면 그 필드는 null(신호 없음)로 남고 [status]에 사유가 기록된다.
 */
class AaosCarPropertySource(
    private val context: Context,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    /** 운전대:앞바퀴 조향비. 차종별 값이 필요하다(일반 승용 14~16). TODO(verify-sdk): 차종 정보로 대체 */
    private val steeringRatio: Float = 15f,
) : VehicleSignalSource {
    override val id: String = "aaos-car-property"

    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.Connecting)
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    override fun snapshots(): Flow<VehicleSnapshot> = callbackFlow {
        val state = MutableStateFlow(VehicleSnapshot(timestampMs = nowMs()))
        fun publish(change: (VehicleSnapshot) -> VehicleSnapshot) {
            state.update { change(it).copy(timestampMs = nowMs()) }
            trySend(state.value)
        }

        if (!isAutomotive(context)) {
            _status.value = SourceStatus.Unavailable("자동차 기기가 아님(FEATURE_AUTOMOTIVE 없음)")
            trySend(state.value)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
                when (value.propertyId) {
                    VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                        (value.value as? Float)?.let { mps -> publish { it.copy(speedKmh = kotlin.math.abs(mps) * 3.6f) } }
                    VehiclePropertyIds.PERF_STEERING_ANGLE ->
                        (value.value as? Float)?.let { deg -> publish { it.copy(steeringAngleDeg = deg * steeringRatio) } }
                    VehiclePropertyIds.GEAR_SELECTION ->
                        (value.value as? Int)?.let { g -> publish { it.copy(gear = mapGear(g)) } }
                    VehiclePropertyIds.TURN_SIGNAL_STATE ->
                        // VHAL VehicleTurnSignal: NONE=0, RIGHT=1, LEFT=2 (android.hardware.automotive.vehicle)
                        (value.value as? Int)?.let { s -> publish { it.copy(turnSignalOn = s != 0) } }
                }
            }

            override fun onErrorEvent(propertyId: Int, areaId: Int) {
                Log.w(TAG, "property error $propertyId area=$areaId")
            }
        }

        var car: Car? = null
        var manager: CarPropertyManager? = null
        val available = mutableSetOf<String>()
        val missing = mutableListOf<String>()

        fun subscribe(m: CarPropertyManager) {
            SUBSCRIPTIONS.forEach { (name, propId, rate) ->
                val ok = try {
                    m.getCarPropertyConfig(propId) != null && m.registerCallback(callback, propId, rate)
                } catch (e: SecurityException) {
                    missing += "$name(권한 없음)"
                    false
                } catch (e: IllegalArgumentException) {
                    missing += "$name(미지원)"
                    false
                }
                if (ok) available += name else if (missing.none { it.startsWith(name) }) missing += "$name(미지원)"
            }
            _status.value = if (available.isEmpty()) {
                SourceStatus.Unavailable("표준 속성 없음: ${missing.joinToString()}")
            } else {
                SourceStatus.Connected(available.toSet())
            }
            if (missing.isNotEmpty()) Log.i(TAG, "표준 속성 일부 사용 불가: $missing")
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                    if (ready) {
                        manager = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                        manager?.let(::subscribe)
                    } else {
                        _status.value = SourceStatus.Unavailable("Car 서비스 연결 끊김")
                    }
                }
            } else {
                car = Car.createCar(context)
                manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                manager?.let(::subscribe)
            }
        } catch (t: Throwable) {
            // android.car 라이브러리가 없는 기기(NoClassDefFoundError 포함)
            _status.value = SourceStatus.Unavailable("Car API 사용 불가: ${t.javaClass.simpleName}")
        }
        trySend(state.value)

        awaitClose {
            runCatching { manager?.unregisterCallback(callback) }
            runCatching { car?.disconnect() }
        }
    }.conflate()

    private data class Sub(val name: String, val propId: Int, val rateHz: Float)

    companion object {
        private const val TAG = "AaosCarPropertySource"

        private val SUBSCRIPTIONS = listOf(
            Sub("차속", VehiclePropertyIds.PERF_VEHICLE_SPEED, 10f),
            Sub("조향각", VehiclePropertyIds.PERF_STEERING_ANGLE, 10f),
            Sub("기어", VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE),
            Sub("방향지시등", VehiclePropertyIds.TURN_SIGNAL_STATE, CarPropertyManager.SENSOR_RATE_ONCHANGE),
        )

        fun isAutomotive(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)

        fun mapGear(g: Int): Gear = when (g) {
            VehicleGear.GEAR_PARK -> Gear.PARK
            VehicleGear.GEAR_REVERSE -> Gear.REVERSE
            VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
            VehicleGear.GEAR_DRIVE -> Gear.DRIVE
            VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
            else -> if (g >= VehicleGear.GEAR_FIRST) Gear.DRIVE else Gear.UNKNOWN // 수동 1~9단은 주행으로 본다
        }
    }
}
