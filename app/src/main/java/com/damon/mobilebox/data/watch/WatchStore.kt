package com.damon.mobilebox.data.watch

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 追剧收藏与播放历史的本地持久化。
 *
 * 刻意独立于 TvBoxRepository 的 `tvbox_subscription_prefs` 和主题用的 `settings`：
 * 订阅数据是「换源后可丢弃的缓存」，观影记录是用户资产，两者不能混在同一份文件里，
 * 否则清订阅时会被一起 remove 掉。
 *
 * 全应用只维护一个共享 StateFlow（参照 ThemeManager 的教训）：DataStore 是冷流，
 * 每个订阅点各自 `collectAsState(initial = ...)` 会先渲染一帧空列表，切 Tab 时
 * 收藏数会自己跳一下。这里由 Application 初始化一次、写操作统一走 edit，
 * 落盘后的新值再推回 StateFlow，UI 永远只有一份最新值。
 */
private val Context.watchDataStore by preferencesDataStore(name = "watch_prefs")

/** 一条追剧收藏。只存展示所需的最小字段，详情仍从订阅源现取，避免数据过期。 */
data class FavoriteItem(
    val videoId: String = "",
    val title: String = "",
    val posterUrl: String = "",
    val addedAt: Long = 0L
)

/**
 * 一次「真正出画面」的播放事件。
 * watchedMs 在会话结束（换集 / 换源 / 退出播放 / 页面销毁）时回写，
 * 统计卡片据此算时长。它包含暂停与后台时间，是上界近似值，不是解码器精确播放时长。
 */
data class WatchRecord(
    val id: String = "",
    val videoId: String = "",
    val title: String = "",
    val posterUrl: String = "",
    val episodeId: Int = 0,
    val episodeName: String = "",
    val sourceName: String = "",
    val isSeries: Boolean = false,
    val startedAt: Long = 0L,
    val watchedMs: Long = 0L
)

object WatchStore {
    private val KEY_FAVORITES = stringPreferencesKey("favorites_json")
    private val KEY_HISTORY = stringPreferencesKey("history_json")
    private const val HISTORY_LIMIT = 50
    private const val FAVORITE_LIMIT = 200

    private val gson = Gson()
    private val favoriteType = object : TypeToken<List<FavoriteItem>>() {}.type
    private val historyType = object : TypeToken<List<WatchRecord>>() {}.type
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false
    private var store: DataStore<Preferences>? = null

    private val _favorites = MutableStateFlow<List<FavoriteItem>>(emptyList())
    val favorites: StateFlow<List<FavoriteItem>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow<List<WatchRecord>>(emptyList())
    val history: StateFlow<List<WatchRecord>> = _history.asStateFlow()

    /** 由 Application.onCreate 调用一次；重复调用直接忽略，避免重复 collect 同一份冷流。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val ds = context.applicationContext.watchDataStore
        store = ds
        scope.launch {
            ds.data.collect { prefs ->
                _favorites.value = decodeFavorites(prefs[KEY_FAVORITES])
                _history.value = decodeHistory(prefs[KEY_HISTORY])
            }
        }
    }

    private fun decodeFavorites(json: String?): List<FavoriteItem> =
        if (json.isNullOrBlank()) emptyList()
        else try {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson<List<FavoriteItem>>(json, favoriteType) as? List<FavoriteItem>).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

    private fun decodeHistory(json: String?): List<WatchRecord> =
        if (json.isNullOrBlank()) emptyList()
        else try {
            @Suppress("UNCHECKED_CAST")
            (gson.fromJson<List<WatchRecord>>(json, historyType) as? List<WatchRecord>).orEmpty()
        } catch (_: Exception) {
            emptyList()
        }

    /** 点「追剧」：已收藏就移除，否则置顶加入。写操作串行化，不必额外加锁。 */
    fun toggleFavorite(item: FavoriteItem) {
        if (item.videoId.isBlank()) return
        val ds = store ?: return
        scope.launch {
            ds.edit { prefs ->
                val list = decodeFavorites(prefs[KEY_FAVORITES]).toMutableList()
                val index = list.indexOfFirst { it.videoId == item.videoId }
                if (index >= 0) {
                    list.removeAt(index)
                } else {
                    list.add(0, item.copy(addedAt = System.currentTimeMillis()))
                }
                prefs[KEY_FAVORITES] = gson.toJson(list.take(FAVORITE_LIMIT))
            }
        }
    }

    /**
     * 播放器首帧落地时调用一次，返回本条记录的 id 供 [finishSession] 回写时长。
     * 去重由调用方按「播放会话」门控：同一会话里 onRenderedFirstFrame 可能多次触发，
     * 换集 / 换源才应该新开一条记录。
     */
    fun recordPlayback(
        videoId: String,
        title: String,
        posterUrl: String,
        episodeId: Int,
        episodeName: String,
        sourceName: String,
        isSeries: Boolean
    ): String {
        val now = System.currentTimeMillis()
        val id = "${videoId}_${episodeId}_$now"
        val record = WatchRecord(
            id = id,
            videoId = videoId,
            title = title,
            posterUrl = posterUrl,
            episodeId = episodeId,
            episodeName = episodeName,
            sourceName = sourceName,
            isSeries = isSeries,
            startedAt = now
        )
        val ds = store ?: return id
        scope.launch {
            ds.edit { prefs ->
                val list = decodeHistory(prefs[KEY_HISTORY]).toMutableList()
                if (list.any { it.id == id }) return@edit
                list.add(0, record)
                prefs[KEY_HISTORY] = gson.toJson(list.take(HISTORY_LIMIT))
            }
        }
        return id
    }

    /** 会话结束时把观看时长补进对应记录；找不到（例如记录被裁掉）就静默跳过。 */
    fun finishSession(recordId: String, watchedMs: Long) {
        if (recordId.isBlank() || watchedMs <= 0L) return
        val ds = store ?: return
        scope.launch {
            ds.edit { prefs ->
                val list = decodeHistory(prefs[KEY_HISTORY]).toMutableList()
                val index = list.indexOfFirst { it.id == recordId }
                if (index < 0) return@edit
                list[index] = list[index].copy(watchedMs = watchedMs)
                prefs[KEY_HISTORY] = gson.toJson(list)
            }
        }
    }
}
