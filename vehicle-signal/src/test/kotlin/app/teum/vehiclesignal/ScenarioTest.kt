package app.teum.vehiclesignal

import app.cash.turbine.test
import app.teum.core.dli.DliEngine
import app.teum.core.dli.DliSpec
import app.teum.core.dli.Gear
import app.teum.core.dli.ManeuverType
import app.teum.core.dli.Signal
import app.teum.core.dli.Stage
import app.teum.core.dli.VehicleSnapshot
import app.teum.core.dli.toLoadStates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

fun loadHighwayScenario(): Scenario {
    val text = requireNotNull(ScenarioTest::class.java.getResource("/highway_demo.json")) {
        "scenarios/highway_demo.json을 테스트 리소스에서 찾지 못함"
    }.readText()
    return ScenarioParser.parse(text)
}

/** 시나리오를 5Hz로 끝까지 돌려 (t초, 단계, DLI) 목록을 만든다. */
fun runScenario(s: Scenario) = run {
    val tl = ScenarioTimeline(s)
    val engine = DliEngine()
    val n = (s.durationSec * 1000 / DliSpec.PERIOD_MS).toInt()
    (0..n).map { i ->
        val ms = i * DliSpec.PERIOD_MS
        val st = engine.process(tl.snapshotAt(ms / 1000f, ms))
        Triple(ms / 1000f, st.stage, st)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioTest {

    @Test
    @DisplayName("고속도로 시나리오: 기대 구간마다 기대 단계가 나온다")
    fun highwayExpectations() {
        val s = loadHighwayScenario()
        val run = runScenario(s)
        val mismatches = run.filter { (t, stage, _) ->
            val exp = ScenarioTimeline(s).expectedStageAt(t)
            exp != null && exp != stage
        }
        assertTrue(
            mismatches.isEmpty(),
            mismatches.joinToString("\n") { (t, st, ls) -> "t=$t 기대=${ScenarioTimeline(s).expectedStageAt(t)} 실제=$st dli=${"%.1f".format(ls.dli)}" },
        )
    }

    @Test
    @DisplayName("합류 접근 시 ETA 15초 이내에서 사전 보류가 켜진다")
    fun preHoldBeforeMerge() {
        val run = runScenario(loadHighwayScenario())
        val firstHold = run.first { it.third.preHold }
        assertTrue(firstHold.first in 26f..29f, "사전 보류 시작 t=${firstHold.first}")
        assertFalse(run.first { it.first == 20f }.third.preHold)
    }

    @Test
    @DisplayName("보간: 숫자는 선형, 불·열거는 계단, 정의 전 필드는 신호 없음")
    fun interpolation() {
        val s = Scenario(
            name = "t", durationSec = 10f,
            keyframes = listOf(
                Keyframe(t = 0f, speedKmh = 0f, gear = Gear.PARK),
                Keyframe(t = 2f, wiper = 1),
                Keyframe(t = 4f, speedKmh = 100f, gear = Gear.DRIVE, maneuverType = "EXIT", maneuverDistanceM = 400f),
                Keyframe(t = 8f, maneuverDistanceM = 0f),
            ),
        )
        val tl = ScenarioTimeline(s)
        val a = tl.snapshotAt(2f, 0)
        assertEquals(50f, a.speedKmh!!, 1e-3f)
        assertEquals(Gear.PARK, a.gear)
        assertEquals(1, a.wiperLevel)
        assertNull(tl.snapshotAt(1f, 0).wiperLevel)
        assertNull(a.brakePressed)

        val b = tl.snapshotAt(6f, 0)
        assertEquals(Gear.DRIVE, b.gear)
        assertEquals(ManeuverType.EXIT, b.maneuver!!.type)
        assertEquals(200f, b.maneuver!!.distanceM, 1e-3f)
        assertTrue(b.naviAvailable)
    }

    @Test
    @DisplayName("dropSignals로 신호를 끊으면 해당 필드가 null(신호 없음)")
    fun dropSignals() {
        val s = Scenario(
            name = "t", durationSec = 10f,
            keyframes = listOf(
                Keyframe(t = 0f, speedKmh = 50f, wiper = 1, maneuverType = "NONE"),
                Keyframe(t = 5f, dropSignals = listOf(Signal.WIPER, Signal.NAVI)),
            ),
        )
        val snap = ScenarioTimeline(s).snapshotAt(6f, 0)
        assertNull(snap.wiperLevel)
        assertFalse(snap.naviAvailable)
        assertEquals(50f, snap.speedKmh)
        val st = DliEngine().process(snap)
        assertTrue(Signal.WIPER in st.missing && Signal.NAVI in st.missing)
    }

    @Test
    @DisplayName("ScenarioReplaySource: 200ms마다 내보내고 시나리오 이벤트를 시각에 맞춰 흘린다")
    fun replaySourceEmitsEvents() = runTest {
        val src = ScenarioReplaySource(loadHighwayScenario(), nowMs = { testScheduler.currentTime })
        val collected = mutableListOf<ScenarioEvent>()
        val job = backgroundScope.launch(Dispatchers.Unconfined) { src.events.collect { collected += it } }
        src.snapshots().test {
            val first = awaitItem()
            val second = awaitItem()
            assertEquals(200L, second.timestampMs - first.timestampMs)
            repeat(70) { awaitItem() } // 14.4초까지
            cancelAndIgnoreRemainingEvents()
        }
        job.cancel()
        assertEquals(listOf(12f), collected.map { it.t })
        assertEquals(ScenarioEvent.Kind.SPEECH, collected.single().kind)
    }

    @Test
    @DisplayName("ScenarioReplaySource + toLoadStates: 실시간 재생으로도 합류 구간에서 집중")
    fun replayDrivesEngine() = runTest {
        val src = ScenarioReplaySource(loadHighwayScenario(), nowMs = { testScheduler.currentTime })
        src.snapshots().toLoadStates(nowMs = { testScheduler.currentTime }).test {
            var focusSeenAt: Long? = null
            while (focusSeenAt == null) {
                val st = awaitItem()
                if (st.stage == Stage.FOCUS) focusSeenAt = st.timestampMs
                if (st.timestampMs > 50_000) break
            }
            assertTrue(focusSeenAt != null && focusSeenAt in 36_000L..41_000L, "focus at $focusSeenAt")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    @DisplayName("합성 신호원: 표준 우선, 운전대 각은 Pleos 우선, 표준에 없는 신호는 Pleos")
    fun compositeMerge() {
        val aaos = VehicleSnapshot(timestampMs = 10, speedKmh = 80f, gear = Gear.DRIVE, steeringAngleDeg = 30f, turnSignalOn = null)
        val pleos = VehicleSnapshot(timestampMs = 12, gear = Gear.PARK, steeringAngleDeg = 45f, turnSignalOn = true, brakePressed = true, wiperLevel = 2)
        val m = CompositeVehicleSource.merge(aaos, pleos)
        assertEquals(80f, m.speedKmh)
        assertEquals(Gear.DRIVE, m.gear)
        assertEquals(45f, m.steeringAngleDeg)
        assertEquals(true, m.turnSignalOn)
        assertEquals(true, m.brakePressed)
        assertEquals(2, m.wiperLevel)
        assertEquals(12L, m.timestampMs)
        // Pleos 운전대 각이 없으면 표준 환산값
        assertEquals(30f, CompositeVehicleSource.merge(aaos, pleos.copy(steeringAngleDeg = null)).steeringAngleDeg)
    }

    @Test
    @DisplayName("Pleos 신호원: 등록에 성공한 켜짐/꺼짐 신호는 첫 값 전까지 꺼짐, 등록 실패한 신호는 신호 없음")
    fun pleosRegisteredDefaults() {
        val none = VehicleSnapshot(0)
        val all = PleosVehicleSource.DEFAULTED_SIGNALS
        val d = PleosVehicleSource.withRegisteredDefaults(none, all)
        assertEquals(false, d.turnSignalOn)
        assertEquals(false, d.brakePressed)
        assertEquals(0, d.wiperLevel)
        // 등록 실패(권한 없음 등)는 그대로 '신호 없음'
        val onlyBrake = PleosVehicleSource.withRegisteredDefaults(none, setOf(PleosVehicleSource.BRAKE))
        assertNull(onlyBrake.turnSignalOn)
        assertNull(onlyBrake.wiperLevel)
        // 이미 받은 값은 덮어쓰지 않는다
        val on = PleosVehicleSource.withRegisteredDefaults(none.copy(turnSignalOn = true, wiperLevel = 3), all)
        assertEquals(true, on.turnSignalOn)
        assertEquals(3, on.wiperLevel)
    }
}

