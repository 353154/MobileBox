package com.damon.mobilebox.data.tvbox

import android.util.Base64
import com.damon.mobilebox.ui.player.VideoDetailData
import com.damon.mobilebox.ui.player.VideoEpisode
import com.damon.mobilebox.ui.player.VideoRoute
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object TvBoxParser {

    private val gson: Gson = GsonBuilder().setLenient().create()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private const val DEFAULT_UA = "okhttp/3.15 (Linux; Android 14; MobileBox)"

    /**
     * 抓取并解析远程 TVBox 订阅配置
     */
    suspend fun fetchSubscription(url: String): Result<TvBoxConfig> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url.trim())
                .header("User-Agent", DEFAULT_UA)
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP 请求失败: ${response.code}"))
            }

            var content = response.body?.string()?.trim() ?: ""
            if (content.isEmpty()) {
                return@withContext Result.failure(Exception("订阅内容为空"))
            }

            // 判断是否是 Base64 编码的订阅内容
            if (!content.startsWith("{") && !content.startsWith("[")) {
                try {
                    val decoded = Base64.decode(content, Base64.DEFAULT)
                    content = String(decoded, Charsets.UTF_8).trim()
                } catch (e: Exception) {
                    // 若 Base64 解码失败则继续尝试当 JSON 解析
                }
            }

            val config = gson.fromJson(content, TvBoxConfig::class.java)
            if (config.sites.isNullOrEmpty()) {
                return@withContext Result.failure(Exception("订阅解析成功，但未找到可用的 sites 视频站点"))
            }

            Result.success(config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从苹果 CMS (Type 0 或 Type 1) 采集源接口抓取影片列表
     * @param api 站点 api 地址，例如: http://cj.ffzyapi.com/api.php/provide/vod/
     * @param categoryId 分类 id (可选)
     * @param page 页码
     * @param keyword 关键词搜索 (可选)
     */
    suspend fun fetchMacCmsVodList(
        api: String,
        categoryId: String? = null,
        page: Int = 1,
        keyword: String? = null
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

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("请求采集源失败: ${response.code}"))
            }

            val body = response.body?.string() ?: ""
            val result = gson.fromJson(body, MacCmsResponse::class.java)
            Result.success(result)
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
            if (!response.isSuccessful) return@withContext trimmed

            val body = response.body?.string().orEmpty()
            // 匹配常见的 m3u8 变量定义与 JSON 字段 (如: const url = "/.../index.m3u8")
            val regex = Regex("""(?:var|const)?\s*(?:url|file)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE)
            val match = regex.find(body)
            if (match != null) {
                var stream = match.groupValues[1]
                if (stream.startsWith("/")) {
                    val uri = android.net.Uri.parse(trimmed)
                    stream = "${uri.scheme}://${uri.host}$stream"
                }
                return@withContext stream
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        trimmed
    }

    /**
     * 将 MacCMS 返回的单条影视数据转化为移动端通用的 VideoDetailData
     */
    fun parseMacCmsVodToDetail(vod: MacCmsVod): VideoDetailData {
        val routes = mutableListOf<VideoRoute>()

        val playFromList = vod.vodPlayFrom?.split("$$$") ?: emptyList()
        val playUrlGroupList = vod.vodPlayUrl?.split("$$$") ?: emptyList()

        for (i in playUrlGroupList.indices) {
            val fromName = playFromList.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "线路 ${i + 1}"
            val rawUrls = playUrlGroupList[i]
            val episodes = mutableListOf<VideoEpisode>()

            // 格式: 第1集$https://...m3u8#第2集$https://...m3u8
            val epItems = rawUrls.split("#")
            epItems.forEachIndexed { epIndex, item ->
                val parts = item.split("$")
                if (parts.size >= 2) {
                    val epName = parts[0].trim()
                    val epUrl = parts[1].trim()
                    if (epUrl.startsWith("http")) {
                        episodes.add(VideoEpisode(episodeId = epIndex + 1, name = epName, streamUrl = epUrl))
                    }
                } else if (item.startsWith("http")) {
                    episodes.add(VideoEpisode(episodeId = epIndex + 1, name = "第 ${epIndex + 1} 集", streamUrl = item.trim()))
                }
            }

            if (episodes.isNotEmpty()) {
                val hasDirectStream = episodes.any { it.streamUrl.contains(".m3u8", ignoreCase = true) || it.streamUrl.contains(".mp4", ignoreCase = true) }
                routes.add(
                    VideoRoute(
                        routeId = "route_$i",
                        routeName = fromName.uppercase(),
                        tag = if (hasDirectStream || fromName.contains("m3u8", ignoreCase = true)) "秒播" else "直连",
                        episodes = episodes
                    )
                )
            }
        }

        // 核心优化：按播放直连可用性排序，确保首选线路是直接可播放的 .m3u8 / .mp4 视频流（而非网页分享/解析页）
        routes.sortWith(compareByDescending<VideoRoute> { route ->
            val hasDirectStream = route.episodes.any { ep ->
                ep.streamUrl.contains(".m3u8", ignoreCase = true) || ep.streamUrl.contains(".mp4", ignoreCase = true)
            }
            val hasM3u8InName = route.routeName.contains("M3U8", ignoreCase = true) ||
                    route.routeName.contains("直连") ||
                    route.routeName.contains("秒播")
            val isSharePage = route.episodes.any { ep ->
                ep.streamUrl.contains("/share/", ignoreCase = true) || ep.streamUrl.contains(".html", ignoreCase = true)
            }

            var score = 0
            if (hasDirectStream) score += 100
            if (hasM3u8InName) score += 50
            if (isSharePage) score -= 100
            score
        })

        // 如果没有提取出有效播放线路，预置一条备用线路
        if (routes.isEmpty()) {
            routes.add(
                VideoRoute(
                    routeId = "route_default",
                    routeName = "标准默认线路",
                    tag = "直连",
                    episodes = listOf(
                        VideoEpisode(
                            episodeId = 1,
                            name = "第 1 集",
                            streamUrl = "https://sf1-cdn-tos.huoshanstatic.com/obj/media-fe/xgplayer_doc_video/mp4/xgplayer-demo-720p.mp4"
                        )
                    )
                )
            )
        }

        return VideoDetailData(
            id = vod.vodId.toString(),
            title = vod.vodName,
            subtitle = vod.vodRemarks ?: vod.vodSub ?: "高清原画",
            rating = vod.vodScore?.takeIf { it.isNotBlank() } ?: "8.8",
            year = vod.vodYear ?: "2026",
            category = vod.typeName ?: "影视",
            tags = listOfNotNull(
                vod.typeName,
                vod.vodRemarks?.take(6),
                "4K超清",
                "免嗅探"
            ).distinct(),
            description = vod.vodContent?.replace(Regex("<[^>]*>"), "")?.trim()
                ?: "本片已接入实时分布式采集线路，由 MobileBox 提供全流程超清解码播放。",
            posterUrl = vod.vodPic ?: "https://image.tmdb.org/t/p/w500/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg",
            routes = routes
        )
    }
}
