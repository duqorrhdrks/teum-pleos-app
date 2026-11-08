package app.teum.notificationqueue

import app.teum.core.dli.LoadState
import app.teum.core.dli.Stage
import app.teum.core.dli.VehicleSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

private fun st(stage: Stage) = LoadState(
    timestampMs = 0, dliRaw = 0f, dli = 0f, stage = stage, stageSinceMs = 0, contributions = emptyMap(),
    missing = emptySet(), maneuverEtaSec = null, preHold = false, snapshot = VehicleSnapshot(0),
)

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationQueueTest {
    private fun TestScope.queue(
        load: MutableStateFlow<LoadState?>,
        enabled: Boolean = true,
        rules: UrgencyRules = UrgencyRules(emergencyContacts = setOf("엄마")),
    ) = NotificationQueue(backgroundScope, load, MutableStateFlow(enabled), MutableStateFlow(rules)) { testScheduler.currentTime }

    private fun msg(sender: String, text: String, at: Long, call: Boolean = false) = TeumMessage(
        sender, text, at, TeumMessage.Source.DEMO, if (call) TeumMessage.Kind.CALL else TeumMessage.Kind.MESSAGE,
    )

    @Test
    fun urgencyRules() {
        val c = UrgencyClassifier()
        val r = UrgencyRules(emergencyContacts = setOf("엄마"))
        assertEquals("긴급 연락처", c.classify(msg("엄마", "밥 먹었어?", 0), r).reason)
        assertTrue(c.classify(msg("친구", "사고 났어", 1), r).urgent)
        assertFalse(c.classify(msg("박대리", "견적서", 1_000), r).urgent)
        assertFalse(c.classify(msg("박대리", "확인?", 60_000), r).urgent)
        assertTrue(c.classify(msg("박대리", "통화?", 120_000), r).urgent) // 3분 안에 3회
        assertFalse(c.classify(msg("박대리", "다음날", 400_000), r).urgent) // 창 밖으로 밀림
    }

    @Test
    @DisplayName("집중: 모두 보류 → 틈: 긴급 먼저 단건, 나머지 일괄 요약")
    fun focusHoldsThenGapBatches() = runTest {
        val load = MutableStateFlow<LoadState?>(st(Stage.FOCUS))
        val q = queue(load)
        q.submit(msg("김팀장", "내일 회의 자료 확인 부탁드려요.", 1))
        q.submit(msg("엄마", "도착하면 연락 줘.", 2))
        q.submit(msg("택배", "배송 완료", 3))
        runCurrent()
        assertEquals(3, q.held.value.size)
        assertEquals("엄마", q.held.value.first().message.sender, "우선순위 큐: 긴급 먼저")
        assertTrue(q.deliveries.replayCache.isEmpty())

        load.value = st(Stage.GAP)
        runCurrent()
        val d = q.deliveries.replayCache
        assertEquals(2, d.size)
        assertTrue(d[0].urgent && !d[0].batch && d[0].spoken.contains("엄마"))
        assertTrue(d[1].batch)
        assertEquals("기다려 둔 알림 2건이에요. 김팀장님 메시지 1건, 내일 회의 자료 확인 부탁드려요. 택배님 메시지 1건, 배송 완료.", d[1].spoken)
        assertTrue(q.held.value.isEmpty())
    }

    @Test
    @DisplayName("주의: 긴급은 즉시, 일반은 보류")
    fun cautionPassesUrgentOnly() = runTest {
        val load = MutableStateFlow<LoadState?>(st(Stage.CAUTION))
        val q = queue(load)
        q.submit(msg("민지", "주말에 시간 돼?", 1))
        q.submit(msg("엄마", "어디야?", 2))
        runCurrent()
        assertEquals(listOf("엄마"), q.deliveries.replayCache.map { it.messages.single().sender })
        assertEquals(1, q.held.value.size)
    }

    @Test
    @DisplayName("집중 중 긴급은 보류됐다가 주의로 내려오면 즉시 나간다")
    fun urgentHeldInFocusReleasedInCaution() = runTest {
        val load = MutableStateFlow<LoadState?>(st(Stage.FOCUS))
        val q = queue(load)
        q.submit(msg("엄마", "전화", 1, call = true))
        q.submit(msg("민지", "주말?", 2))
        runCurrent()
        load.value = st(Stage.CAUTION)
        runCurrent()
        assertEquals("엄마님에게서 전화가 왔었어요.", q.deliveries.replayCache.single().spoken)
        assertEquals(1, q.held.value.size)
    }

    @Test
    @DisplayName("틈 단계에서 새 메시지는 바로 단건 전달")
    fun gapDeliversImmediately() = runTest {
        val q = queue(MutableStateFlow(st(Stage.GAP)))
        q.submit(msg("민지", "주말?", 1))
        runCurrent()
        assertEquals("민지님 메시지: 주말?", q.deliveries.replayCache.single().spoken)
        assertEquals(1, q.stats.value.deliveredImmediately)
    }

    @Test
    @DisplayName("틈 OFF: 집중 단계에서도 모두 즉시 통과")
    fun teumOff() = runTest {
        val q = queue(MutableStateFlow(st(Stage.FOCUS)), enabled = false)
        q.submit(msg("민지", "a", 1)); q.submit(msg("택배", "b", 2))
        runCurrent()
        assertEquals(2, q.deliveries.replayCache.size)
        assertTrue(q.held.value.isEmpty())
    }

    @Test
    fun batchTextLimitsSenders() {
        val text = DeliveryText.batch(
            listOf("a", "b", "c", "d", "e").mapIndexed { i, s -> msg(s, "내용$i", i.toLong()) } + msg("a", "전화", 9, call = true),
        )
        assertTrue(text.startsWith("기다려 둔 알림 6건이에요."))
        assertTrue(text.contains("a님 메시지 1건, 전화 1통, 내용0"))
        assertTrue(text.endsWith("그 밖에 2명에게서 온 알림이 있어요."))
    }
}
