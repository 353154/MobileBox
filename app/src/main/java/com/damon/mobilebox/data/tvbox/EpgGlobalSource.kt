package com.damon.mobilebox.data.tvbox

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 全局 XMLTV 兜底节目单。
 *
 * 订阅配置里的 EPG 模板（`xyzweb.cyou/tvbox/epg`、`epg.112114.xyz`）自 2026-11 起相继失效：
 * 前者对任何频道/日期都恒定返回 `{"epg_data":[]}`，后者域名已完全不可达。
 * 这里用一份每日维护的公开 XMLTV 作为兜底来源：
 *
 *  - 整包只下载一次（约 8.5MB），流式解析，不把整个文件读成字符串；
 *  - 只保留「今天 ± 1 天」的节目，内存里约 5~8k 条，够 135 个频道用；
 *  - 解析结果按天落盘缓存（约 200~400KB），当天/未过期时直接读缓存，不再下载；
 *  - 频道 key 与 [EpgPrograms.normalize] 同源，`CCTV-1` 与 XMLTV 的 `CCTV1`、
 *    `湖南卫视` 与 `湖南卫视` 都能对上。
 *
 * 失败一律返回空表，由调用方沿用「查不到就不显示」的既有行为，不编造节目。
 */
object EpgGlobalSource {

