package app.teum.vehiclesignal

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.os.SystemClock
import android.util.Log
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
 * Pleos Vehicle SDK(ai.pleos.playground:Vehicle:2.3.2)로만 얻을 수 있는 신호를 읽는다.
 *
 * 클래스·메서드·권한은 공식 문서, 공식 샘플(pleos-sample-vehicle-app), 에뮬레이터 실행으로 확인했다.
 * | 신호 | API | 권한 |
 * |---|---|---|
 * | 브레이크 페달 | Brake.registerBrakePedalStatus | pleos.car.permission.CAR_DRIVE |
 * | 운전대 각도 | SteeringWheel.registerSteeringWheelAngle | pleos.car.permission.CAR_DRIVE |
 * | 와이퍼 | Wiper.registerWiperState + getCurrentWiperSwitch | pleos.car.permission.CAR_WINDOWS |
 * | 방향지시등 | TurnSignal.registerTurnSignalState | pleos.car.permission.CAR_LIGHTS |
 *
 * 기어는 쓰지 않는다. SDK 2.3.2의 Gear.registerGearSelection은 @Deprecated("use the Google GEAR_SELECTION
 * property")라서 AAOS 표준 GEAR_SELECTION([AaosCarPropertySource])만 쓴다.
 * 방향지시등은 표준 TURN_SIGNAL_STATE가 시스템 권한일 수 있어 [CompositeVehicleSource]에서 대체값으로 쓴다.
 * 운전대 각도는 표준 속성(PERF_STEERING_ANGLE)이 앞바퀴 각이라 Pleos 값을 우선한다.
 */
class PleosVehicleSource(
    context: Context,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : VehicleSignalSource {
    override val id: String = "pleos-vehicle"
    private val appContext = context.applicationContext

    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.Connecting)
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    override fun snapshots(): Flow<VehicleSnapshot> = callbackFlow {
        val state = MutableStateFlow(VehicleSnapshot(timestampMs = nowMs()))
        val available = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun publish(name: String, change: (VehicleSnapshot) -> VehicleSnapshot) {
            available += name
            _status.value = SourceStatus.Connected(available.toSet())
            state.update { change(it).copy(timestampMs = nowMs()) }
            trySend(state.value)
        }
        fun failed(name: String, e: Exception) {
            Log.w(TAG, "$name 실패: ${e.message}")
            available -= name
            if (available.isEmpty()) _status.value = SourceStatus.Unavailable("$name: ${e.message}")
        }

        val vehicle = try {
            Vehicle(appContext).also { it.initialize() }
        } catch (e: Throwable) {
            // Pleos Vehicle Service가 없는 기기(일반 AAOS 에뮬레이터 등)
            _status.value = SourceStatus.Unavailable("Pleos Vehicle SDK 사용 불가: ${e.message}")
            trySend(state.value)
            awaitClose { }
            return@callbackFlow
        }

        val frontWindshield = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val wiper = vehicle.getWiper()

        val brakeListener = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) {
                publish(BRAKE) { it.copy(brakePressed = status) }
            }
            override fun onFailed(e: Exception) = failed(BRAKE, e)
        }
        val steeringListener = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) {
                publish("운전대 각도") { it.copy(steeringAngleDeg = angle) }
            }
            override fun onFailed(e: Exception) = failed("운전대 각도", e)
        }
        val turnListener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
                publish(TURN_SIGNAL) { it.copy(turnSignalOn = value?.let { v -> v != TurnSignalValue.OFF }) }
            }
            override fun onFailed(e: Exception) = failed(TURN_SIGNAL, e)
        }
        // WiperState는 ON/OFF만 알려 준다. 단계는 스위치 위치(getCurrentWiperSwitch)로 읽는다.
        val wiperListener = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) {
                if (state == WiperState.OFF) {
                    publish(WIPER) { it.copy(wiperLevel = 0) }
                    return
                }
                wiper.getCurrentWiperSwitch(
                    frontWindshield,
                    { sw -> publish(WIPER) { it.copy(wiperLevel = mapWiperLevel(sw, state)) } },
                    { e -> failed(WIPER, e) },
                )
            }
            override fun onFailed(e: Exception) = failed(WIPER, e)
        }

        val registered = mutableSetOf<String>()
        fun guard(name: String, block: () -> Unit) = try {
            block()
            registered += name
        } catch (e: Exception) {
            failed(name, e)
        }

        guard(BRAKE) { vehicle.getBrake().registerBrakePedalStatus(brakeListener) }
        guard("운전대 각도") { vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener) }
        guard(TURN_SIGNAL) { vehicle.getTurnSignal().registerTurnSignalState(turnListener) }
        guard(WIPER) { wiper.registerWiperState(frontWindshield, wiperListener) }

        // 등록만으로는 현재 값이 오지 않는다. 값이 바뀔 때만 알려 줘서, 부팅 뒤 한 번도 안 건드린
        // 방향지시등·와이퍼는 계속 '신호 없음'으로 보였다(에뮬레이터, 2026-11-08 확인).
        // 등록에 성공한 켜짐/꺼짐 신호는 첫 변화가 오기 전까지 꺼짐으로 둔다. DLI 점수는 '신호 없음'과 같은 0점이고,
        // '신호 없음' 표시는 실제로 구독하지 못한 신호에만 남는다.
        val defaults = registered intersect DEFAULTED_SIGNALS
        if (defaults.isNotEmpty()) {
            available += defaults
            _status.value = SourceStatus.Connected(available.toSet())
            state.update { withRegisteredDefaults(it, defaults) }
        }
        // 와이퍼는 스위치 위치를 직접 읽을 수 있으므로 실제 값으로 덮어쓴다.
        if (WIPER in registered) {
            runCatching {
                wiper.getCurrentWiperSwitch(
                    frontWindshield,
                    { sw -> mapWiperLevel(sw, null)?.let { lv -> publish(WIPER) { it.copy(wiperLevel = lv) } } },
                    { e -> Log.w(TAG, "와이퍼 스위치 위치 읽기 실패: ${e.message}") },
                )
            }
        }
        trySend(state.value)

        awaitClose {
            // 문서: 등록에 쓴 같은 객체·영역으로 해제하고, 다 쓰면 release()를 호출한다.
            runCatching { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) }
            runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
            runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(turnListener) }
            runCatching { wiper.unregisterWiperState(frontWindshield, wiperListener) }
            runCatching { vehicle.release() }
        }
    }.conflate()

    companion object {
        private const val TAG = "PleosVehicleSource"
        const val BRAKE = "브레이크"
        const val TURN_SIGNAL = "방향지시등"
        const val WIPER = "와이퍼"

        /** 바뀔 때만 값이 오는 켜짐/꺼짐 신호: 등록에 성공했으면 첫 값이 오기 전까지 꺼짐으로 본다. */
        val DEFAULTED_SIGNALS = setOf(BRAKE, TURN_SIGNAL, WIPER)

        /** [registered]에 든 신호 중 아직 값이 없는 것만 꺼짐(브레이크 안 밟음·방향지시등 꺼짐·와이퍼 0단)으로 채운다. */
        fun withRegisteredDefaults(s: VehicleSnapshot, registered: Set<String>): VehicleSnapshot = s.copy(
            brakePressed = s.brakePressed ?: if (BRAKE in registered) false else null,
            turnSignalOn = s.turnSignalOn ?: if (TURN_SIGNAL in registered) false else null,
            wiperLevel = s.wiperLevel ?: if (WIPER in registered) 0 else null,
        )

        /**
         * 와이퍼 스위치 위치 → DLI 와이퍼 단계(0~3).
         * 간헐·안개 → 1, 연속 1~2단 → 2, 연속 3단 이상 → 3.
         * AUTO는 빗물 감지 강도를 알 수 없으므로 작동 중이면 1로 본다.
         */
        fun mapWiperLevel(sw: WiperSwitch?, state: WiperState?): Int? = when (sw) {
            null -> if (state == WiperState.ON) 1 else null
            WiperSwitch.OFF, WiperSwitch.SERVICE -> 0
            WiperSwitch.MIST,
            WiperSwitch.INTERMITTENT_LEVEL_1, WiperSwitch.INTERMITTENT_LEVEL_2,
            WiperSwitch.INTERMITTENT_LEVEL_3, WiperSwitch.INTERMITTENT_LEVEL_4,
            WiperSwitch.INTERMITTENT_LEVEL_5 -> 1
            WiperSwitch.CONTINUOUS_LEVEL_1, WiperSwitch.CONTINUOUS_LEVEL_2 -> 2
            WiperSwitch.CONTINUOUS_LEVEL_3, WiperSwitch.CONTINUOUS_LEVEL_4,
            WiperSwitch.CONTINUOUS_LEVEL_5 -> 3
            WiperSwitch.AUTO -> if (state == WiperState.ON) 1 else 0
            WiperSwitch.OTHER -> if (state == WiperState.ON) 1 else null
        }
    }
}
