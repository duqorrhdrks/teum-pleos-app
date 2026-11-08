package app.teum.loadaware

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 연동 앱용 클라이언트. 틈에 바인드해 부하 상태를 구독하고 알림 전달을 위임한다.
 *
 * 연동 앱 매니페스트에 필요한 것:
 * ```xml
 * <uses-permission android:name="app.teum.permission.READ_LOAD_STATE" />
 * <uses-permission android:name="app.teum.permission.SUBMIT_MESSAGE" />  <!-- 위임할 때만, 런타임 요청 -->
 * <queries><package android:name="app.teum" /></queries>
 * ```
 */
class TeumLoadClient(context: Context) {
    private val appContext = context.applicationContext
    private var service: ITeumLoadService? = null

    private val _state = MutableStateFlow<PublicLoadState?>(null)
    val state: StateFlow<PublicLoadState?> = _state.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val listener = object : ILoadStateListener.Stub() {
        override fun onLoadStateChanged(state: Bundle?) {
            _state.value = PublicLoadState.fromBundle(state)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = ITeumLoadService.Stub.asInterface(binder)
            service = s
            _connected.value = true
            runCatching {
                _state.value = PublicLoadState.fromBundle(s.loadState)
                s.registerListener(listener)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _connected.value = false
        }
    }

    /** @return 바인드 요청이 받아들여졌는지(틈 미설치·권한 없음이면 false) */
    fun connect(): Boolean = try {
        appContext.bindService(
            Intent(LoadAwareContract.ACTION_BIND).setPackage(LoadAwareContract.TEUM_PACKAGE),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    } catch (e: SecurityException) {
        false
    }

    fun disconnect() {
        runCatching { service?.unregisterListener(listener) }
        runCatching { appContext.unbindService(connection) }
        service = null
        _connected.value = false
    }

    /** @return LoadAwareContract.SUBMIT_* 코드, 연결 전이면 null */
    fun submitMessage(sender: String, text: String, isCall: Boolean = false): Int? = try {
        service?.submitMessage(sender, text, isCall)
    } catch (e: RemoteException) {
        null
    }
}
