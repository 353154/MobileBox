package com.damon.mobilebox.data.tmdb

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 首页快照：把上一次拿到的「轮播 + 三行榜单」整份落盘。
 *
 * 为什么需要它：`TmdbRepository` 里的 `trendingList` / `rowCache` 都只在内存，
 * 进程一死首页就回到骨架屏；冷启动赶上弱网，用户要盯着空白看 2~5 秒。
 * 有了快照就变成「先渲染上次的画面（读盘几十毫秒），网络回来再原地替换」，
 * 用户基本看不到空白。
 *
 * 三条约定：
 * 1. **不设硬过期**。快照只用来挡住空白，内容新旧交给随后的网络请求，所以哪怕
 *    是几天前的也先显示 —— 一张过期的海报墙也比白屏强。真正的新鲜度由
 *    `TmdbRepository` 的 1 小时 / 30 分钟内存 TTL 负责。
 * 2. **写盘合并**。首页一次加载会连续触发多次保存（八条 logo 陆续到），
 *    每次落盘显然不合理；这里用「首次调度 + 900ms 后写最新内存快照」合并。
 * 3. **只在有内容时覆盖**。一次全挂的加载不能把一份好快照冲成空文件。
 */
object HomeCache {
    private const val FILE_NAME = "home_snapshot.json"
    private const val WRITE_DELAY_MS = 900L

    /**
     * 结构版本。`TmdbMeta` 字段或 logo 挑选规则变化时 bump，
     * 旧文件直接作废（里面可能是一批已经对不上的条目）。
     */
    private const val VERSION = 1

    data class Snapshot(
        var version: Int = 0,
        var savedAt: Long = 0L,
        var trending: List<TmdbMeta> = emptyList(),
        var hot: List<TmdbMeta> = emptyList(),
        var airing: List<TmdbMeta> = emptyList(),
        var top: List<TmdbMeta> = emptyList()
    )

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var file: File? = null
    @Volatile
    private var snapshot: Snapshot? = null
    private var loaded = false
    private var writeScheduled = false

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            file = File(context.applicationContext.filesDir, FILE_NAME)
        }
    }

    /** 内存里那份，不读盘。 */
    fun peek(): Snapshot? = snapshot

    /**
     * 进程内第一次调用才真的读盘，之后走内存。
     *
     * 解析失败 / 版本不符 / 空快照一律返回 null —— 首页该退回骨架屏就退回，
     * 不能因为一个坏文件把首页搞崩。
     */
    suspend fun load(): Snapshot? {
        synchronized(lock) { if (loaded) return snapshot }
        val target = file ?: return null
        val parsed = withContext(Dispatchers.IO) {
            runCatching {
                if (target.exists()) gson.fromJson(target.readText(), Snapshot::class.java) else null
            }.getOrNull()
        }
        val safe = runCatching {
            parsed?.takeIf {
                it.version == VERSION &&
                    (it.trending.isNotEmpty() || it.hot.isNotEmpty() || it.top.isNotEmpty())
            }
        }.getOrNull()
        synchronized(lock) {
            loaded = true
            snapshot = safe
        }
        return safe
    }

    /** 保存一份新快照。空内容直接忽略，避免把好快照冲掉。 */
    fun save(
        trending: List<TmdbMeta>,
        hot: List<TmdbMeta>,
        airing: List<TmdbMeta>,
        top: List<TmdbMeta>
    ) {
        if (trending.isEmpty() && hot.isEmpty() && airing.isEmpty() && top.isEmpty()) return
        synchronized(lock) {
            snapshot = Snapshot(
                version = VERSION,
                savedAt = System.currentTimeMillis(),
                trending = trending,
                hot = hot,
                airing = airing,
                top = top
            )
            loaded = true
            if (writeScheduled) return
            writeScheduled = true
        }
        scope.launch {
            delay(WRITE_DELAY_MS)
            val target = synchronized(lock) {
                writeScheduled = false
                file
            } ?: return@launch
            // 锁外序列化：Gson 走一遍五十条 meta 要几毫秒，别占着锁。
            val current = snapshot ?: return@launch
            val text = runCatching { gson.toJson(current) }.getOrNull() ?: return@launch
            val ok = runCatching { target.writeText(text) }.isSuccess
            if (!ok) android.util.Log.w("MB-TMDB", "首页快照写入失败")
        }
    }
}
