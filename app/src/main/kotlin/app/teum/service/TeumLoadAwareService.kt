package app.teum.service

import app.teum.engine.LoadEngine
import app.teum.loadaware.LoadAwareServiceBase
import app.teum.loadaware.PublicLoadState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** 부하 인지 API 바운드 서비스. 매니페스트에서 READ_LOAD_STATE 권한으로 보호한다. */
@AndroidEntryPoint
class TeumLoadAwareService : LoadAwareServiceBase() {
    @Inject lateinit var engine: LoadEngine

    override val loadState: StateFlow<PublicLoadState?> get() = engine.publicState

    override fun onCreate() {
        super.onCreate()
        // 다른 앱이 먼저 바인드해도 엔진이 돌도록 한다
        LoadEngineService.start(this)
    }

    override fun onSubmit(appLabel: String, sender: String, text: String, isCall: Boolean) {
        engine.submitDelegated(appLabel, sender, text, isCall)
    }
}
