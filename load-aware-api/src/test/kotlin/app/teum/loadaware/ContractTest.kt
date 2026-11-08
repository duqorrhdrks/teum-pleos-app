package app.teum.loadaware

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContractTest {
    @Test
    fun rateLimiterPerUidPerMinute() {
        var now = 0L
        val l = SubmitRateLimiter(maxPerMinute = 3) { now }
        repeat(3) { assertTrue(l.tryAcquire(1)) }
        assertFalse(l.tryAcquire(1))
        assertTrue(l.tryAcquire(2), "다른 앱은 별도 한도")
        now = 60_001
        assertTrue(l.tryAcquire(1), "1분이 지나면 다시 허용")
    }

    @Test
    fun sanitize() {
        assertEquals("배달앱" to "음식이 도착했어요", sanitizeSubmission(" 배달앱 ", "음식이\n도착했어요".replace("\n", " ")))
        assertEquals("a" to "b c", sanitizeSubmission("a", "b\u0007c"))
        assertNull(sanitizeSubmission("", "x"))
        assertNull(sanitizeSubmission("a", "x".repeat(LoadAwareContract.MAX_TEXT_LENGTH + 1)))
        assertEquals(40, sanitizeSubmission("가".repeat(60), "x")!!.first.length)
    }
}
