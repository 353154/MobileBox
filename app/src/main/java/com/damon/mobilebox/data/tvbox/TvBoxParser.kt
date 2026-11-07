package com.damon.mobilebox.data.tvbox

import android.net.Uri
import android.util.Base64
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.zip.GZIPInputStream
import com.damon.mobilebox.ui.player.VideoDetailData
import com.damon.mobilebox.ui.player.VideoEpisode
import com.damon.mobilebox.ui.player.VideoRoute
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object TvBoxParser {

    private val gson: Gson = GsonBuilder().setLenient().create()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val DEFAULT_UA = "okhttp/3.15 (Linux; Android 14; MobileBox)"
    private val AES_DEFAULT_KEY = "258258tvboxlive!".toByteArray(StandardCharsets.UTF_8)
    private val AES_DEFAULT_IV  = "1234567890abcdef".toByteArray(StandardCharsets.UTF_8)

    /**
     * 抓取并解析远程 TVBox 订阅配置 (支持单仓、多仓 urls、Base64 以及常见 AES-CBC 解密)
     */
    suspend fun fetchSubscription(url: String): Result<TvBoxSubscriptionResult> = withContext(Dispatchers.IO) {
        try {
            val trimmedUrl = url.trim()
            val request = Request.Builder()
                .url(trimmedUrl)
                .header("User-Agent", DEFAULT_UA)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP 请求失败: ${response.code}"))
            }

            val rawBytes = response.body?.bytes() ?: byteArrayOf()
            if (rawBytes.isEmpty()) {
                return@withContext Result.failure(Exception("订阅内容为空"))
            }

            // 1. 解码与解密内容
            val content = decryptAndNormalizeContent(rawBytes)
            if (content.isBlank()) {
                return@withContext Result.failure(Exception("订阅内容为空或解密失败"))
            }

            // 2. 判断是否是多仓订阅 (含有 urls 列表或根为多仓数组)
            val warehouses = tryParseMultiWarehouse(content, trimmedUrl)
            if (warehouses.isNotEmpty()) {
                return@withContext Result.success(
                    TvBoxSubscriptionResult.MultiWarehouse(
                        warehouses = warehouses,
                        rawUrl = trimmedUrl
                    )
                )
            }

            // 3. 按照标准单仓 JSON 进行解析
            val config = gson.fromJson(content, TvBoxConfig::class.java)
            if (config == null) {
                return@withContext Result.failure(Exception("无法解析有效的 JSON 配置"))
            }

            // 检查是否有 urls 字段配置（部分多仓对象嵌套 urls）
            val nestedWarehouses = parseUrlsField(config.urls, trimmedUrl)
            if (nestedWarehouses.isNotEmpty()) {
                return@withContext Result.success(
                    TvBoxSubscriptionResult.MultiWarehouse(
                        warehouses = nestedWarehouses,
                        rawUrl = trimmedUrl
                    )
                )
            }

            // 4. 标准单仓验证与相对路径修正
            val rawSites = config.sites ?: emptyList()
            if (rawSites.isEmpty()) {
                return@withContext Result.failure(Exception("未在配置中找到可用的 sites 视频站点或 urls 多仓"))
            }

            // 将相对路径补全为基于当前订阅 URL 的完整链接
            val resolvedSites = rawSites.map { site ->
                val resolvedApi = resolveRelativeUrl(trimmedUrl, site.api)
                site.copy(api = resolvedApi)
            }
            val resolvedLives = config.lives?.map { live ->
                live.copy(url = live.url?.let { resolveRelativeUrl(trimmedUrl, it) })
            }
            val resolvedConfig = config.copy(
                sites = resolvedSites,
                lives = resolvedLives,
                spider = config.spider?.let { resolveRelativeUrl(trimmedUrl, it) }
            )

            Result.success(TvBoxSubscriptionResult.SingleConfig(resolvedConfig, trimmedUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 多重解密与归一化文本处理：处理 BOM、Base64、AES-CBC/ECB、去注释与非法尾逗号
     */
    private fun decryptAndNormalizeContent(rawBytes: ByteArray): String {
        var str = String(rawBytes, StandardCharsets.UTF_8).trim()
        if (str.startsWith("\uFEFF")) {
            str = str.substring(1).trim()
        }

        // 1. 若以 enc:// 开头，说明是 AES 加密
        if (str.startsWith("enc://")) {
            val base64Payload = str.removePrefix("enc://").trim()
            val decrypted = decryptAes(base64Payload)
            if (!decrypted.isNullOrBlank()) {
                return cleanJsonString(decrypted)
            }
        }

        // 2. 若包含 ** 或 2423 开头
        if (str.startsWith("**")) {
            try {
                val decoded = Base64.decode(str.removePrefix("**"), Base64.DEFAULT)
                val testStr = String(decoded, StandardCharsets.UTF_8).trim()
                if (testStr.startsWith("{") || testStr.startsWith("[")) {
                    return cleanJsonString(testStr)
                }
            } catch (e: Exception) { /* ignore */ }
        }

        // 3. 直接以 { 或 [ 开头，说明是标准 JSON
        if (str.startsWith("{") || str.startsWith("[")) {
            return cleanJsonString(str)
        }

        // 4. 尝试 Base64 解码
        try {
            val decoded = Base64.decode(str, Base64.DEFAULT)
            val decodedStr = String(decoded, StandardCharsets.UTF_8).trim()
            if (decodedStr.startsWith("{") || decodedStr.startsWith("[")) {
                return cleanJsonString(decodedStr)
            }
            // 若解码后不是直接 JSON，尝试对其 AES 解密
            val aesDecrypted = decryptAesBytes(decoded)
            if (!aesDecrypted.isNullOrBlank()) {
                return cleanJsonString(aesDecrypted)
            }
        } catch (e: Exception) {
            // ignore
        }

        // 5. 尝试直接对原始字节做 AES 解密
        val directAes = decryptAes(str)
        if (!directAes.isNullOrBlank() && (directAes.startsWith("{") || directAes.startsWith("["))) {
            return cleanJsonString(directAes)
        }

        return cleanJsonString(str)
    }

    /**
     * AES 解密助手 (支持 CBC/ECB，默认 key: 258258tvboxlive! iv: 1234567890abcdef)
     */
    private fun decryptAes(base64Cipher: String): String? {
        return try {
            val cipherBytes = Base64.decode(base64Cipher.trim(), Base64.DEFAULT)
            decryptAesBytes(cipherBytes)
        } catch (e: Exception) {
            null
        }
    }

    private fun decryptAesBytes(cipherBytes: ByteArray): String? {
        if (cipherBytes.isEmpty()) return null
        // 尝试 1: CBC with default key and default IV
        try {
            val key = SecretKeySpec(AES_DEFAULT_KEY, "AES")
            val iv = IvParameterSpec(AES_DEFAULT_IV)
            val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, iv)
            val decrypted = cipher.doFinal(cipherBytes)
            val res = String(decrypted, StandardCharsets.UTF_8).trim()
            if (res.startsWith("{") || res.startsWith("[")) return res
        } catch (e: Exception) { /* continue */ }

        // 尝试 2: CBC 前 16 字节作为 IV
        if (cipherBytes.size > 16) {
            try {
                val ivBytes = cipherBytes.copyOfRange(0, 16)
                val realCipher = cipherBytes.copyOfRange(16, cipherBytes.size)
                val key = SecretKeySpec(AES_DEFAULT_KEY, "AES")
                val iv = IvParameterSpec(ivBytes)
                val cipher = Cipher.getInstance("AES/CBC/PKCS7Padding")
                cipher.init(Cipher.DECRYPT_MODE, key, iv)
                val decrypted = cipher.doFinal(realCipher)
                val res = String(decrypted, StandardCharsets.UTF_8).trim()
                if (res.startsWith("{") || res.startsWith("[")) return res
            } catch (e: Exception) { /* continue */ }
        }

        // 尝试 3: ECB 模式
        try {
            val key = SecretKeySpec(AES_DEFAULT_KEY, "AES")
            val cipher = Cipher.getInstance("AES/ECB/PKCS7Padding")
            cipher.init(Cipher.DECRYPT_MODE, key)
            val decrypted = cipher.doFinal(cipherBytes)
            val res = String(decrypted, StandardCharsets.UTF_8).trim()
            if (res.startsWith("{") || res.startsWith("[")) return res
        } catch (e: Exception) { /* continue */ }

        return null
    }

    /**
     * 清理 JSON 文本中的多余注释与非法尾随逗号
     */
    private fun cleanJsonString(raw: String): String {
        var cleaned = raw.trim()
        // 去除单行注释 // ...
        cleaned = cleaned.replace(Regex("""(?m)^\s*//.*$"""), "")
        // 去除尾随逗号 (例如 , } 或 , ])
        cleaned = cleaned.replace(Regex(""",\s*([}\]])"""), "$1")
        return cleaned.trim()
    }

    /**
     * 解析多仓 urls 字段 (支持多种主流多仓形态)
     */
    private fun tryParseMultiWarehouse(jsonContent: String, baseUrl: String): List<TvBoxWarehouse> {
        val list = mutableListOf<TvBoxWarehouse>()
        try {
            val element = JsonParser.parseString(jsonContent)
            // 形态 1: 根为数组 [{"name":"xxx","url":"http..."}, "线路2@http..."]
            if (element.isJsonArray) {
                parseJsonArrayToWarehouses(element.asJsonArray, baseUrl, list)
            } else if (element.isJsonObject) {
                val obj = element.asJsonObject
                if (obj.has("urls") && obj.get("urls").isJsonArray) {
                    parseJsonArrayToWarehouses(obj.getAsJsonArray("urls"), baseUrl, list)
                }
            }
        } catch (e: Exception) {
            // 形态 2: 纯文本按行多仓 (线路名,http... 或 线路名@http...)
            val lines = jsonContent.lines()
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                    val parsed = parseWarehouseLine(trimmed, baseUrl)
                    if (parsed != null) list.add(parsed)
                }
            }
        }
        return list
    }

    private fun parseUrlsField(urlsField: Any?, baseUrl: String): List<TvBoxWarehouse> {
        if (urlsField == null) return emptyList()
        val list = mutableListOf<TvBoxWarehouse>()
        try {
            val jsonElement = gson.toJsonTree(urlsField)
            if (jsonElement.isJsonArray) {
                parseJsonArrayToWarehouses(jsonElement.asJsonArray, baseUrl, list)
            }
        } catch (e: Exception) { /* ignore */ }
        return list
    }

    private fun parseJsonArrayToWarehouses(array: JsonArray, baseUrl: String, output: MutableList<TvBoxWarehouse>) {
        for (item in array) {
            if (item.isJsonObject) {
                val obj = item.asJsonObject
                val url = obj.get("url")?.asString?.trim() ?: ""
                val name = obj.get("name")?.asString?.trim() ?: "线路 ${output.size + 1}"
                if (url.isNotBlank()) {
                    output.add(TvBoxWarehouse(name = name, url = resolveRelativeUrl(baseUrl, url)))
                }
            } else if (item.isJsonPrimitive && item.asJsonPrimitive.isString) {
                val str = item.asString.trim()
                val parsed = parseWarehouseLine(str, baseUrl)
                if (parsed != null) output.add(parsed)
            }
        }
    }

    private fun parseWarehouseLine(line: String, baseUrl: String): TvBoxWarehouse? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("//")) return null
        return when {
            trimmed.contains("@") -> {
                val parts = trimmed.split("@", limit = 2)
                val name = parts[0].trim()
                val url = parts[1].trim()
                if (url.startsWith("http")) TvBoxWarehouse(name, resolveRelativeUrl(baseUrl, url)) else null
            }
            trimmed.contains(",") -> {
                val parts = trimmed.split(",", limit = 2)
                val name = parts[0].trim()
                val url = parts[1].trim()
                if (url.startsWith("http")) TvBoxWarehouse(name, resolveRelativeUrl(baseUrl, url)) else null
            }
            trimmed.startsWith("http") -> {
                TvBoxWarehouse("线路 ${trimmed.takeLast(10)}", resolveRelativeUrl(baseUrl, trimmed))
            }
            else -> null
        }
    }

    /**
     * 自动补全相对路径 (如 ./jar/spider.jar 补全为 http://domain/path/jar/spider.jar)
     */
    fun resolveRelativeUrl(baseUrl: String, targetUrl: String): String {
        val target = targetUrl.trim()
        if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("clan://")) {
            return target
        }
        return try {
            val baseUri = Uri.parse(baseUrl)
            val cleanTarget = if (target.startsWith("./")) target.substring(2) else target.removePrefix("/")
            val lastSlash = baseUrl.lastIndexOf('/')
            if (lastSlash != -1) {
                baseUrl.substring(0, lastSlash + 1) + cleanTarget
            } else {
                "${baseUri.scheme}://${baseUri.host}/$cleanTarget"
            }
        } catch (e: Exception) {
            target
        }
    }

    /**
     * 解析并拉取直播列表 (支持 TVBox live.txt #genre# 格式及标准 M3U 播放列表)
     */
    suspend fun fetchLiveChannels(liveUrl: String): Result<LiveParseResult> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(liveUrl.trim())
                .header("User-Agent", DEFAULT_UA)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("拉取直播源失败: ${response.code}"))
            }

            val content = response.body?.string() ?: ""
            val groups = parseLiveChannelsContent(content, liveUrl)
            // M3U 头里的 url-tvg / x-tvg-url 是标准 EPG 入口；没有就不查，UI 也不显示节目行
            val epgUrls = extractEpgUrls(content.lines())
            Result.success(LiveParseResult(groups, epgUrls))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 解析直播源文本内容 (TXT & M3U 混编自适应)
     */
    fun parseLiveChannelsContent(rawContent: String, baseUrl: String): List<LiveChannelGroup> {
        val lines = rawContent.lines()
        val isM3u = lines.any { it.trim().startsWith("#EXTM3U") }
        return if (isM3u) {
            parseM3uLive(lines, baseUrl)
        } else {
            parseTxtLive(lines, baseUrl)
        }
    }

    private fun parseTxtLive(lines: List<String>, baseUrl: String): List<LiveChannelGroup> {
        val groupList = mutableListOf<LiveChannelGroup>()
        var currentGroupName = "央视频道"
        val currentChannels = mutableListOf<LiveChannel>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#EXT")) continue

            if (trimmed.contains("#genre#")) {
                if (currentChannels.isNotEmpty()) {
                    groupList.add(LiveChannelGroup(currentGroupName, currentChannels.toList()))
                    currentChannels.clear()
                }
                currentGroupName = trimmed.replace(",#genre#", "").replace("#genre#", "").trim()
                continue
            }

            if (trimmed.contains(",")) {
                val parts = trimmed.split(",", limit = 2)
                val channelName = parts[0].trim()
                val urlPart = parts[1].trim()
                if (urlPart.isNotBlank()) {
                    val resolvedUrl = resolveRelativeUrl(baseUrl, urlPart)
                    currentChannels.add(
                        LiveChannel(
                            name = channelName,
                            url = resolvedUrl,
                            group = currentGroupName
                        )
                    )
                }
            }
        }
        if (currentChannels.isNotEmpty()) {
            groupList.add(LiveChannelGroup(currentGroupName, currentChannels.toList()))
        }
        return groupList
    }

    private fun parseM3uLive(lines: List<String>, baseUrl: String): List<LiveChannelGroup> {
        val groupMap = linkedMapOf<String, MutableList<LiveChannel>>()
        var currentGroup = "默认分组"
        var currentName = ""
        var currentLogo: String? = null
        var currentTvgId: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            if (trimmed.startsWith("#EXTINF:")) {
                // 提取 group-title
                val groupMatcher = Regex("""group-title="([^"]+)"""").find(trimmed)
                if (groupMatcher != null) {
                    currentGroup = groupMatcher.groupValues[1]
                }
                // 提取 tvg-id，用于与 XMLTV 的 channel id 精确匹配
                val tvgIdMatcher = Regex("""tvg-id="([^"]+)"""").find(trimmed)
                currentTvgId = tvgIdMatcher?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
                // 提取 tvg-logo
                val logoMatcher = Regex("""tvg-logo="([^"]+)"""").find(trimmed)
                currentLogo = logoMatcher?.groupValues?.get(1)

                // 提取频道名字 (最后一个逗号之后)
                val lastComma = trimmed.lastIndexOf(',')
                currentName = if (lastComma != -1) trimmed.substring(lastComma + 1).trim() else "未知频道"
            } else if (!trimmed.startsWith("#")) {
                val playUrl = resolveRelativeUrl(baseUrl, trimmed)
                if (playUrl.startsWith("http")) {
                    val list = groupMap.getOrPut(currentGroup) { mutableListOf() }
                    list.add(
                        LiveChannel(
                            name = currentName.ifBlank { "频道 ${list.size + 1}" },
                            url = playUrl,
                            group = currentGroup,
                            logo = currentLogo,
                            tvgId = currentTvgId
                        )
                    )
                    currentTvgId = null
                    currentLogo = null
                }
            }
        }

        return groupMap.map { (group, channels) ->
            LiveChannelGroup(groupName = group, channels = channels)
        }
    }

    // ---------------------------------------------------------------------------------
    // EPG (XMLTV) 节目单
    // ---------------------------------------------------------------------------------

    /** 从 #EXTM3U 头提取 url-tvg / x-tvg-url，多个地址常用逗号分隔 */
    fun extractEpgUrls(lines: List<String>): List<String> {
        val header = lines.firstOrNull { it.trim().startsWith("#EXTM3U") } ?: return emptyList()
        return Regex("""(?:url-tvg|x-tvg-url)="([^"]+)"""").findAll(header)
            .flatMap { it.groupValues[1].split(',').asSequence() }
            .map { it.trim() }
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()
            .toList()
    }

    /**
     * 拉取并解析 XMLTV 节目单，返回「归一化 key -> 当天节目列表」。
     * 支持 .xml / .xml.gz（按 URL 后缀或 gzip magic 判断）。
     */
    suspend fun fetchEpg(epgUrl: String): Result<Map<String, List<EpgSlot>>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(epgUrl.trim())
                .header("User-Agent", DEFAULT_UA)
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("EPG 拉取失败: ${response.code}"))
            }
            val bytes = response.body?.bytes()
                ?: return@withContext Result.failure(Exception("EPG 内容为空"))
            if (bytes.isEmpty()) {
                return@withContext Result.success(emptyMap())
            }
            val isGzip = epgUrl.contains(".gz", ignoreCase = true) ||
                    (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            val inputStream = if (isGzip) {
                GZIPInputStream(ByteArrayInputStream(bytes))
            } else {
                ByteArrayInputStream(bytes)
            }
            val text = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            Result.success(parseXmlTv(text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * TVBox 标准「按频道名 + 日期查询」EPG 模板，例如：
     *   https://xxx/tvbox/epg?ch={name}&date={date}
     * 返回当天 JSON：{"epg_data":[{"start":"00:15","end":"00:46","title":"..."}]}
     * 与静态 XMLTV 不同，它必须逐频道请求，且时间只有 HH:mm，需按查询日补齐绝对毫秒。
     */
    suspend fun fetchChannelEpg(
        template: String,
        channelName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): Result<List<EpgSlot>> = withContext(Dispatchers.IO) {
        try {
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date(nowMillis))
            val encodedName = URLEncoder.encode(channelName.trim(), "UTF-8")
            val url = template.trim()
                .replace("{name}", encodedName, ignoreCase = true)
                .replace("{ch}", encodedName, ignoreCase = true)
                .replace("{date}", day, ignoreCase = true)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DEFAULT_UA)
                .build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("EPG 查询失败: ${response.code}"))
            }
            val body = response.body?.string()
                ?: return@withContext Result.failure(Exception("EPG 结果为空"))
            Result.success(parseEpgJson(body, nowMillis))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("MB-EPG", "请求异常: ${e.javaClass.simpleName} ${e.message}")
            Result.failure(e)
        }
    }

    private fun parseEpgJson(json: String, nowMillis: Long): List<EpgSlot> {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: return emptyList()
        val array = root.getAsJsonArray("epg_data") ?: return emptyList()
        val dayStart = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val oneDay = 24L * 60L * 60L * 1000L
        return array.mapNotNull { element ->
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            val title = runCatching { obj.get("title")?.asString }.getOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val start = runCatching { obj.get("start")?.asString }.getOrNull()
                ?: return@mapNotNull null
            val end = runCatching { obj.get("end")?.asString }.getOrNull() ?: start
            val startMin = hhmmToMinutes(start) ?: return@mapNotNull null
            val endMin = hhmmToMinutes(end) ?: return@mapNotNull null
            val s = dayStart + startMin * 60_000L
            var e = dayStart + endMin * 60_000L
            if (e <= s) e += oneDay   // 跨零点节目
            EpgSlot(title, s, e)
        }.distinctBy { it.startMillis }.sortedBy { it.startMillis }
    }

    /** "00:15" / "00:15:30" -> 当天第 N 分钟 */
    private fun hhmmToMinutes(raw: String): Long? {
        val parts = raw.trim().split(':')
        if (parts.size < 2) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        return h * 60L + m
    }

    private val epgTimeFormats = listOf(
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US),
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US),
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    )

    private fun parseEpgTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        for (format in epgTimeFormats) {
            try {
                val parsed = synchronized(format) { format.parse(raw.trim()) }
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
                // 换下一种格式
            }
        }
        return null
    }

    /** 逐事件扫描，避免 nextText 造成事件游标错位 */
    private fun parseXmlTv(xml: String): Map<String, List<EpgSlot>> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val idToDisplayName = mutableMapOf<String, String>()
        val slotsById = mutableMapOf<String, MutableList<EpgSlot>>()

        var inChannel = false
        var inProgramme = false
        var capturingDisplayName = false
        var capturingTitle = false
        var pendingId = ""
        var pendingChannel = ""
        var pendingStart = 0L
        var pendingStop = 0L
        var pendingTitle = ""

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                    "channel" -> {
                        inChannel = true
                        pendingId = parser.getAttributeValue(null, "id").orEmpty()
                    }
                    "display-name" -> if (inChannel) capturingDisplayName = true
                    "programme" -> {
                        inProgramme = true
                        pendingChannel = parser.getAttributeValue(null, "channel").orEmpty()
                        pendingStart = parseEpgTime(parser.getAttributeValue(null, "start")) ?: 0L
                        pendingStop = parseEpgTime(parser.getAttributeValue(null, "stop")) ?: 0L
                        pendingTitle = ""
                    }
                    "title" -> if (inProgramme) capturingTitle = true
                }
                XmlPullParser.TEXT -> {
                    if (capturingTitle && pendingTitle.isBlank()) {
                        pendingTitle = parser.text.trim()
                    }
                    if (capturingDisplayName && pendingId.isNotBlank() && idToDisplayName[pendingId] == null) {
                        parser.text.trim().takeIf { it.isNotBlank() }?.let { idToDisplayName[pendingId] = it }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name.lowercase(Locale.ROOT)) {
                    "title" -> capturingTitle = false
                    "display-name" -> capturingDisplayName = false
                    "channel" -> inChannel = false
                    "programme" -> {
                        if (pendingChannel.isNotBlank() && pendingStart > 0L &&
                            pendingStop > pendingStart && pendingTitle.isNotBlank()
                        ) {
                            slotsById.getOrPut(pendingChannel) { mutableListOf() }
                                .add(EpgSlot(pendingTitle, pendingStart, pendingStop))
                        }
                        inProgramme = false
                    }
                }
            }
            event = parser.next()
        }

        val result = mutableMapOf<String, List<EpgSlot>>()
        for ((id, slots) in slotsById) {
            val sorted = slots.distinctBy { it.startMillis }.sortedBy { it.startMillis }
            result[EpgPrograms.normalize(id)] = sorted
            idToDisplayName[id]?.let { name ->
                result.putIfAbsent(EpgPrograms.normalize(name), sorted)
            }
        }
        return result
    }

    /**
     * 从苹果 CMS (Type 0 或 Type 1) 采集源接口抓取影片列表
     */
    suspend fun fetchMacCmsVodList(
        api: String,
        categoryId: String? = null,
        page: Int = 1,
        keyword: String? = null,
        /** 整次调用（含连接/读取）的超时；跨站搜索需要它，否则一个卡死的站点能拖住整个搜索 */
        callTimeoutMs: Long? = null
    ): Result<MacCmsResponse> = withContext(Dispatchers.IO) {
        try {
            var fullUrl = api.trim()
            if (!fullUrl.contains("?")) {
                fullUrl += if (fullUrl.endsWith("/")) "?" else "/?"
            } else if (!fullUrl.endsWith("&") && !fullUrl.endsWith("?")) {
                fullUrl += "&"
            }

            fullUrl += "ac=videolist&pg=$page"
            if (!categoryId.isNullOrEmpty() && categoryId != "0" && categoryId != "全部") {
                fullUrl += "&t=$categoryId"
            }
            if (!keyword.isNullOrEmpty()) {
                fullUrl += "&wd=${keyword.trim()}"
            }

            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", DEFAULT_UA)
                .build()

            val client = if (callTimeoutMs != null && callTimeoutMs > 0) {
                // newBuilder 复用同一个 Dispatcher / 连接池，只收紧本次调用超时
                httpClient.newBuilder().callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()
            } else {
                httpClient
            }
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("请求采集源失败: ${response.code}"))
            }

            val body = response.body?.string() ?: ""
            val result = gson.fromJson(body, MacCmsResponse::class.java)
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从苹果 CMS 采集源拉取真实分类标签树 (ac=list 包含完整的 class 分类)
     */
    suspend fun fetchMacCmsCategories(api: String): Result<List<MacCmsCategory>> = withContext(Dispatchers.IO) {
        try {
            var fullUrl = api.trim()
            if (!fullUrl.contains("?")) {
                fullUrl += if (fullUrl.endsWith("/")) "?" else "/?"
            } else if (!fullUrl.endsWith("&") && !fullUrl.endsWith("?")) {
                fullUrl += "&"
            }
            fullUrl += "ac=list"

            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", DEFAULT_UA)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("请求分类失败: ${response.code}"))
            }

            val body = response.body?.string() ?: ""
            val result = gson.fromJson(body, MacCmsResponse::class.java)
            val categories = result.categories ?: emptyList()
            Result.success(categories)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 自动嗅探或提取网页播放器、分享链接中的真实 m3u8 直连流
     */
    suspend fun sniffRealStreamUrl(rawUrl: String): String = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        if (!trimmed.contains("/share/") && !trimmed.contains(".html") && !trimmed.contains("play")) {
            return@withContext trimmed
        }
        try {
            val request = Request.Builder()
                .url(trimmed)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; MobileBox) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                .header("Referer", trimmed)
                .build()

            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: return@withContext trimmed

            val m3u8Regex = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
            val match = m3u8Regex.find(html)
            if (match != null) {
                return@withContext match.groupValues[1]
            }

            val varUrlRegex = Regex("""var\s+url\s*=\s*['"]([^'"]+)['"]""")
            val varMatch = varUrlRegex.find(html)
            if (varMatch != null) {
                val candidate = varMatch.groupValues[1]
                if (candidate.startsWith("http")) return@withContext candidate
            }
        } catch (e: Exception) {
            // ignore
        }
        trimmed
    }

    /**
     * 将苹果 CMS 视频条目转换为 UI 播放器兼容的 VideoDetailData 结构
     */
    fun parseMacCmsVodToDetail(vod: MacCmsVod): VideoDetailData {
        val routes = mutableListOf<VideoRoute>()
        val playFromList = vod.vodPlayFrom?.split("$$$") ?: emptyList()
        val playUrlList = vod.vodPlayUrl?.split("$$$") ?: emptyList()

        for (i in playFromList.indices) {
            val fromName = playFromList.getOrNull(i)?.uppercase() ?: "线路 ${i + 1}"
            val urlData = playUrlList.getOrNull(i) ?: ""
            val episodeList = mutableListOf<VideoEpisode>()

            val epTokens = urlData.split("#")
            for ((epIdx, token) in epTokens.withIndex()) {
                val pair = token.split("$")
                if (pair.size >= 2) {
                    val epTitle = pair[0].trim()
                    val epUrl = pair[1].trim()
                    episodeList.add(
                        VideoEpisode(
                            episodeId = epIdx + 1,
                            name = epTitle,
                            streamUrl = epUrl
                        )
                    )
                } else if (token.isNotBlank()) {
                    episodeList.add(
                        VideoEpisode(
                            episodeId = epIdx + 1,
                            name = "正片",
                            streamUrl = token.trim()
                        )
                    )
                }
            }

            if (episodeList.isNotEmpty()) {
                routes.add(
                    VideoRoute(
                        routeId = "route_$i",
                        routeName = fromName,
                        tag = "直连",
                        episodes = episodeList
                    )
                )
            }
        }

        if (routes.isEmpty()) {
            routes.add(
                VideoRoute(
                    routeId = "route_default",
                    routeName = "默认线路",
                    tag = "直连",
                    episodes = listOf(
                        VideoEpisode(
                            episodeId = 1,
                            name = "正片",
                            streamUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
                        )
                    )
                )
            )
        }

        val parsedScore = vod.vodScore ?: "0.0"

        return VideoDetailData(
            id = vod.vodId.toString(),
            title = vod.vodName,
            subtitle = vod.vodRemarks ?: "更新至最新",
            rating = parsedScore,
            year = vod.vodYear ?: "2026",
            category = vod.typeName ?: "影视",
            tags = listOfNotNull(vod.typeName, vod.vodRemarks, "4K超清", "免嗅探"),
            description = vod.vodContent?.replace(Regex("<[^>]*>"), "")?.trim() ?: "暂无剧情简介",
            posterUrl = vod.vodPic ?: "",
            routes = routes,
            actor = vod.vodActor.orEmpty(),
            director = vod.vodDirector.orEmpty()
        )
    }
}
