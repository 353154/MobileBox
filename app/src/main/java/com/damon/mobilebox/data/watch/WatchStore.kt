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
 * 一部影片某一集的观看记录。
 *
 * 一条记录 = 「影片 + 集数」，不是「一次播放会话」：同一集反复播放只更新这一条，
 * 否则历史列表会被同一个人为回退 / 拖动产生的短会话刷屏（用户 16:38 反馈）。
 *
 * watchedMs 是累计观看的墙上时间（含暂停 / 后台，上界近似值，仅供统计卡片）；
 * positionMs / durationMs 才是「看到了哪里」，由播放器进度回调在会话结束时回写。
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
    val watchedMs: Long = 0L,
    /** 最后一次离开时播放器所在位置（毫秒）。拖动进度条后以新位置为准。 */
    val positionMs: Long = 0L,
    /** 视频总时长（毫秒），用于算进度比例与显示「看到 x / 共 y」。 */
    val durationMs: Long = 0L,
    /** 电视直播频道。统计里单独算一类时长，且不参与「待看 / 在看」的影片派生。 */
    val isLive: Boolean = false
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
            val raw = (gson.fromJson<List<WatchRecord>>(json, historyType) as? List<WatchRecord>).orEmpty()
            normalizeHistory(raw)
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * 旧版 id 带时间戳（videoId_episodeId_now），同一集会留下 N 条记录。
     * 每次读盘按 videoId + episodeId 归并：元信息取最近一次，时长累加，进度取最近一次。
     * 这样老数据不用用户手动清理就自动收敛，写回时列表也永远是「一集一条」。
     */
    private fun normalizeHistory(raw: List<WatchRecord>): List<WatchRecord> =
        raw.groupBy { "${it.videoId}_${it.episodeId}" }
            .map { (key, group) ->
                val latest = group.maxByOrNull { it.startedAt } ?: group.first()
                latest.copy(
                    id = key,
                    watchedMs = group.sumOf { it.watchedMs.coerceAtLeast(0L) },
                    durationMs = group.maxOfOrNull { it.durationMs.coerceAtLeast(0L) } ?: 0L
                )
            }
            .sortedByDescending { it.startedAt }
            .take(HISTORY_LIMIT)

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
     * 播放器首帧落地时调用一次，返回本条记录的 id 供 [finishSession] 回写时长与进度。
     *
     * id 是稳定的「影片 + 集数」键：同一集反复播放只把这一条提到列表最前并刷新元信息，
     * 不会新增行。调用方仍用 recordId 门控同一会话内多次 onRenderedFirstFrame。
     */
    fun recordPlayback(
        videoId: String,
        title: String,
        posterUrl: String,
        episodeId: Int,
        episodeName: String,
        sourceName: String,
        isSeries: Boolean,
        isLive: Boolean = false
    ): String {
        val now = System.currentTimeMillis()
        val id = "${videoId}_${episodeId}"
        val ds = store ?: return id
        scope.launch {
            ds.edit { prefs ->
                val list = decodeHistory(prefs[KEY_HISTORY]).toMutableList()
                val existingIndex = list.indexOfFirst { it.id == id }
                val existing = if (existingIndex >= 0) list.removeAt(existingIndex) else null
                // 保留已累计的时长与上一次的进度：本次会话结束时 finishSession 再覆盖。
                val record = WatchRecord(
                    id = id,
                    videoId = videoId,
                    title = title,
                    posterUrl = posterUrl,
                    episodeId = episodeId,
                    episodeName = episodeName,
                    sourceName = sourceName,
                    isSeries = isSeries,
                    isLive = isLive,
                    startedAt = now,
                    watchedMs = existing?.watchedMs ?: 0L,
                    positionMs = existing?.positionMs ?: 0L,
                    durationMs = existing?.durationMs ?: 0L
                )
                list.add(0, record)
                prefs[KEY_HISTORY] = gson.toJson(list.take(HISTORY_LIMIT))
            }
        }
        return id
    }

    /**
     * 会话结束（换集 / 换源 / 退出播放 / 页面销毁）时结算：累计本次时长，
     * 并写入播放器最后上报的位置与总时长。进度只取 >0 的值，避免异常回调把已记录的位置抹成 0。
     */
    fun finishSession(recordId: String, watchedMs: Long, positionMs: Long, durationMs: Long) {
        if (recordId.isBlank() || (watchedMs <= 0L && positionMs <= 0L)) return
        val ds = store ?: return
        scope.launch {
            ds.edit { prefs ->
                val list = decodeHistory(prefs[KEY_HISTORY]).toMutableList()
                val index = list.indexOfFirst { it.id == recordId }
                if (index < 0) return@edit
                val old = list[index]
                list[index] = old.copy(
                    watchedMs = old.watchedMs + watchedMs.coerceAtLeast(0L),
                    positionMs = if (positionMs > 0L) positionMs else old.positionMs,
                    durationMs = if (durationMs > 0L) durationMs else old.durationMs
                )
                prefs[KEY_HISTORY] = gson.toJson(list)
            }
        }
    }

    // ------------------------------------------------------------------
    // 删除 / 清空：四个列表页共用这一份持久化。
    // 待看与在看都是派生视图，没有自己的存储：
    //   · 待看 = 收藏里没有点播记录的影片 → 删除/清空只动收藏表
    //   · 在看 = 点播历史按 videoId 聚合   → 删除/清空只动历史表（直播记录不动）
    // ------------------------------------------------------------------
    private fun editFavorites(transform: (MutableList<FavoriteItem>) -> Unit) {
        val ds = store ?: return
        scope.launch {
            ds.edit { prefs ->
                val list = decodeFavorites(prefs[KEY_FAVORITES]).toMutableList()
                transform(list)
                prefs[KEY_FAVORITES] = gson.toJson(list.take(FAVORITE_LIMIT))
            }
        }
    }

    private fun editHistory(transform: (MutableList<WatchRecord>) -> Unit) {
        val ds = store ?: return
        scope.launch {
            ds.edit { prefs ->
                val list = decodeHistory(prefs[KEY_HISTORY]).toMutableList()
                transform(list)
                prefs[KEY_HISTORY] = gson.toJson(list.take(HISTORY_LIMIT))
            }
        }
    }

    /** 观看历史：删一条（id = 影片 + 集数）。 */
    fun removeHistoryRecord(id: String) {
        if (id.isBlank()) return
        editHistory { list -> list.removeAll { record -> record.id == id } }
    }

    /** 观看历史：全清（含直播记录）。 */
    fun clearHistory() {
        editHistory { list -> list.clear() }
    }

    /** 追剧清单：同一个 videoId 从收藏表移除。 */
    fun removeFavorite(videoId: String) {
        if (videoId.isBlank()) return
        editFavorites { list -> list.removeAll { item -> item.videoId == videoId } }
    }

    /** 追剧清单：全清。 */
    fun clearFavorites() {
        editFavorites { list -> list.clear() }
    }

    /** 在看剧集：删掉这一部片的所有点播历史（收藏不动）。 */
    fun removeWatching(videoId: String) {
        if (videoId.isBlank()) return
        editHistory { list -> list.removeAll { record -> !record.isLive && record.videoId == videoId } }
    }

    /** 在看剧集：清空所有点播历史，直播记录保留。 */
    fun clearWatching() {
        editHistory { list -> list.removeAll { record -> !record.isLive } }
    }

    // ------------------------------------------------------------------
    // WebDAV 云端同步：合并结果一次性写回。
    // 用 suspend 的 edit（而非 fire-and-forget）保证调用方拿到的是已落盘状态；
    // 两个 key 在同一次 edit 里写，避免收藏与历史只落地一半。
    // ------------------------------------------------------------------
    suspend fun importBackup(favorites: List<FavoriteItem>, history: List<WatchRecord>) {
        val ds = store ?: return
        ds.edit { prefs ->
            prefs[KEY_FAVORITES] = gson.toJson(favorites.take(FAVORITE_LIMIT))
            prefs[KEY_HISTORY] = gson.toJson(normalizeHistory(history).take(HISTORY_LIMIT))
        }
    }
}
