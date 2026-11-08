package app.teum.navisignal

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import app.teum.core.dli.ManeuverInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pleos NaviHelper SDK(ai.pleos.playground:NaviHelper:3.0.5)에서 다음 기동 지점을 받는다.
 *
 * 확인한 API(공식 샘플 pleos-sample-navi-app, 공식 문서, 에뮬레이터 실행):
 * - NaviHelper(context).initialize(onSuccess, onFailure), addListener / removeListener, release()
 * - getTBTInfo() → NaviHelperEventListener.onTBTInfo(List<TBTInfo>)
 * - TBTInfo(type: TurnType, distance: Double, description: String)
 * - onNavigationStatus(NavigationStatus: INACTIVE, INITIALIZING, FREE_DRIVE, ROUTE_DRIVE)
 *
 * 도달 예상 시간은 기동별로 주지 않으므로 null로 두고 core-dli가 거리/차속으로 추정한다.
 * 실패하거나 미지원이면 [NaviSignal.UNAVAILABLE]을 내보내 DLI 기동 항목을 0으로 만든다.
 */
class PleosNaviHelperSource(context: Context) : NaviSignalSource {
    override val id: String = "pleos-navihelper"
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(NaviSignal(available = false, detail = "연결 중"))
    override val state: StateFlow<NaviSignal> = _state.asStateFlow()

    override fun signals(): Flow<NaviSignal> = callbackFlow {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var status: NavigationStatus? = null
        fun emit(s: NaviSignal) {
            _state.value = s
            trySend(s)
        }

        val helper = try {
            NaviHelper(appContext)
        } catch (t: Throwable) {
            emit(NaviSignal.UNAVAILABLE.copy(detail = "NaviHelper 생성 실패: ${t.message}"))
            awaitClose { scope.cancel() }
            return@callbackFlow
        }

        val listener = object : NaviHelperEventListener {
            override fun onNavigationStatus(navigationStatus: NavigationStatus) {
                status = navigationStatus
                if (navigationStatus != NavigationStatus.ROUTE_DRIVE) {
                    emit(NaviSignal(available = true, maneuver = null, detail = "경로 안내 없음($navigationStatus)"))
                }
            }

            override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
                val next = tbtInfoList.firstOrNull()
                emit(
                    NaviSignal(
                        available = true,
                        maneuver = next?.let {
                            // TODO(verify-sdk): TBTInfo.distance 단위를 문서에서 확인하지 못했다. 미터로 가정.
                            ManeuverInfo(distanceM = it.distance.toFloat(), type = mapTurnType(it.type.name))
                        },
                        detail = next?.let { "${it.type.name} ${it.distance.toInt()}m ${it.description}" } ?: "다음 기동 없음",
                    ),
                )
            }

            override fun onRouteEnded() = emit(NaviSignal(available = true, detail = "경로 종료"))
            override fun onRouteCancelled() = emit(NaviSignal(available = true, detail = "경로 취소"))

            override fun onError(errorCode: NaviErrorCode) {
                Log.w(TAG, "NaviHelper error $errorCode")
            }
        }

        helper.initialize(
            {
                helper.addListener(listener)
                emit(NaviSignal(available = true, detail = "연결됨"))
                // TODO(verify-sdk): onTBTInfo가 안내 중 자동으로 계속 오는지 문서로 확인하지 못했다.
                // 확인 전까지 1초마다 getTBTInfo()로 요청한다(성능 가이드: 폴링 최소화 → 경로 안내 중에만).
                scope.launch {
                    while (isActive) {
                        if (status == null || status == NavigationStatus.ROUTE_DRIVE) {
                            runCatching { helper.getTBTInfo() }
                                .onFailure { Log.w(TAG, "getTBTInfo 실패", it) }
                        }
                        delay(POLL_MS)
                    }
                }
            },
            { e ->
                emit(NaviSignal.UNAVAILABLE.copy(detail = "NaviHelper 초기화 실패: ${e.message}"))
            },
        )

        awaitClose {
            scope.cancel()
            runCatching { helper.removeListener(listener) }
            runCatching { helper.release() }
        }
    }

    companion object {
        private const val TAG = "PleosNaviHelperSource"
        private const val POLL_MS = 1_000L
    }
}

/** 내비가 없는 환경(일반 에뮬레이터, 단위 테스트)용. */
class NoNaviSource : NaviSignalSource {
    override val id = "none"
    private val _state = MutableStateFlow(NaviSignal.UNAVAILABLE)
    override val state: StateFlow<NaviSignal> = _state.asStateFlow()
    override fun signals(): Flow<NaviSignal> = _state
}