    private const val TAG = "MB-EPG"
    private const val EPG_URL = "https://cdn.jsdelivr.net/gh/fanmingming/live@main/e.xml"
    private const val CACHE_NAME = "epg_global_v1.txt"
    private const val CACHE_HEADER = "v1"
    /** 缓存有效期：节目单按天变，配合缓存头里的日期，实际每天最多下载一次 */
    private const val TTL_MS = 12L * 60 * 60 * 1000

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val timeFormats = listOf(
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US),
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US),
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    )

    private val lock = Any()
    private val loadMutex = Mutex()

    private var memo: Map<String, List<EpgSlot>> = emptyMap()
    private var memoDay: String = ""

    /** 已经尝试过但没有任何节目的日期，避免同一天内反复重试把流量打空 */
    private var failedDay: String = ""

    /**
     * 取当天节目单。多次并发调用只会真正下载/解析一次，其余等待同一份结果。
     *
     * @return 归一化频道 key -> 当天节目列表；失败返回空 Map（调用方据此整行不显示）
     */
    suspend fun load(context: Context, nowMillis: Long = System.currentTimeMillis()): Map<String, List<EpgSlot>> {
        val day = dayKey(nowMillis)
        synchronized(lock) {
            if (memoDay == day && memo.isNotEmpty()) return memo
            if (failedDay == day) return emptyMap()
        }
        return loadMutex.withLock {
            synchronized(lock) {
                if (memoDay == day && memo.isNotEmpty()) return memo
                if (failedDay == day) return emptyMap()
            }
            val result = runCatching { build(context, day, nowMillis) }
                .onFailure { Log.w(TAG, "全局节目单加载失败: ${it.javaClass.simpleName} ${it.message}") }
                .getOrDefault(emptyMap())
            synchronized(lock) {
                if (result.isNotEmpty()) {
                    memo = result
                    memoDay = day
                    failedDay = ""
                } else {
                    failedDay = day
                }
            }
            if (result.isNotEmpty()) {
                Log.i(TAG, "全局节目单就绪: ${result.size} 个频道 / ${result.values.sumOf { it.size }} 档")
            } else {
                Log.i(TAG, "全局节目单不可用")
            }
            result
        }
    }

    private fun build(context: Context, day: String, nowMillis: Long): Map<String, List<EpgSlot>> {
        // 1. 当天缓存命中：直接读盘（约几十毫秒）
        readCache(context, day)?.let { cached ->
            if (cached.isNotEmpty()) {
                Log.i(TAG, "全局节目单命中缓存: ${cached.size} 个频道")
                return cached
            }
        }
        // 2. 否则整包下载 + 流式解析
        val request = Request.Builder()
            .url(EPG_URL)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) MobileBox")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "全局节目单下载失败: HTTP ${response.code}")
                return emptyMap()
            }
            val body = response.body ?: return emptyMap()
            val (dayStart, dayEnd) = dayWindow(nowMillis)
            val parsed = body.byteStream().use { parseStream(it, dayStart, dayEnd) }
            if (parsed.isEmpty()) return emptyMap()
            writeCache(context, day, parsed)
            return parsed
        }
    }

    /** 流式解析，只保留落在 [dayStart, dayEnd) 的节目，避免把整月数据都留在内存里 */
    private fun parseStream(
        input: InputStream,
        dayStart: Long,
        dayEnd: Long
    ): Map<String, List<EpgSlot>> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val result = HashMap<String, MutableList<EpgSlot>>()
        var channelKey = ""
        var start = 0L
        var stop = 0L
        var title = ""
        var capturingTitle = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                    "programme" -> {
                        channelKey = EpgPrograms.normalize(parser.getAttributeValue(null, "channel").orEmpty())
                        start = parseTime(parser.getAttributeValue(null, "start")) ?: 0L
                        stop = parseTime(parser.getAttributeValue(null, "stop")) ?: 0L
                        title = ""
                    }
                    "title" -> capturingTitle = true
                }
                XmlPullParser.TEXT -> {
                    if (capturingTitle && title.isBlank()) title = parser.text.trim()
                }
                XmlPullParser.END_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                    "title" -> capturingTitle = false
                    "programme" -> {
                        if (channelKey.isNotEmpty() && title.isNotEmpty() &&
                            start in dayStart until dayEnd && stop > start
                        ) {
                            result.getOrPut(channelKey) { ArrayList() }.add(EpgSlot(title, start, stop))
                        }
                    }
                }
            }
            event = parser.next()
        }
        return result.mapValues { (_, slots) ->
            slots.distinctBy { it.startMillis }.sortedBy { it.startMillis }
        }
    }

    private fun parseTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        for (format in timeFormats) {
            try {
                synchronized(format) { format.parse(text) }?.let { return it.time }
            } catch (_: Exception) {
                // 换下一种格式
            }
        }
        return null
    }

    // ---------- 缓存 ----------

    private fun cacheFile(context: Context): File = File(context.cacheDir, CACHE_NAME)

    private fun readCache(context: Context, day: String): Map<String, List<EpgSlot>>? {
        val file = cacheFile(context)
        if (!file.isFile) return null
        if (System.currentTimeMillis() - file.lastModified() > TTL_MS) return null
        return runCatching {
            val lines = file.bufferedReader().use { it.readLines() }
            val header = lines.firstOrNull()?.split('|') ?: return null
            if (header.size < 2 || header[0] != CACHE_HEADER || header[1] != day) return null
            val map = HashMap<String, MutableList<EpgSlot>>()
            for (line in lines.drop(1)) {
                val parts = line.split('|', limit = 4)
                if (parts.size < 4) continue
                val key = parts[0]
                val s = parts[1].toLongOrNull() ?: continue
                val e = parts[2].toLongOrNull() ?: continue
                val t = parts[3]
                if (key.isEmpty() || t.isEmpty() || e <= s) continue
                map.getOrPut(key) { ArrayList() }.add(EpgSlot(t, s, e))
            }
            map.mapValues { (_, v) -> v.sortedBy { it.startMillis } }
        }.getOrNull()
    }

    private fun writeCache(context: Context, day: String, data: Map<String, List<EpgSlot>>) {
        runCatching {
            val file = cacheFile(context)
            val tmp = File(file.parentFile, "$CACHE_NAME.tmp")
            tmp.bufferedWriter().use { writer ->
                writer.write("$CACHE_HEADER|$day\n")
                data.forEach { (key, slots) ->
                    slots.forEach { slot ->
                        writer.write("$key|${slot.startMillis}|${slot.endMillis}|${slot.title}\n")
                    }
                }
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { Log.w(TAG, "全局节目单缓存写入失败: ${it.message}") }
    }

    // ---------- 时间窗口 ----------

    private fun dayKey(nowMillis: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date(nowMillis))

    /** 只保留今天和明天凌晨的节目：跨零点的那档也要在，否则 23:50 打开会缺当前节目 */
    private fun dayWindow(nowMillis: Long): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return start to start + 36L * 60 * 60 * 1000
    }
}
