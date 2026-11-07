package com.damon.mobilebox.data.watch

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * WebDAV 云端同步配置。
 *
 * 与 watch_prefs / playback_prefs 一样用独立 DataStore：清订阅、清观影记录都不该
 * 把连接配置一起清掉。设置页用 collectAsState() 订阅共享 StateFlow，网络层在
 * 非 Compose 的 suspend 函数里读 [snapshot]，避免等冷流首帧。
 */
private val Context.webDavDataStore by preferencesDataStore(name = "webdav_prefs")

data class WebDavConfigState(
    /** 形如 https://dav.jianguoyun.com/dav/ 或 https://your.host/remote.php/dav/files/用户名/ */
    val baseUrl: String = "",
    val username: String = "",
    /** 多数服务（坚果云 / Nextcloud）要求用「应用密码」，不是登录密码。 */
    val password: String = "",
    /** 备份所在子目录，默认 MobileBox，最终文件为 <baseUrl>/<remoteDir>/backup.json。 */
    val remoteDir: String = "MobileBox"
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

object WebDavConfig {
    private val KEY_URL = stringPreferencesKey("base_url")
    private val KEY_USER = stringPreferencesKey("username")
    private val KEY_PASSWORD = stringPreferencesKey("password")
    private val KEY_DIR = stringPreferencesKey("remote_dir")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false
    private var store: DataStore<Preferences>? = null

    private val _state = MutableStateFlow(WebDavConfigState())
    val stateFlow: StateFlow<WebDavConfigState> = _state.asStateFlow()

    /** 同步快照，写入时立刻更新，供 WebDavSync 的 suspend 函数读取。 */
    @Volatile
    var snapshot: WebDavConfigState = WebDavConfigState()
        private set

    /** 由 Application.onCreate 调用一次。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val ds = context.applicationContext.webDavDataStore
        store = ds
        scope.launch {
            ds.data.collect { prefs ->
                val value = WebDavConfigState(
                    baseUrl = prefs[KEY_URL].orEmpty(),
                    username = prefs[KEY_USER].orEmpty(),
                    password = prefs[KEY_PASSWORD].orEmpty(),
                    remoteDir = prefs[KEY_DIR]?.takeIf { it.isNotBlank() } ?: "MobileBox"
                )
                snapshot = value
                _state.value = value
            }
        }
    }

    fun save(state: WebDavConfigState) {
        val normalized = state.copy(
            baseUrl = state.baseUrl.trim(),
            username = state.username.trim(),
            remoteDir = state.remoteDir.trim().ifBlank { "MobileBox" }
        )
        snapshot = normalized
        _state.value = normalized
        val ds = store ?: return
        scope.launch {
            ds.edit { prefs ->
                prefs[KEY_URL] = normalized.baseUrl
                prefs[KEY_USER] = normalized.username
                prefs[KEY_PASSWORD] = normalized.password
                prefs[KEY_DIR] = normalized.remoteDir
            }
        }
    }

    /** 只清本机保存的连接信息，云端备份文件不受影响。 */
    fun clear() {
        val empty = WebDavConfigState()
        snapshot = empty
        _state.value = empty
        val ds = store ?: return
        scope.launch {
            ds.edit { prefs ->
                prefs.remove(KEY_URL)
                prefs.remove(KEY_USER)
                prefs.remove(KEY_PASSWORD)
                prefs.remove(KEY_DIR)
            }
        }
    }
}
