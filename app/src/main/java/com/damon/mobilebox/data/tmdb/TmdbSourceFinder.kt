package com.damon.mobilebox.data.tmdb

import com.damon.mobilebox.data.tvbox.TvBoxParser
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.ui.player.VideoDetailData
import kotlin.math.abs

/**
 * 把 TMDB 条目映射回本地订阅源里「真能播」的那一条。
 *
 * 为什么需要它：首页轮播的内容来自 TMDB（趋势榜），只有 tmdbId，没有采集站的 `vod_id`。
 * 想播就必须拿中文片名回源站搜一次 —— 搜到的是源站的 VideoDetailData（带 routes），
 * 详情页再拿它当"播放底座"，展示层用 TMDB 的文案与图。
 *
 * 匹配必须严：源站搜索对长片名是模糊匹配，`maxByOrNull` 随便挑一条会把《流浪地球2》
 * 的线路挂到《流浪地球》上，用户点开就发现放错片。所以沿用 [TmdbMatcher] 那套硬底线：
 * **标题必须完全相同或包含**，模糊命中直接丢弃；同分时选集数多的（信息更全）。
 */
object TmdbSourceFinder {
    /** 与刮削同一套阈值：包含关系 30 + 年份接近 10 起才够 45。 */
    private const val ACCEPT_SCORE = 45

    /**
     * 先查**当前站点**（一个请求，快到几乎无感），没命中再并发搜全部站点。
     *
     * 不直接上 `searchAll`：它会同时打几十个站、8 秒封顶，而首页轮播的片子
     * 十有八九就在当前站里。全站兜底只在当前站确实没有时才付这个代价。
     */
    suspend fun find(meta: TmdbMeta): VideoDetailData? {
        val word = meta.title.trim()
        if (word.isBlank()) return null
        val want = TmdbMatcher.normalize(word)
        if (want.length < 2) return null
        val wantYear = meta.year.toIntOrNull()

        TvBoxRepository.selectedSite.value?.let { site ->
            val local = runCatching {
                TvBoxParser.fetchMacCmsVodList(
                    api = site.api,
                    page = 1,
                    keyword = word,
                    callTimeoutMs = 6_000
                ).getOrNull()?.list.orEmpty()
                    .map { TvBoxParser.parseMacCmsVodToDetail(it, site.name) }
            }.getOrDefault(emptyList())
            best(local, want, wantYear)?.let { return it }
        }

        return best(TvBoxRepository.searchAll(word).map { it.detail }, want, wantYear)
    }

    private fun best(
        candidates: List<VideoDetailData>,
        want: String,
        wantYear: Int?
    ): VideoDetailData? = candidates
        .filter { it.routes.isNotEmpty() }
        .mapNotNull { detail ->
            val score = scoreOf(detail, want, wantYear) ?: return@mapNotNull null
            Triple(detail, score, detail.routes.sumOf { route -> route.episodes.size })
        }
        .filter { (_, score, _) -> score >= ACCEPT_SCORE }
        .maxWithOrNull(
            compareBy<Triple<VideoDetailData, Int, Int>> { it.second }
                .thenBy { it.third }
        )
        ?.first

    private fun scoreOf(detail: VideoDetailData, want: String, wantYear: Int?): Int? {
        val name = TmdbMatcher.normalize(detail.title)
        if (name.isBlank()) return null
        var score = when {
            name == want -> 60
            name.contains(want) || want.contains(name) -> 30
            // 没有公共前缀近似这档：源站的「第二季」「剧场版」会被当成同一部。
            else -> return null
        }
        val year = TmdbMatcher.extractYear(detail.year)
        if (wantYear != null && year != null) {
            score += when {
                year == wantYear -> 25
                abs(year - wantYear) <= 1 -> 10
                else -> -10
            }
        }
        return score
    }
}
