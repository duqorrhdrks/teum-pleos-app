package app.teum.notificationqueue

import java.util.concurrent.atomic.AtomicLong

/** 틈이 조율하는 메시지 하나. 원문은 차량 안에서만 다루고 외부로 보내지 않는다. */
data class TeumMessage(
    val sender: String,
    val text: String,
    val receivedAtMs: Long,
    val source: Source,
    val kind: Kind = Kind.MESSAGE,
    /** 보낸 앱 이름(알림 수집·위임일 때) */
    val app: String? = null,
    val id: Long = seq.incrementAndGet(),
) {
    enum class Source(val label: String) {
        DEMO("데모"),
        SCENARIO("시나리오"),
        NOTIFICATION_LISTENER("알림 수집"),
        LOAD_AWARE_API("앱 위임"),
    }

    /** 실제 전화 제어는 범위 밖. CALL은 데모로 '전화 왔었음'만 표시한다. */
    enum class Kind { MESSAGE, CALL }

    private companion object { val seq = AtomicLong() }
}

/**
 * 긴급 규칙.
 * @param emergencyContacts 긴급 연락처(이름 일치)
 * @param keywords 본문에 들어 있으면 긴급
 * @param repeatCount 같은 보낸 사람이 [repeatWindowMs] 안에 이 횟수 이상 보내면 긴급(반복 수신)
 */
data class UrgencyRules(
    val emergencyContacts: Set<String> = emptySet(),
    val keywords: Set<String> = DEFAULT_KEYWORDS,
    val repeatCount: Int = 3,
    val repeatWindowMs: Long = 3 * 60_000L,
) {
    companion object {
        val DEFAULT_KEYWORDS = setOf("긴급", "응급", "사고", "병원", "급해", "119")
    }
}

data class Urgency(val urgent: Boolean, val reason: String? = null)

/** 메시지의 긴급 여부를 판단한다. 반복 수신 판정을 위해 최근 수신 기록을 들고 있다. */
class UrgencyClassifier {
    private val history = ArrayDeque<Pair<String, Long>>()

    fun classify(m: TeumMessage, rules: UrgencyRules): Urgency {
        history.addLast(m.sender to m.receivedAtMs)
        while (history.isNotEmpty() && m.receivedAtMs - history.first().second > rules.repeatWindowMs) history.removeFirst()

        if (m.sender in rules.emergencyContacts) return Urgency(true, "긴급 연락처")
        rules.keywords.firstOrNull { m.text.contains(it) }?.let { return Urgency(true, "키워드 '$it'") }
        val count = history.count { it.first == m.sender }
        if (count >= rules.repeatCount) return Urgency(true, "${rules.repeatWindowMs / 60_000}분 안에 ${count}회 수신")
        return Urgency(false)
    }
}

/** 전달 묶음. [spoken]은 음성으로 읽을 문장, [display]는 화면용 짧은 문구. */
data class Delivery(
    val messages: List<TeumMessage>,
    val urgent: Boolean,
    val batch: Boolean,
    val spoken: String,
    val display: String,
    val deliveredAtMs: Long,
)

/** 전달 문구 생성(한국어). */
object DeliveryText {
    fun single(m: TeumMessage, urgency: Urgency): String = when (m.kind) {
        TeumMessage.Kind.CALL -> "${m.sender}님에게서 전화가 왔었어요."
        TeumMessage.Kind.MESSAGE ->
            (if (urgency.urgent) "긴급 메시지예요. " else "") + "${m.sender}님 메시지: ${m.text}"
    }

    /** 틈 단계 일괄 요약: 보낸 사람별로 묶고 최신 내용을 읽는다. 세 명을 넘으면 나머지는 건수만 말한다. */
    fun batch(messages: List<TeumMessage>): String {
        val bySender = messages.groupBy { it.sender }
        val parts = bySender.entries.take(3).map { (sender, ms) ->
            val last = ms.last()
            val calls = ms.count { it.kind == TeumMessage.Kind.CALL }
            val texts = ms.filter { it.kind == TeumMessage.Kind.MESSAGE }
            buildString {
                append("${sender}님 ")
                if (texts.isNotEmpty()) append("메시지 ${texts.size}건")
                if (calls > 0) append(if (texts.isNotEmpty()) ", 전화 ${calls}통" else "전화 ${calls}통")
                if (last.kind == TeumMessage.Kind.MESSAGE) append(", ${last.text}") else if (texts.isNotEmpty()) append(", ${texts.last().text}")
            }
        }
        val rest = bySender.size - 3
        return buildString {
            append("기다려 둔 알림 ${messages.size}건이에요. ")
            append(parts.joinToString(". ") { it.trimEnd('.', ' ') })
            if (!endsWith(".")) append(".")
            if (rest > 0) append(" 그 밖에 ${rest}명에게서 온 알림이 있어요.")
        }
    }

    fun batchDisplay(messages: List<TeumMessage>): String =
        "알림 ${messages.size}건 · " + messages.map { it.sender }.distinct().take(3).joinToString(", ")
}
