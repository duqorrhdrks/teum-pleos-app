package app.teum.navisignal

import app.teum.core.dli.ManeuverInfo
import app.teum.core.dli.ManeuverType
import app.teum.core.dli.VehicleSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 내비 신호.
 * @param available 내비 신호원이 동작하는지. false면 DLI 기동 항목은 0, 디버그 화면은 '신호 없음'.
 * @param maneuver 다음 기동. 경로 안내 중이 아니거나 다음 기동이 없으면 null.
 */
data class NaviSignal(
    val available: Boolean,
    val maneuver: ManeuverInfo? = null,
    val detail: String = "",
) {
    companion object {
        val UNAVAILABLE = NaviSignal(available = false, detail = "내비 미지원")
    }
}

interface NaviSignalSource {
    val id: String
    val state: StateFlow<NaviSignal>
    fun signals(): Flow<NaviSignal>
}

/** 차량 스냅샷에 내비 신호를 덧붙인다. 실패·미지원이면 maneuver = null, naviAvailable = false. */
fun VehicleSnapshot.withNavi(navi: NaviSignal?): VehicleSnapshot =
    copy(naviAvailable = navi?.available == true, maneuver = if (navi?.available == true) navi.maneuver else null)

/**
 * Pleos NaviHelper TurnType 이름 → 틈 기동 유형. 순수 함수라 문자열로 받는다(단위 테스트용).
 *
 * - 고속도로·IC 진입(TURN_ENTER_IC_*) → 합류
 * - TURN_DEVIDE_MERGE(분기·합류) → 합류 (복잡도가 큰 쪽으로 보수적으로)
 * - 갈림길 유지(TURN_KEEP_*), 지하·고가 진입/회피(TURN_ENTER_UNDER/OVER, TURN_AVOID_*) → 분기
 * - IC 진출, 휴게소·졸음쉼터 진입 → 출구
 * - 직진 → 직진
 * - 교차로 좌·우회전, 유턴, 회전교차로, 요금소, 터널, 도착 등 → 기타(사양상 복잡도 0)
 *
 * '차로 감소'는 TurnType에 없다. NaviHelper에서는 얻을 수 없으므로 그 복잡도(0.8)는 시나리오에서만 나온다.
 * TODO(verify-sdk): TURN_DEVIDE_MERGE의 정확한 의미(분기인지 합류인지)를 문서에서 확인하지 못했다.
 */
fun mapTurnType(name: String): ManeuverType = when {
    name.startsWith("TURN_ENTER_IC_") -> ManeuverType.MERGE
    name == "TURN_DEVIDE_MERGE" -> ManeuverType.MERGE
    name.startsWith("TURN_KEEP_") -> ManeuverType.DIVERGE
    name.startsWith("TURN_ENTER_UNDER_") || name.startsWith("TURN_ENTER_OVER_") ||
        name.startsWith("TURN_AVOID_") -> ManeuverType.DIVERGE
    name.startsWith("TURN_EXIT_IC_") || name == "TURN_ENTER_SERVICEAREA" || name == "TURN_RESTAREA" ->
        ManeuverType.EXIT
    name == "TURN_STRAIGHT" -> ManeuverType.STRAIGHT
    else -> ManeuverType.OTHER
}

/**
 * 멈춘 내비 값 거르기. 마지막으로 거리가 바뀐 뒤 차가 '남은 거리 + [marginM]'보다 더 달렸는데도
 * 내비가 같은 거리를 계속 주면, 이미 지나쳤거나 위치가 갱신되지 않는 값이므로 기동 정보를 버린다(null).
 *
 * 예: Pleos 에뮬레이터는 차속을 넣어도 지도 위치가 움직이지 않아 '좌회전 4m'가 계속 온다(2026-11-07 확인).
 * 그대로 쓰면 도달 예상 0초로 계산돼 긴 음성이 영원히 사전 보류된다. 실차의 GPS 수신 불량에도 같은 보호가 된다.
 */
class StaleManeuverFilter(private val marginM: Float = 50f) {
    private var last: ManeuverInfo? = null
    private var lastTs: Long? = null
    private var traveledM = 0f

    fun apply(s: VehicleSnapshot): VehicleSnapshot {
        // 신호가 한동안 끊겼다가 다시 오면 그 사이를 1초로만 친다(과대 추정 방지)
        val dtMs = lastTs?.let { (s.timestampMs - it).coerceIn(0L, 1_000L) } ?: 0L
        lastTs = s.timestampMs
        val m = s.maneuver
        if (m == null) {
            last = null
            traveledM = 0f
            return s
        }
        if (m.distanceM != last?.distanceM || m.type != last?.type) {
            last = m
            traveledM = 0f
            return s
        }
        traveledM += (s.speedKmh ?: 0f) / 3.6f * dtMs / 1000f
        return if (traveledM > m.distanceM + marginM) s.copy(maneuver = null) else s
    }
}
