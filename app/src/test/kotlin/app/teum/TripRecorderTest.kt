package app.teum

import app.teum.core.dli.DliEngine
import app.teum.core.dli.Gear
import app.teum.core.dli.Stage
import app.teum.core.dli.VehicleSnapshot
import app.teum.data.TripRecorder
import app.teum.notificationqueue.QueueStats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TripRecorderTest {
    @Test
    fun endsTripAfterParkingAndCountsStages() {
        val engine = DliEngine()
        val rec = TripRecorder { 0L }
        var ended = false
        var t = 0L
        fun feed(sec: Int, snap: (Long) -> VehicleSnapshot) {
            repeat(sec * 5) { ended = ended || rec.onLoadState(engine.process(snap(t))); t += 200 }
        }
        // 정차(P)만으로는 주행 끝이 아니다(한 번도 움직이지 않음)
        feed(5) { VehicleSnapshot(it, speedKmh = 0f, gear = Gear.PARK) }
        assertFalse(ended)
        feed(10) { VehicleSnapshot(it, speedKmh = 100f, gear = Gear.DRIVE, naviAvailable = true) }
        feed(4) { VehicleSnapshot(it, speedKmh = 0f, gear = Gear.PARK) }
        assertTrue(ended)
        val b = rec.finish(QueueStats(received = 3, held = 2), teumEnabled = true)
        assertEquals(18, b.durationSec)
        assertTrue(b.stageSec.getValue(Stage.GAP) > 10)
        assertEquals(2, b.held)
        assertTrue(b.headline.contains("알림 2건"))
    }
}
