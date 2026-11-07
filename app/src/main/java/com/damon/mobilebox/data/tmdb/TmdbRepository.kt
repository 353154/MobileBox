package com.damon.mobilebox.data.tmdb

import android.content.Context
import android.os.SystemClock
import com.google.gson.Gson

/**
 * 刮削编排：缓存 → 搜索 → 详情 → 熔断降级。
 *
 * 降级顺序由配置决定，但**任何一条路线失败都不会抛给 UI**：详情页拿不到就保持订阅源
 * 数据，不弹错误、不显示空态。刮削是增强，不是功能依赖。
 */
object TmdbRepository {
    private const val COOLDOWN_MS = 10L * 60 * 1000
    private const val LANGUAGE = "zh-CN"

    private val gson = Gson()

    fun init(context: Context) {
        TmdbCache.init(context)
    }

    // ---------------- 熔断 ----------------

    private class Health {
        @Volatile
        private var cooldownUntil = 0L

        fun available(): Boolean = SystemClock.elapsedRealtime() >= cooldownUntil

        fun trip() {
            cooldownUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS
        }
    }

    private val proxyHealth = Health()
    private val directHealth = Health()

    private fun healthOf(route: TmdbClient.Route): Health = when (route) {
        TmdbClient.Route.PROXY -> proxyHealth
        TmdbClient.Route.DIRECT -> directHealth
    }

    // ---------------- 对外入口 ----------------

    suspend fun enrich(
        title: String,
        yearText: String,
        typeHint: String,
        subtitle: String = ""
    ): TmdbMeta? {
        val query = TmdbMatcher.normalize(title)
        if (query.length < 2) return null
        val year = TmdbMatcher.extractYear(yearText)
        val type = TmdbMatcher.guessType(typeHint, subtitle)

        val primaryKey = queryKey(query, year, type)
        android.util.Log.d(
            "MB-TMDB",
            "enrich title=$title year=$yearText type=$type query=$query"
        )
        TmdbCache.get(primaryKey)?.let {
            android.util.Log.d("MB-TMDB", "cache hit id=${it.tmdbId} score=${it.score}")
            return it
        }
        if (TmdbCache.isMiss(primaryKey)) {
            android.util.Log.d("MB-TMDB", "negative cache hit $primaryKey")
            return null
        }

        when (val primary = searchOnce(query, year, type)) {
            is SearchOutcome.Found -> {
                android.util.Log.d("MB-TMDB", "matched($type) \"${primary.meta.title}\" id=${primary.meta.tmdbId}")
                return primary.meta
            }
            SearchOutcome.NetworkFailed -> {
                android.util.Log.w("MB-TMDB", "network failed, skip scraping (no negative cache)")
                return null
            }
            SearchOutcome.NoMatch -> Unit
        }

        // 第二轮：类型推断不可靠（「影视」「片库」这类分类名没有任何类型信息），换另一种类型补一次。
        val altType = if (type == "tv") "movie" else "tv"
        val altKey = queryKey(query, year, altType)
        TmdbCache.get(altKey)?.let {
            TmdbCache.put(primaryKey, it)
            return it
        }
        when (val alt = searchOnce(query, year, altType)) {
            is SearchOutcome.Found -> {
                TmdbCache.put(altKey, alt.meta)
                TmdbCache.put(primaryKey, alt.meta)
                return alt.meta
            }
            // 网络中断不记负缓存：联网后应重试，而不是 7 天内都当作没有这部片。
            SearchOutcome.NetworkFailed -> return null
            SearchOutcome.NoMatch -> Unit
        }

        // 第三轮：不限类型的全域搜索。前两轮皆空时，原因往往不是「TMDB 没有这部」，
        // 而是类型判错 —— 综艺、纪录片在源里常被分到电影类，multi 不做类型约束能救回来。
        val multiKey = queryKey(query, year, "multi")
        TmdbCache.get(multiKey)?.let {
            TmdbCache.put(primaryKey, it)
            return it
        }
        return when (val m = searchOnce(query, year, "multi")) {
            is SearchOutcome.Found -> {
                TmdbCache.put(multiKey, m.meta)
                TmdbCache.put(primaryKey, m.meta)
                m.meta
            }
            SearchOutcome.NetworkFailed -> null
            SearchOutcome.NoMatch -> {
                TmdbCache.putMiss(primaryKey)
                TmdbCache.putMiss(multiKey)
                android.util.Log.d("MB-TMDB", "no match in tv/movie/multi: \"$query\"")
                null
            }
        }
    }

