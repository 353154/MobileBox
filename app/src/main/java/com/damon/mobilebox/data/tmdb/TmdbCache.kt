package com.damon.mobilebox.data.tmdb

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * TMDB 刮削结果本地缓存。
 *
 * 为什么不用 Room：只需要「查一个键、拿一个对象」，为它引一整个 ORM + 注解处理器
 * 不值当。当前缓存上限 500 条，序列化后约 150KB，一次读写都在毫秒级。
 *
 * 三张表：
 * - entries：tmdbId → TmdbMeta，按 LRU 淘汰（LinkedHashMap 访问顺序，读时移到末尾）；
 * - index：`归一化标题|年份|类型` → tmdbId，避免每次进详情页都重新搜索；
 * - misses：同格式的键 → 时间戳，负缓存 7 天。国产短剧在 TMDB 根本没有条目，
 *   没有负缓存的话每次进详情页都要白跑两次搜索请求。
 */
object TmdbCache {
    private const val FILE_NAME = "tmdb_cache.json"
    private const val MAX_ENTRIES = 500
    private const val ENTRY_TTL_MS = 30L * 24 * 3600 * 1000
    private const val MISS_TTL_MS = 7L * 24 * 3600 * 1000

    /**
     * 匹配策略版本。搜索策略变更后旧负缓存会「认死」一批本来能刮到的片，
     * 版本不一致时清空索引与负缓存、保留已刮到的条目，避免用户要等 7 天或清数据。
     */
    private const val STRATEGY_VERSION = 6

    private data class Store(
        var strategy: Int = 0,
        var entries: MutableMap<String, TmdbMeta> = LinkedHashMap(),
        var index: MutableMap<String, String> = LinkedHashMap(),
        var misses: MutableMap<String, Long> = LinkedHashMap()
    )

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var file: File? = null
    private var store = Store()
    private var writeScheduled = false

    fun init(context: Context) {
        val target = File(context.applicationContext.filesDir, FILE_NAME)
        synchronized(lock) {
            if (file != null) return
            file = target
            store = runCatching {
                if (target.exists()) gson.fromJson(target.readText(), Store::class.java) else null
            }.getOrNull() ?: Store()
            if (store.strategy != STRATEGY_VERSION) {
                store.entries.clear()
                store.index.clear()
                store.misses.clear()
                store.strategy = STRATEGY_VERSION
                scheduleWrite()
            }
        }
    }

    /** 命中索引且未过期。每次读都把条目移到 LRU 末尾。 */
    fun get(queryKey: String): TmdbMeta? = synchronized(lock) {
        val id = store.index[queryKey] ?: return null
        val meta = store.entries.remove(id) ?: run {
            store.index.remove(queryKey)
            return null
        }
        if (System.currentTimeMillis() - meta.updatedAt > ENTRY_TTL_MS) {
            store.index.remove(queryKey)
            return null
        }
        store.entries[id] = meta // 重新插入 = 移到末尾
        meta
    }

    /** 反向查：同名影片可能命中不同 query key，落缓存前先按 tmdbId 复用已缓存的详情。 */
    fun getById(tmdbId: Int): TmdbMeta? = synchronized(lock) {
        store.entries[tmdbId.toString()]
    }

    fun isMiss(queryKey: String): Boolean = synchronized(lock) {
        val at = store.misses[queryKey] ?: return false
        if (System.currentTimeMillis() - at > MISS_TTL_MS) {
            store.misses.remove(queryKey)
            return false
        }
        true
    }

    fun put(queryKey: String, meta: TmdbMeta) {
        synchronized(lock) {
            val id = meta.tmdbId.toString()
            store.entries[id] = meta
            store.index[queryKey] = id
            store.misses.remove(queryKey)
            if (store.entries.size > MAX_ENTRIES) {
                val oldest = store.entries.keys.firstOrNull() ?: return
                store.entries.remove(oldest)
                store.index.entries.removeAll { it.value == oldest }
            }
        }
        scheduleWrite()
    }

    fun putMiss(queryKey: String) {
        synchronized(lock) {
            store.misses[queryKey] = System.currentTimeMillis()
            // 负缓存同样需要上限：大量不同片名进进出出时不能无限增长。
            if (store.misses.size > MAX_ENTRIES) {
                store.misses.keys.firstOrNull()?.let { store.misses.remove(it) }
            }
        }
        scheduleWrite()
    }

    /** 延迟合并写入：一次详情页可能连续写索引 + 详情 + 负缓存，没必要每次都落盘。 */
    private fun scheduleWrite() {
        synchronized(lock) {
            if (writeScheduled) return
            writeScheduled = true
        }
        scope.launch {
            delay(800)
            val json = synchronized(lock) {
                writeScheduled = false
                val text = runCatching { gson.toJson(store) }.getOrNull()
                val target = file
                if (text != null && target != null) runCatching { target.writeText(text) }
                text
            }
            if (json == null) android.util.Log.w("MB-TMDB", "缓存写入失败")
        }
    }
}
