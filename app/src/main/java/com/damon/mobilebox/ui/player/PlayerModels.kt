package com.damon.mobilebox.ui.player

data class VideoEpisode(
    val episodeId: Int,
    val name: String,
    val streamUrl: String
)

data class VideoRoute(
    val routeId: String,
    val routeName: String,
    val tag: String,
    val episodes: List<VideoEpisode>,
    /** 这条线路所属的采集站（TVBox 订阅里的 site.name）。UI 用它做前缀，区分「哪个源」。 */
    val siteName: String = ""
)

/** 站点名去掉「(17万+高清海报)」这类营销后缀，避免撑爆 chip。 */
fun shortSiteName(name: String): String =
    name.substringBefore('(').substringBefore('（').trim()

/**
 * 播放源 chip 的统一文案：`站名·线路名 标签`。
 * 线路名本身是上游 `vod_play_from` 标识（FEIFAN/FFM3U8/…），不带站点信息，
 * 光看它用户无法判断来自哪个配置源，所以站名前缀在数据层就必须带上。
 */
fun VideoRoute.displayName(): String = buildString {
    val site = shortSiteName(siteName)
    if (site.isNotEmpty()) append(site).append('·')
    append(routeName)
    if (tag.isNotBlank()) append(' ').append(tag)
}

data class VideoDetailData(
    val id: String,
    val title: String,
    val subtitle: String,
    val rating: String,
    val year: String,
    val category: String,
    val tags: List<String>,
    val description: String,
    val posterUrl: String,
    val routes: List<VideoRoute>,
    val actor: String = "",
    val director: String = ""
) {
    companion object {
        // 全局国内官方高可用超高速 CDN 测试片源 (字节跳动火山引擎官方流，国内三网秒开)
        const val TEST_STREAM_URL = "https://sf1-cdn-tos.huoshanstatic.com/obj/media-fe/xgplayer_doc_video/mp4/xgplayer-demo-720p.mp4"

        fun createFromMovie(
            id: String,
            title: String,
            subtitle: String,
            rating: String,
            posterUrl: String
        ): VideoDetailData {
            // 所有集数与线路全部统一使用同一个高可用测试视频流，确保点入即播
            val episodesRoute1 = (1..12).map { ep ->
                VideoEpisode(
                    episodeId = ep,
                    name = "第 $ep 集",
                    streamUrl = TEST_STREAM_URL
                )
            }

            val episodesRoute2 = (1..12).map { ep ->
                VideoEpisode(
                    episodeId = ep,
                    name = "第 $ep 集 [4K]",
                    streamUrl = TEST_STREAM_URL
                )
            }

            return VideoDetailData(
                id = id,
                title = title,
                subtitle = subtitle,
                rating = rating,
                year = "2026",
                category = "影视精选",
                tags = listOf("4K 超清", "HDR10+", "杜比全景声", "免嗅探秒播"),
                description = "本片讲述了在全新时代背景下，主角历经重重考验与技术蜕变，最终完成自我救赎与突破的宏大故事。画质采用原生 4K 数字中间件调色，全流程杜比视界高动态呈现。",
                posterUrl = posterUrl,
                routes = listOf(
                    VideoRoute("route_speed", "极速秒播专线", "直连", episodesRoute1),
                    VideoRoute("route_4k", "4K 蓝光高码专区", "原画", episodesRoute2)
                )
            )
        }
    }
}
