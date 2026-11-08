package app.teum.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.teum.notificationqueue.UrgencyRules
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** 신호 출처. */
enum class SignalMode(val label: String) {
    /** 실차/에뮬레이터 차량 신호(AAOS 표준 + Pleos Vehicle + NaviHelper) */
    VEHICLE("차량 신호"),
    /** JSON 시나리오 재생(시연·테스트) */
    SCENARIO("시나리오 재생"),
}

data class TeumSettings(
    /** false = '틈 OFF(기준 비교)': 모든 알림과 음성을 즉시 통과 */
    val teumEnabled: Boolean = true,
    val signalMode: SignalMode = SignalMode.VEHICLE,
    val emergencyContacts: List<String> = listOf("엄마"),
    val keywords: List<String> = UrgencyRules.DEFAULT_KEYWORDS.toList(),
    /** 처음 실행 안내 말풍선을 끝까지 봤거나 건너뛰었는지 */
    val guideSeen: Boolean = false,
) {
    val urgencyRules: UrgencyRules
        get() = UrgencyRules(emergencyContacts = emergencyContacts.toSet(), keywords = keywords.toSet())
}

private val Context.dataStore by preferencesDataStore(name = "teum_settings")

/**
 * 설정 저장소. 차량 안(앱 내부 저장소)에만 저장한다.
 * Pleos Sync SDK는 Playground 저장소(nexus-playground)·문서에서 찾지 못해 모바일 동기화는 하지 않는다(README 참고).
 */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val enabled = booleanPreferencesKey("teum_enabled")
        val mode = stringPreferencesKey("signal_mode")
        val contacts = stringPreferencesKey("emergency_contacts")
        val keywords = stringPreferencesKey("urgent_keywords")
        val guideSeen = booleanPreferencesKey("guide_seen")
    }

    private fun split(s: String?): List<String>? =
        s?.split(',', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() }

    /** 저장된 값을 한 번 읽는다(StateFlow 초기 기본값이 아닌 실제 값). */
    suspend fun current(): TeumSettings = flow.first()

    fun settings(scope: CoroutineScope): StateFlow<TeumSettings> = flow.stateIn(scope, SharingStarted.Eagerly, TeumSettings())

    private val flow = context.dataStore.data.map { p ->
        val d = TeumSettings()
        TeumSettings(
            teumEnabled = p[Keys.enabled] ?: d.teumEnabled,
            signalMode = p[Keys.mode]?.let { runCatching { SignalMode.valueOf(it) }.getOrNull() } ?: d.signalMode,
            emergencyContacts = split(p[Keys.contacts]) ?: d.emergencyContacts,
            keywords = split(p[Keys.keywords]) ?: d.keywords,
            guideSeen = p[Keys.guideSeen] ?: d.guideSeen,
        )
    }

    suspend fun setEnabled(v: Boolean) = context.dataStore.edit { it[Keys.enabled] = v }
    suspend fun setMode(m: SignalMode) = context.dataStore.edit { it[Keys.mode] = m.name }
    suspend fun setContacts(list: List<String>) = context.dataStore.edit { it[Keys.contacts] = list.joinToString(",") }
    suspend fun setKeywords(list: List<String>) = context.dataStore.edit { it[Keys.keywords] = list.joinToString(",") }
    suspend fun setGuideSeen(v: Boolean) = context.dataStore.edit { it[Keys.guideSeen] = v }
}
