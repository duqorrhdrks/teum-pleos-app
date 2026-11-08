package app.teum.notificationqueue

import app.teum.core.dli.Stage
import app.teum.core.dli.toLoadStates
import app.teum.vehiclesignal.ScenarioEvent
import app.teum.vehiclesignal.ScenarioParser
import app.teum.vehiclesignal.ScenarioReplaySource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioQueueTest {
    @Test
    @DisplayName("고속도로 시나리오: 집중 중 보류 → 이탈 후 긴급(엄마) 먼저, 박대리 3번째는 반복 수신 긴급")
    fun highwayNotifications() = runTest {
        val scenario = ScenarioParser.parse(requireNotNull(javaClass.getResource("/highway_demo.json")).readText())
        val src = ScenarioReplaySource(scenario, nowMs = { testScheduler.currentTime })
        val states = src.snapshots().toLoadStates(nowMs = { testScheduler.currentTime })
            .stateIn(backgroundScope, SharingStarted.Eagerly, null)
        val q = NotificationQueue(
            backgroundScope, states, MutableStateFlow(true),
            MutableStateFlow(UrgencyRules(emergencyContacts = setOf("엄마"))),
        ) { testScheduler.currentTime }
        val demo = DemoMessageSource { testScheduler.currentTime }
        val stageAtDelivery = mutableListOf<Pair<Delivery, Stage?>>()
        backgroundScope.launch {
            src.events.collect { e ->
                if (e.kind != ScenarioEvent.Kind.SPEECH) q.submit(demo.message(e.sender!!, e.text, e.kind == ScenarioEvent.Kind.CALL))
            }
        }
        backgroundScope.launch { q.deliveries.collect { stageAtDelivery += it to states.value?.stage } }

        advanceTimeBy(100_000)

        val d = stageAtDelivery.map { it.first }
        // 집중 구간(39.4~44초)에 들어온 메시지는 집중을 벗어날 때까지 나가지 않는다
        val first = d.first()
        assertTrue(first.deliveredAtMs in 43_000..45_000, "첫 전달 ${first.deliveredAtMs}")
        assertTrue(first.urgent && first.messages.single().sender == "엄마", "집중 이탈 뒤 긴급 연락처가 먼저: $first")
        assertTrue(d[1].urgent && d[1].messages.single().kind == TeumMessage.Kind.CALL, "엄마 전화(긴급)")
        assertTrue(stageAtDelivery.none { it.second == Stage.FOCUS }, "집중 단계에서는 아무것도 전하지 않는다")
        val batch = d.first { it.batch }
        assertEquals(setOf("김팀장", "택배"), batch.messages.map { it.sender }.toSet())
        // 박대리 세 번째(64초)는 반복 수신으로 긴급
        val park = d.filter { it.messages.any { m -> m.sender == "박대리" } }
        assertTrue(park.any { it.urgent && it.messages.single().text.startsWith("지금 통화") }, park.toString())
        assertTrue(q.held.value.isEmpty())
    }
}
