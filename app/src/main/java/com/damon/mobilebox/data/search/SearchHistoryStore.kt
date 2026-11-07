package com.damon.mobilebox.data.search

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
 * 搜索页「最近搜索」的本地持久化（2026-11-08 新增）。
 *
 * 与 `WatchStore` 同一套结构：独立 `search_prefs`，Application 里 init 一次，
 * 全应用只维护一份 StateFlow。UI 用 `collectAsState()` 订阅（不带 initial），
 * 不会先渲染一帧空列表再跳成有值。
 *
 * 只存关键词字符串，不存搜索结果：`SearchHit` 里带 `VideoDetailData`，
 * 体积大且不可序列化，会话内免重查交给进程级 `SearchMemory` 负责。
 */
private val Context.searchDataStore by preferencesDataStore(name = "search_prefs")

object SearchHistoryStore {
    private const val LIMIT = 10
    private val KEY_RECENT = stringPreferencesKey("recent_keywords")
    private val gson = Gson()
    private val listType = object : TypeToken<List<String>>() {}.type
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var initialized = false
    private var store: DataStore<Preferences>? = null

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    /** 由 Application.onCreate 调用一次；重复调用直接忽略，避免重复 collect 同一份冷流。 */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val ds = context.applicationContext.searchDataStore
        store = ds
        scope.launch {
            ds.data.collect { prefs -> _history.value = decode(prefs[KEY_RECENT]) }
        }
    }

    private fun decode(json: String?): List<String> =
        if (json.isNullOrBlank()) emptyList()
        else try {
            gson.fromJson<List<String>>(json, listType)
                .orEmpty()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
                .take(LIMIT)
        } catch (_: Exception) {
            emptyList()
        }

    /** 记一次真实发起过的搜索：去重置顶，超过 10 条丢最旧的。 */
    fun add(word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        val ds = store ?: return
        scope.launch {
            ds.edit { prefs ->
                val list = decode(prefs[KEY_RECENT]).toMutableList()
                list.removeAll { it.equals(w, ignoreCase = true) }
                list.add(0, w)
                prefs[KEY_RECENT] = gson.toJson(list.take(LIMIT))
            }
        }
    }

    fun remove(word: String) {
        val ds = store ?: return
        scope.launch {
            ds.edit { prefs ->
                val list = decode(prefs[KEY_RECENT]).filterNot { it == word }
                if (list.isEmpty()) prefs.remove(KEY_RECENT) else prefs[KEY_RECENT] = gson.toJson(list)
            }
        }
    }

    fun clear() {
        val ds = store ?: return
        scope.launch {
            ds.edit { prefs -> prefs.remove(KEY_RECENT) }
        }
    }
}