    // ---------------- 搜索 ----------------

    private sealed class SearchOutcome {
        data class Found(val meta: TmdbMeta) : SearchOutcome()
        data object NoMatch : SearchOutcome()
        data object NetworkFailed : SearchOutcome()
    }

    private fun queryKey(query: String, year: Int?, type: String) = "$query|${year ?: 0}|$type"

    private suspend fun searchOnce(
        query: String,
        year: Int?,
        type: String
    ): SearchOutcome {
        val params = mutableMapOf(
            "query" to query,
            "language" to LANGUAGE,
            "include_adult" to "false"
        )
        // 故意不传 year / first_air_date_year：TMDB 的年份过滤是精确匹配，
        // 而源标注的年份（「更新至2026」「2025」）与 TMDB 首播年经常差 1~2 年，
        // 一旦不符就直接返回空数组 —— 实测「你好时光」带 2026 是 0 条、不带年份立刻命中。
        // 年份改由 TmdbMatcher.score 参与打分，这里只负责把候选捞回来。

        val path = if (type == "multi") "/3/search/multi" else "/3/search/$type"
        val body = request(path, params) ?: return SearchOutcome.NetworkFailed
        val parsed = runCatching { gson.fromJson(body, TmdbSearchResponse::class.java) }
            .getOrNull() ?: return SearchOutcome.NoMatch
        val candidates = parsed.results.orEmpty()
            .filter { it.id > 0 && it.mediaType != "person" }
        if (candidates.isEmpty()) return SearchOutcome.NoMatch

        val best = candidates.maxByOrNull { TmdbMatcher.score(query, year, it) }
            ?: return SearchOutcome.NoMatch
        val score = TmdbMatcher.score(query, year, best)
        android.util.Log.d(
            "MB-TMDB",
            "search $type \"$query\" -> ${candidates.size} candidates, best=\"${best.displayTitle}\" id=${best.id} score=$score/${TmdbMatcher.ACCEPT_SCORE}"
        )
        if (score < TmdbMatcher.ACCEPT_SCORE) return SearchOutcome.NoMatch

        // 全域搜索（/search/multi）不区分 movie/tv，用条目自带的 media_type 决定详情该打哪个端点。
        val effectiveType = if (type == "multi") (best.mediaType ?: "movie") else type

        // 搜索结果不含类型名（genres 只有 genre_ids），必须再打一次详情；
        // 详情失败不影响主流程，用搜索结果的字段兜底。
        val detail = fetchDetail(best.id, effectiveType)

        return SearchOutcome.Found(
            TmdbMeta(
                tmdbId = best.id,
                mediaType = effectiveType,
                title = best.displayTitle,
                overview = detail?.overview?.takeIf { it.isNotBlank() }
                    ?: best.overview.orEmpty(),
                voteAverage = detail?.voteAverage ?: best.voteAverage ?: 0.0,
                voteCount = detail?.voteCount ?: best.voteCount ?: 0,
                year = (detail?.releaseDate ?: detail?.firstAirDate).orEmpty().take(4)
                    .ifBlank { best.yearText },
                genres = detail?.genres.orEmpty()
                    .mapNotNull { TmdbGenreZh.of(it.name).takeIf(String::isNotBlank) },
                posterUrl = TmdbImage.poster(detail?.posterPath ?: best.posterPath),
                backdropUrl = TmdbImage.backdrop(detail?.backdropPath ?: best.backdropPath),
                cast = pickCast(detail),
                stills = pickStills(detail),
                score = score,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun fetchDetail(tmdbId: Int, type: String): TmdbDetailResponse? {
        // 一次请求把详情 + 剧照 + 演职员一起拿回来：append_to_response 不额外计费，
        // 分成多次请求反而让熔断判断变复杂、详情页等待更长。
        // 预告片已按用户要求整体去掉（TMDB 只给 YouTube 链接，国内是 SNI 阻断），
        // 因此不再带 videos 与 include_video_language。
        val body = request(
            "/3/$type/$tmdbId",
            mapOf(
                "language" to LANGUAGE,
                "append_to_response" to "images,credits",
                // 只留中文/英文/无语言的图，否则日韩版海报会挤掉可用的剧照。
                "include_image_language" to "zh,en,null"
            )
        ) ?: return null
        return runCatching { gson.fromJson(body, TmdbDetailResponse::class.java) }.getOrNull()
    }

    // ---------------- 剧照 / 演职人员 ----------------

    /**
     * 演职人员：导演（剧集用 created_by 的「主创」）排前，其后按 TMDB 戏份顺序的演员。
     *
     * 裁到 12 条的理由：横滑一屏最多看 6~7 个头像，再多用户也不会划完；
     * 而缓存是 500 条上限的 JSON，每人一条含头像 URL，不裁会让文件白白膨胀。
     * 姓名去重，因为 TMDB 会把同一人按不同部门重复返回。
     */
    private fun pickCast(detail: TmdbDetailResponse?): List<TmdbPerson> {
        val credits = detail?.credits ?: return emptyList()
        val directors = credits.crew.orEmpty()
            .filter { it.job == "Director" }
            .mapNotNull { crew ->
                crew.name?.takeIf(String::isNotBlank)
                    ?.let { TmdbPerson(it, "导演", TmdbImage.profile(crew.profilePath)) }
            }
        // 剧集没有 Director 这个 crew 职位，主创信息在详情顶层的 created_by 上。
        val creators = detail.createdBy.orEmpty()
            .mapNotNull { crew ->
                crew.name?.takeIf(String::isNotBlank)
                    ?.let { TmdbPerson(it, "主创", TmdbImage.profile(crew.profilePath)) }
            }
        val actors = credits.cast.orEmpty()
            .sortedBy { it.order }
            .mapNotNull { member ->
                member.name?.takeIf(String::isNotBlank)?.let {
                    TmdbPerson(
                        name = it,
                        // 角色名为空时显示「演员」，避免头像下面留一条空白
                        role = member.character?.trim().orEmpty().ifBlank { "演员" },
                        profileUrl = TmdbImage.profile(member.profilePath)
                    )
                }
            }
        val leads = (directors + creators).distinctBy { it.name }.take(2)
        val leadNames = leads.mapTo(HashSet()) { it.name }
        val rest = actors.distinctBy { it.name }.filter { it.name !in leadNames }
        return (leads + rest).take(12)
    }

    /** 剧照取全部横版大图。宽度不足 780 的都算缩略图，铺满卡片会糊。 */
    private fun pickStills(detail: TmdbDetailResponse?): List<String> =
        detail?.images?.backdrops.orEmpty()
            .filter { (it.width ?: 0) >= 780 }
            .mapNotNull { TmdbImage.still(it.filePath).takeIf(String::isNotBlank) }
            .distinct()

    // ---------------- 路线编排 ----------------

    /**
     * 路线顺序：先直连官方 API，失败再退到内置 Worker。
     *
     * 直连是主路：`api.themoviedb.org` 在国内只被 DNS 污染、链路本身通畅，
     * [TmdbDns] 提供真实 A 记录后实测 0.7 秒可达，不需要用户挂代理。
     * Worker 是后备：`workers.dev` 是 SNI 阻断，只有开了代理才通。
     */
    private fun routes(): List<TmdbClient.Route> =
        listOf(TmdbClient.Route.DIRECT, TmdbClient.Route.PROXY)
            .filter { TmdbClient.isRouteConfigured(it) }

    /**
     * 按路线顺序请求，命中即返回明文 JSON。
     *
     * 熔断规则：
     * - IO 异常 / 429 / 5xx → 该路线冷却 10 分钟，换下一条；
     * - 401 / 403 → 内置 token 失效（或 Worker 侧 token 被撤），标记后立即终止，
     *   因为两条路线用的是同一份凭据，换路线也是同一个结果；
     * - 全部路线都在冷却 → 半开一次，只试第一条，避免网络恢复后要等满 10 分钟。
     */
    private suspend fun request(path: String, params: Map<String, String>): String? {
        if (!TmdbConfig.isConfigured || TmdbConfig.tokenInvalid) return null

        val routes = routes()
        if (routes.isEmpty()) return null
        val usable = routes.filter { healthOf(it).available() }
        val attempts = if (usable.isNotEmpty()) usable else routes.take(1)

        for (route in attempts) {
            when (val outcome = TmdbClient.get(route, path, params, TmdbConfig.ACCESS_TOKEN)) {
                is HttpOutcome.Success -> return outcome.body
                is HttpOutcome.Failure -> {
                    if (outcome.isAuthError) {
                        TmdbConfig.markInvalid()
                        android.util.Log.w("MB-TMDB", "token rejected (${outcome.code}), scraping disabled")
                        return null
                    }
                    if (outcome.isRetryableElsewhere) {
                        healthOf(route).trip()
                        continue
                    }
                    return null
                }
            }
        }
        return null
    }
}
