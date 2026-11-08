package app.teum.ux

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Build
import app.teum.vehiclesignal.AaosCarPropertySource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 주행 중 UX 제한 상태(CarUxRestrictionsManager).
 * @param requiresDistractionOptimization true면 주행 중: 텍스트 입력·스크롤 목록·긴 글을 보이지 않는다.
 */
data class UxState(
    val requiresDistractionOptimization: Boolean,
    val noKeyboard: Boolean,
    val limitContent: Boolean,
    val source: String,
) {
    /** 설정 편집 가능 여부(정차 중에만) */
    val canEdit: Boolean get() = !requiresDistractionOptimization && !noKeyboard

    companion object {
        val PARKED_UNKNOWN = UxState(false, false, false, "Car API 없음")
    }
}

object UxRestrictions {
    fun flow(context: Context): Flow<UxState> = callbackFlow {
        if (!AaosCarPropertySource.isAutomotive(context) || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            trySend(UxState.PARKED_UNKNOWN)
            awaitClose { }
            return@callbackFlow
        }
        var manager: CarUxRestrictionsManager? = null
        fun map(r: CarUxRestrictions?): UxState = if (r == null) UxState.PARKED_UNKNOWN else UxState(
            requiresDistractionOptimization = r.isRequiresDistractionOptimization,
            noKeyboard = r.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_NO_KEYBOARD != 0,
            limitContent = r.activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT != 0,
            source = "CarUxRestrictionsManager",
        )
        val car = try {
            Car.createCar(context.applicationContext, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                if (!ready) return@createCar
                manager = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                manager?.let { m ->
                    trySend(map(m.currentCarUxRestrictions))
                    m.registerListener { r -> trySend(map(r)) }
                }
            }
        } catch (t: Throwable) {
            trySend(UxState.PARKED_UNKNOWN)
            null
        }
        awaitClose {
            runCatching { manager?.unregisterListener() }
            runCatching { car?.disconnect() }
        }
    }.distinctUntilChanged()
}
