package com.damon.mobilebox.data.watch

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 播放行为偏好：画中画 / 后台播放两个开关。
 *
 * 与 WatchStore 一样用独立 DataStore（`playback_prefs`），不复用主题的 `settings`，
 * 避免主题数据被清理时连坐。之所以同时保留「StateFlow」和「volatile 快照」：
 * - 设置页用 collectAsState()，切 Tab 不回退到默认值（ThemeManager 的教训）；
 * - MainActivity.onUserLeaveHint 与播放器生命周期回调不在 Compose 作用域里，
 *   需要立刻读到最新值，不能等 DataStore 落盘后再推流。
 */
private val Context.playbackDataStore by preferencesDataStore(name = "playback_prefs")

data class PlaybackPrefs(
    /** 按 Home 是否自动进 PiP 小窗。默认开，保持原有行为。 */
    val pipEnabled: Boolean = true,
    /** 切后台/锁屏是否继续播放。默认关，退到后台自动暂停。 */
    val backgroundPlayEnabled: Boolean = false
)

object PlaybackSettings {
    private val PIP_KEY = booleanPreferencesKey("pip_enabled")
    private val BACKGROUND_PLAY_KEY = booleanPreferencesKey("background_play_enabled")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false
    private var store: DataStore<Preferences>? = null

    private val _prefs = MutableStateFlow(PlaybackPrefs())
    val prefsFlow: StateFlow<PlaybackPrefs> = _prefs.asStateFlow()

    /** 非 Compose 环境的同步快照，写入开关时立即更新。 */
    @Volatile
    var pipEnabled: Boolean = true
        private set

    @Volatile
    var backgroundPlayEnabled: Boolean = false
        private set

    /** Compose 可观察：应用是否退到后台。由 MainActivity 的 onStart/onStop 维护。 */
    var appInBackground by mutableStateOf(false)
        private set

    /** 由 Application.onCreate 调用一次。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val ds = context.applicationContext.playbackDataStore
        store = ds
        scope.launch {
            ds.data.collect { prefs ->
                val value = PlaybackPrefs(
                    pipEnabled = prefs[PIP_KEY] ?: true,
                    backgroundPlayEnabled = prefs[BACKGROUND_PLAY_KEY] ?: false
                )
                pipEnabled = value.pipEnabled
                backgroundPlayEnabled = value.backgroundPlayEnabled
                _prefs.value = value
            }
        }
    }

    fun setPipEnabled(enabled: Boolean) {
        pipEnabled = enabled
        _prefs.value = _prefs.value.copy(pipEnabled = enabled)
        val ds = store ?: return
        scope.launch { ds.edit { it[PIP_KEY] = enabled } }
    }

    fun setBackgroundPlayEnabled(enabled: Boolean) {
        backgroundPlayEnabled = enabled
        _prefs.value = _prefs.value.copy(backgroundPlayEnabled = enabled)
        val ds = store ?: return
        scope.launch { ds.edit { it[BACKGROUND_PLAY_KEY] = enabled } }
    }

    fun onAppForeground() {
        appInBackground = false
    }

    fun onAppBackground() {
        appInBackground = true
    }
}
