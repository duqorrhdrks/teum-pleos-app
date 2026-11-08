package app.teum.vehiclesignal

import app.teum.core.dli.DliSpec
import app.teum.core.dli.VehicleSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * JSON 시나리오를 실시간으로 재생한다(에뮬레이터 시연과 테스트용).
 * 200ms마다 스냅샷을 내보내고, 그 사이에 든 시나리오 이벤트(음성·메시지·전화)를 [events]로 흘린다.
 *
 * @param nowMs 단조 시각(앱: SystemClock.elapsedRealtime, 테스트: 가상 시간)
 * @param loop 끝나면 처음부터 다시 재생
 */
class ScenarioReplaySource(
    val scenario: Scenario,
    private val nowMs: () -> Long,
    private val loop: Boolean = false,
    private val speed: Float = 1f,
) : VehicleSignalSource {
    override val id: String = "scenario:${scenario.name}"

    private val timeline = ScenarioTimeline(scenario)
    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.Connected(setOf("시나리오 전체")))
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()

    private val _events = MutableSharedFlow<ScenarioEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ScenarioEvent> = _events.asSharedFlow()

    private val _position = MutableStateFlow(0f)
    /** 현재 재생 위치(초). 시연 화면의 진행 막대와 '기대 단계' 표시에 쓴다. */
    val positionSec: StateFlow<Float> = _position.asStateFlow()

    fun expectedStageAt(tSec: Float) = timeline.expectedStageAt(tSec)

    override fun snapshots(): Flow<VehicleSnapshot> = flow {
        do {
            val start = nowMs()
            var prevT = -1f
            while (true) {
                val t = (nowMs() - start) / 1000f * speed
                if (t > scenario.durationSec) break
                timeline.eventsBetween(prevT, t).forEach { _events.emit(it) }
                prevT = t
                _position.value = t
                emit(timeline.snapshotAt(t, nowMs()))
                delay(DliSpec.PERIOD_MS)
            }
        } while (loop)
        _status.value = SourceStatus.Unavailable("시나리오 재생 끝")
    }
}
