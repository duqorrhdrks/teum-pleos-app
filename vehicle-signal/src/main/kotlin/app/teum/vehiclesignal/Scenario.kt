package app.teum.vehiclesignal

import app.teum.core.dli.Gear
import app.teum.core.dli.ManeuverInfo
import app.teum.core.dli.ManeuverType
import app.teum.core.dli.Signal
import app.teum.core.dli.Stage
import app.teum.core.dli.VehicleSnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.sin

/**
 * 시연·테스트용 주행 시나리오. 정본은 저장소의 scenarios/ 폴더에 있고,
 * 시연 시뮬레이터와 같은 파일을 쓴다.
 *
 * 키프레임 필드는 모두 선택이다. 정의된 키프레임끼리 숫자 값은 선형 보간,
 * (그래서 값을 일정하게 유지하다가 바꾸려면 바꾸기 직전 키프레임에 같은 값을 한 번 더 적는다)
 * 불·열거 값은 직전 키프레임 값을 유지한다(계단). 한 번도 정의되지 않은 필드는 '신호 없음'이다.
 */
@Serializable
data class Scenario(
    val name: String,
    val description: String = "",
    val durationSec: Float,
    val keyframes: List<Keyframe>,
    val events: List<ScenarioEvent> = emptyList(),
    /** 테스트와 시연 화면의 '기대 단계' 표시용 구간. */
    val expect: List<ExpectedStage> = emptyList(),
)

@Serializable
data class Keyframe(
    val t: Float,
    val speedKmh: Float? = null,
    val steeringDeg: Float? = null,
    /** 조향 흔들림 진폭(°)과 주파수(Hz). steeringDeg에 사인파를 더한다. 차로 유지·합류 조향 재현용. */
    val steeringWobbleDeg: Float? = null,
    val steeringWobbleHz: Float? = null,
    val accelMps2: Float? = null,
    val brake: Boolean? = null,
    val turnSignal: Boolean? = null,
    val gear: Gear? = null,
    val wiper: Int? = null,
    /** NONE이면 다음 기동 없음. */
    val maneuverType: String? = null,
    val maneuverDistanceM: Float? = null,
    /** 이 시점부터 끊는 신호(디버그 화면 '신호 없음' 시연용). 빈 목록이면 복구. */
    val dropSignals: List<Signal>? = null,
)

@Serializable
data class ScenarioEvent(
    val t: Float,
    val kind: Kind,
    val text: String,
    val sender: String? = null,
    /** 긴 음성(사전 보류 대상)인지. speech에만 의미가 있다. */
    val long: Boolean = false,
) {
    @Serializable
    enum class Kind {
        /** AI 음성 대화 발화 */
        @SerialName("speech") SPEECH,
        /** 메시지 알림 */
        @SerialName("message") MESSAGE,
        /** 전화 수신(실제 전화 제어는 하지 않고 데모로만 표시) */
        @SerialName("call") CALL,
    }
}

@Serializable
data class ExpectedStage(val from: Float, val to: Float, val stage: Stage)

object ScenarioParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): Scenario {
        val s = json.decodeFromString(Scenario.serializer(), text)
        require(s.keyframes.isNotEmpty()) { "keyframes가 비어 있다" }
        require(s.keyframes.zipWithNext().all { (a, b) -> a.t <= b.t }) { "keyframes는 t 오름차순이어야 한다" }
        return s
    }
}

/**
 * 시나리오의 임의 시각 스냅샷을 계산한다. 순수 함수라 JVM 단위 테스트에서 그대로 쓴다.
 */
class ScenarioTimeline(val scenario: Scenario) {
    private val frames = scenario.keyframes

    private fun <T : Any> points(sel: (Keyframe) -> T?): List<Pair<Float, T>> =
        frames.mapNotNull { k -> sel(k)?.let { k.t to it } }

    private val speed = points { it.speedKmh }
    private val steering = points { it.steeringDeg }
    private val wobbleA = points { it.steeringWobbleDeg }
    private val wobbleF = points { it.steeringWobbleHz }
    private val accel = points { it.accelMps2 }
    private val brake = points { it.brake }
    private val turn = points { it.turnSignal }
    private val gear = points { it.gear }
    private val wiper = points { it.wiper }
    private val mType = points { it.maneuverType }
    private val mDist = points { it.maneuverDistanceM }
    private val drops = points { it.dropSignals }

    private fun linear(p: List<Pair<Float, Float>>, t: Float): Float? {
        if (p.isEmpty() || t < p.first().first) return null
        val i = p.indexOfLast { it.first <= t }
        val (t0, v0) = p[i]
        val next = p.getOrNull(i + 1) ?: return v0
        val (t1, v1) = next
        if (t1 <= t0) return v1
        return v0 + (v1 - v0) * ((t - t0) / (t1 - t0))
    }

    private fun <T> step(p: List<Pair<Float, T>>, t: Float): T? =
        p.lastOrNull { it.first <= t }?.second

    fun snapshotAt(tSec: Float, timestampMs: Long): VehicleSnapshot {
        val dropped = step(drops, tSec).orEmpty().toSet()
        fun <T> keep(sig: Signal, v: T?): T? = if (sig in dropped) null else v

        val baseSteer = linear(steering, tSec)
        val a = linear(wobbleA, tSec) ?: 0f
        val f = linear(wobbleF, tSec) ?: 0f
        val steer = baseSteer?.let { it + a * sin(2 * PI * f * tSec).toFloat() }

        val type = step(mType, tSec)
        val maneuver = if (type == null || type == "NONE") null else ManeuverInfo(
            distanceM = (linear(mDist, tSec) ?: Float.MAX_VALUE).coerceAtLeast(0f),
            type = runCatching { ManeuverType.valueOf(type) }.getOrDefault(ManeuverType.OTHER),
        )
        return VehicleSnapshot(
            timestampMs = timestampMs,
            speedKmh = keep(Signal.SPEED, linear(speed, tSec)?.coerceAtLeast(0f)),
            steeringAngleDeg = keep(Signal.STEERING, steer),
            longitudinalAccelMps2 = keep(Signal.ACCEL, linear(accel, tSec)),
            brakePressed = keep(Signal.BRAKE, step(brake, tSec)),
            turnSignalOn = keep(Signal.TURN_SIGNAL, step(turn, tSec)),
            gear = keep(Signal.GEAR, step(gear, tSec)),
            wiperLevel = keep(Signal.WIPER, step(wiper, tSec)),
            naviAvailable = Signal.NAVI !in dropped && mType.isNotEmpty(),
            maneuver = if (Signal.NAVI in dropped) null else maneuver,
        )
    }

    /** 구간 (fromSec, toSec]에 속한 이벤트. */
    fun eventsBetween(fromSec: Float, toSec: Float): List<ScenarioEvent> =
        scenario.events.filter { it.t > fromSec && it.t <= toSec }

    fun expectedStageAt(tSec: Float): Stage? =
        scenario.expect.firstOrNull { tSec >= it.from && tSec <= it.to }?.stage
}
