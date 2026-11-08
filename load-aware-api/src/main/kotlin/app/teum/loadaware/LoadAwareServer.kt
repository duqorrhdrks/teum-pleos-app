package app.teum.loadaware

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * 부하 인지 API 바운드 서비스의 공통 구현. 앱 모듈이 상속해 [loadState]와 [onSubmit]를 연결한다.
 * 매니페스트 선언(권한·intent-filter)도 앱 모듈에 있다.
 */
abstract class LoadAwareServiceBase : Service() {
    protected abstract val loadState: StateFlow<PublicLoadState?>

    /** 검증을 통과한 위임 메시지. 호출 앱 이름을 함께 넘긴다. */
    protected abstract fun onSubmit(appLabel: String, sender: String, text: String, isCall: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listeners = RemoteCallbackList<ILoadStateListener>()
    private val limiter = SubmitRateLimiter(nowMs = SystemClock::elapsedRealtime)

    private val binder = object : ITeumLoadService.Stub() {
        override fun getApiVersion(): Int = LoadAwareContract.API_VERSION

        override fun getLoadState(): Bundle = currentState()?.toBundle() ?: Bundle()

        override fun registerListener(listener: ILoadStateListener?) {
            listener ?: return
            listeners.register(listener)
            currentState()?.let { runCatching { listener.onLoadStateChanged(it.toBundle()) } }
        }

        override fun unregisterListener(listener: ILoadStateListener?) {
            listener?.let { listeners.unregister(it) }
        }

        override fun submitMessage(sender: String?, text: String?, isCall: Boolean): Int {
            if (checkCallingPermission(LoadAwareContract.PERMISSION_SUBMIT_MESSAGE) != PackageManager.PERMISSION_GRANTED) {
                return LoadAwareContract.SUBMIT_DENIED_PERMISSION
            }
            val uid = Binder.getCallingUid()
            val (s, t) = sanitizeSubmission(sender, text) ?: return LoadAwareContract.SUBMIT_INVALID
            if (!limiter.tryAcquire(uid)) return LoadAwareContract.SUBMIT_RATE_LIMITED
            val label = callerLabel(uid)
            onSubmit(label, s, t, isCall)
            return LoadAwareContract.SUBMIT_ACCEPTED
        }
    }

    // Stub 안에서는 loadState가 AIDL getLoadState()와 이름이 겹치므로 바깥 값을 함수로 꺼낸다.
    private fun currentState(): PublicLoadState? = loadState.value

    private fun callerLabel(uid: Int): String {
        val pkg = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: return "uid:$uid"
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            loadState.filterNotNull()
                .distinctUntilChangedBy { Triple(it.stage, it.preHold, it.teumEnabled) }
                .collect { st ->
                    val b = st.toBundle()
                    val n = listeners.beginBroadcast()
                    try {
                        for (i in 0 until n) runCatching { listeners.getBroadcastItem(i).onLoadStateChanged(b) }
                    } finally {
                        listeners.finishBroadcast()
                    }
                }
        }
    }

    override fun onBind(intent: Intent): IBinder? =
        if (intent.action == LoadAwareContract.ACTION_BIND) binder else null

    override fun onDestroy() {
        scope.cancel()
        listeners.kill()
        super.onDestroy()
    }
}

/** 단계 변경을 권한으로 보호한 브로드캐스트로 알린다. */
object LoadStageBroadcaster {
    fun send(context: Context, state: PublicLoadState) {
        val intent = Intent(LoadAwareContract.ACTION_STAGE_CHANGED)
            .putExtras(state.toBundle())
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        try {
            context.sendBroadcast(intent, LoadAwareContract.PERMISSION_READ_LOAD_STATE)
        } catch (e: SecurityException) {
            Log.w("LoadStageBroadcaster", "broadcast failed", e)
        }
    }
}
