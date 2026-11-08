package app.teum.notificationqueue

import app.teum.core.dli.LoadState
import app.teum.core.dli.Stage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.PriorityQueue

data class HeldMessage(val message: TeumMessage, val urgency: Urgency, val heldAtMs: Long)

/** 주행 후 브리핑용 집계. */
data class QueueStats(
    val received: Int = 0,
    val held: Int = 0,
    val deliveredImmediately: Int = 0,
    val urgentPassed: Int = 0,
    val batches: Int = 0,
    val longestHoldMs: Long = 0,
)

/**
 * 알림 보류·일괄 전달 큐.
 *
 * | 단계 | 일반 | 긴급 |
 * |---|---|---|
 * | 집중 | 보류 | 보류(집중을 벗어나는 즉시 가장 먼저) |
 * | 주의 | 보류 | 즉시 전달 |
 * | 틈 | 즉시 전달(보류분이 있으면 일괄 요약) | 즉시 전달 |
 * | 틈 OFF | 즉시 전달 | 즉시 전달 |
 *
 * 보류 큐는 우선순위 큐다(긴급 먼저, 그다음 받은 순서).
 */
class NotificationQueue(
    private val scope: CoroutineScope,
    private val loadStates: StateFlow<LoadState?>,
    private val teumEnabled: StateFlow<Boolean>,
    private val rules: StateFlow<UrgencyRules>,
    private val nowMs: () -> Long,
) {
    private val mutex = Mutex()
    private val classifier = UrgencyClassifier()
    private val heldQueue = PriorityQueue<HeldMessage>(
        compareByDescending<HeldMessage> { it.urgency.urgent }.thenBy { it.message.receivedAtMs }.thenBy { it.message.id },
    )

    private val _held = MutableStateFlow<List<HeldMessage>>(emptyList())
    val held: StateFlow<List<HeldMessage>> = _held.asStateFlow()

    private val _deliveries = MutableSharedFlow<Delivery>(replay = 16, extraBufferCapacity = 64)
    val deliveries: SharedFlow<Delivery> = _deliveries.asSharedFlow()

    private val _stats = MutableStateFlow(QueueStats())
    val stats: StateFlow<QueueStats> = _stats.asStateFlow()

    private data class Mode(val enabled: Boolean, val stage: Stage?)
    /** 현재 모드는 StateFlow 값을 직접 읽는다(수신 시점의 단계로 판정해야 하므로). */
    private val mode: Mode get() = Mode(teumEnabled.value, loadStates.value?.stage)

    init {
        scope.launch {
            combine(loadStates, teumEnabled) { st, en -> Mode(en, st?.stage) }
                .distinctUntilChanged()
                .collect { flushIfAllowed() }
        }
    }

    fun submit(m: TeumMessage) {
        scope.launch { handle(m) }
    }

    private suspend fun handle(m: TeumMessage) = mutex.withLock {
        val urgency = classifier.classify(m, rules.value)
        val md = mode
        _stats.value = _stats.value.let { it.copy(received = it.received + 1) }
        val passNow = when {
            !md.enabled -> true
            md.stage == Stage.FOCUS -> false
            md.stage == Stage.CAUTION -> urgency.urgent
            md.stage == Stage.GAP -> heldQueue.isEmpty() // 보류분이 있으면 순서를 지켜 함께 묶는다
            else -> urgency.urgent // 부하 상태를 아직 모르면 일반은 보류
        }
        if (passNow) {
            deliverSingle(m, urgency)
        } else {
            heldQueue += HeldMessage(m, urgency, nowMs())
            _stats.value = _stats.value.let { it.copy(held = it.held + 1) }
            publishHeld()
            if (md.stage == Stage.GAP) flushLocked()
        }
    }

    private suspend fun flushIfAllowed() = mutex.withLock { flushLocked() }

    private suspend fun flushLocked() {
        val md = mode
        if (heldQueue.isEmpty()) return
        when {
            !md.enabled || md.stage == Stage.GAP -> {
                // 긴급분은 따로 먼저, 나머지는 일괄 요약
                val all = generateSequence { heldQueue.poll() }.toList()
                val (urgentOnes, normalOnes) = all.partition { it.urgency.urgent }
                urgentOnes.forEach { deliverSingle(it.message, it.urgency, it.heldAtMs) }
                if (normalOnes.size == 1) {
                    deliverSingle(normalOnes[0].message, normalOnes[0].urgency, normalOnes[0].heldAtMs)
                } else if (normalOnes.isNotEmpty()) {
                    deliverBatch(normalOnes)
                }
            }
            md.stage == Stage.CAUTION -> {
                // 주의: 긴급만 꺼낸다
                val urgentOnes = heldQueue.filter { it.urgency.urgent }.sortedBy { it.message.receivedAtMs }
                urgentOnes.forEach { heldQueue.remove(it); deliverSingle(it.message, it.urgency, it.heldAtMs) }
            }
            else -> Unit
        }
        publishHeld()
    }

    private fun publishHeld() {
        _held.value = heldQueue.toList().sortedWith(heldQueue.comparator())
    }

    private suspend fun deliverSingle(m: TeumMessage, u: Urgency, heldAt: Long? = null) {
        val now = nowMs()
        _stats.value = _stats.value.let {
            it.copy(
                deliveredImmediately = it.deliveredImmediately + if (heldAt == null) 1 else 0,
                urgentPassed = it.urgentPassed + if (u.urgent) 1 else 0,
                longestHoldMs = maxOf(it.longestHoldMs, heldAt?.let { h -> now - h } ?: 0),
            )
        }
        _deliveries.emit(
            Delivery(
                messages = listOf(m), urgent = u.urgent, batch = false,
                spoken = DeliveryText.single(m, u),
                display = "${m.sender}: ${m.text}".take(40),
                deliveredAtMs = now,
            ),
        )
    }

    private suspend fun deliverBatch(items: List<HeldMessage>) {
        val now = nowMs()
        val msgs = items.map { it.message }
        _stats.value = _stats.value.let {
            it.copy(batches = it.batches + 1, longestHoldMs = maxOf(it.longestHoldMs, items.maxOf { h -> now - h.heldAtMs }))
        }
        _deliveries.emit(
            Delivery(
                messages = msgs, urgent = false, batch = true,
                spoken = DeliveryText.batch(msgs),
                display = DeliveryText.batchDisplay(msgs),
                deliveredAtMs = now,
            ),
        )
    }

    fun resetStats() {
        _stats.value = QueueStats()
    }
}
