package app.teum.navisignal

import app.teum.core.dli.DliEngine
import app.teum.core.dli.Factor
import app.teum.core.dli.ManeuverInfo
import app.teum.core.dli.ManeuverType
import app.teum.core.dli.Signal
import app.teum.core.dli.VehicleSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class NaviMappingTest {
    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(
        "TURN_ENTER_IC_R,MERGE", "TURN_ENTER_IC_C,MERGE", "TURN_DEVIDE_MERGE,MERGE",
        "TURN_KEEP_L,DIVERGE", "TURN_ENTER_OVER_R,DIVERGE", "TURN_AVOID_UNDER_C,DIVERGE",
        "TURN_EXIT_IC_L,EXIT", "TURN_RESTAREA,EXIT", "TURN_ENTER_SERVICEAREA,EXIT",
        "TURN_STRAIGHT,STRAIGHT", "TURN_LEFT,OTHER", "TURN_UTURN,OTHER", "TURN_TOLL,OTHER", "TURN_UNKNOWN,OTHER",
    )
    fun turnTypeMapping(name: String, expected: ManeuverType) {
        assertEquals(expected, mapTurnType(name))
    }

    @Test
    fun unavailableNaviZeroesManeuverAndMarksMissing() {
        val snap = VehicleSnapshot(timestampMs = 0, speedKmh = 100f)
            .withNavi(NaviSignal(available = false, maneuver = ManeuverInfo(0f, ManeuverType.MERGE)))
        val st = DliEngine().process(snap)
        assertEquals(0f, st.contributions.getValue(Factor.MANEUVER))
        assertTrue(Signal.NAVI in st.missing)
    }

    @Test
    fun availableNaviContributes() {
        val snap = VehicleSnapshot(timestampMs = 0, speedKmh = 100f)
            .withNavi(NaviSignal(available = true, maneuver = ManeuverInfo(250f, ManeuverType.MERGE)))
        assertEquals(10f, DliEngine().process(snap).contributions.getValue(Factor.MANEUVER), 1e-3f)
    }
}
