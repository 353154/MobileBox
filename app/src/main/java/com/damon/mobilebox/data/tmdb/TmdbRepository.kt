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
        HomeCache.init(context)
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

    // ---------------- 首页轮播 ----------------

    private const val TRENDING_TTL_MS = 60L * 60 * 1000
    private const val ID_INDEX_MAX = 60

    /**
     * 内存索引：`movie:123` → 完整 meta。
     *
     * 首页轮播点进详情只需要 tmdbId，但详情页要标题 / 简介 / 演职员 / 剧照一整套。
     * 轮播本身就来自本进程的请求，顺手把整份 meta 留在内存里，进详情时零请求、
     * 首帧就有内容；只有进程被杀后冷启动直达详情页才会走 [load] 重新拉。
     * 用 LRU 上限 60 条：一屏轮播最多 8 条，翻两次也到不了上限。
     */
    private val idIndex = object : java.util.LinkedHashMap<String, TmdbMeta>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TmdbMeta>?): Boolean =
            size > ID_INDEX_MAX
    }

    private fun indexKey(mediaType: String, tmdbId: Int) = "$mediaType:$tmdbId"

    private fun remember(meta: TmdbMeta): TmdbMeta {
        synchronized(idIndex) { idIndex[indexKey(meta.mediaType, meta.tmdbId)] = meta }
        return meta
    }

    @Volatile
    private var trendingAt = 0L

    @Volatile
    private var trendingList: List<TmdbMeta> = emptyList()

    /**
     * 首页 Hero 轮播：TMDB 本周趋势。
     *
     * 只留同时有 `backdrop_path` 与 `overview` 的条目 —— 轮播是大图 + 文案叠加，
     * 缺其中任何一个都会空出半屏。标题去重（趋势榜常有同名的电影与剧集重剪版）。
     *
     * 只有 `media_type` / `vote_average` / `genre_ids`（未翻译），所以**不带类型标签**，
     * 底部信息行只显示「电影 / 剧集 · 年份 · 评分」。类型名要额外打五次详情才能拿到，
     * 为了几行小字多花五倍请求不划算；详情页会补上。
     */
    suspend fun trending(limit: Int = 8, force: Boolean = false): List<TmdbMeta> {
        if (!force && trendingList.isNotEmpty() && SystemClock.elapsedRealtime() - trendingAt < TRENDING_TTL_MS) {
            return trendingList.take(limit)
        }
        val body = request("/3/trending/all/week", mapOf("language" to LANGUAGE))
            ?: return trendingList.take(limit)
        val parsed = runCatching { gson.fromJson(body, TmdbSearchResponse::class.java) }.getOrNull()
        val items = parsed?.results.orEmpty()
            .filter { item ->
                item.id > 0 &&
                    (item.mediaType == "movie" || item.mediaType == "tv") &&
                    !item.backdropPath.isNullOrBlank() &&
                    !item.overview.isNullOrBlank()
            }
            .distinctBy { it.displayTitle.trim() }
            .take(limit)
            .map { item ->
                val type = item.mediaType ?: "movie"
                TmdbMeta(
                    tmdbId = item.id,
                    mediaType = type,
                    title = item.displayTitle.trim(),
                    overview = item.overview.orEmpty().trim(),
                    voteAverage = item.voteAverage ?: 0.0,
                    voteCount = item.voteCount ?: 0,
                    year = item.yearText,
                    posterUrl = TmdbImage.poster(item.posterPath),
                    backdropUrl = TmdbImage.backdrop(item.backdropPath),
                    updatedAt = System.currentTimeMillis()
                )
            }
        if (items.isNotEmpty()) {
            items.forEach { remember(it) }
            trendingList = items
            trendingAt = SystemClock.elapsedRealtime()
            android.util.Log.d("MB-TMDB", "trending -> ${items.size} items")
        }
        return items
    }

    // ---------------- 首页横滑榜单 ----------------

    private const val ROW_TTL_MS = 30L * 60 * 1000

    private class RowEntry(val at: Long, val items: List<TmdbMeta>)

    /** 以请求路径为 key 的 30 分钟内存缓存。首页四行最多四个 key，不需要淘汰策略。 */
    private val rowCache = java.util.concurrent.ConcurrentHashMap<String, RowEntry>()

    /**
     * 首页横滑行数据：TMDB 现成的榜单接口（正在热映 / 正在播出 / 高分佳作）。
     *
     * 与 [trending] 的差别：这里**必须有竖版海报**（横滑是海报墙，缺图的卡片会露出空槽），
     * 简介允许为空（卡片不展示简介）。失败时返回上一次的缓存而不是空列表，
     * 免得一次网络抖动把整屏内容清空。
     */
    private suspend fun list(
        path: String,
        mediaType: String,
        limit: Int,
        force: Boolean,
        /**
         * 额外 query。分类页的 `discover` 要用 `with_genres` / `sort_by`，其余接口传空。
         * **必须进缓存 key**，否则「动作」和「喜剧」会共用同一份缓存。
         */
        extra: Map<String, String> = emptyMap()
    ): List<TmdbMeta> {
        val key = buildString {
            append(path).append('|').append(limit)
            extra.toSortedMap().forEach { (k, v) -> append('|').append(k).append('=').append(v) }
        }
        if (!force) {
            rowCache[key]?.let {
                if (SystemClock.elapsedRealtime() - it.at < ROW_TTL_MS) return it.items
            }
        }
        val params = mapOf("language" to LANGUAGE, "page" to "1") + extra
        val body = request(path, params)
            ?: return rowCache[key]?.items.orEmpty()
        val parsed = runCatching { gson.fromJson(body, TmdbSearchResponse::class.java) }.getOrNull()
        val items = parsed?.results.orEmpty()
            .filter { it.id > 0 && !it.posterPath.isNullOrBlank() }
            .mapNotNull { item ->
                val title = item.displayTitle.trim()
                if (title.isEmpty()) return@mapNotNull null
                TmdbMeta(
                    tmdbId = item.id,
                    mediaType = mediaType,
                    title = title,
                    overview = item.overview.orEmpty().trim(),
                    voteAverage = item.voteAverage ?: 0.0,
                    voteCount = item.voteCount ?: 0,
                    year = item.yearText,
                    posterUrl = TmdbImage.poster(item.posterPath),
                    backdropUrl = TmdbImage.backdrop(item.backdropPath),
                    updatedAt = System.currentTimeMillis()
                )
            }
            .distinctBy { it.title }
            .take(limit)
        if (items.isNotEmpty()) {
            items.forEach { remember(it) }
            rowCache[key] = RowEntry(SystemClock.elapsedRealtime(), items)
        }
        return items
    }

    /** 院线在映（含点映），国内源站覆盖最差，但海报墙最好看。 */
    suspend fun nowPlaying(limit: Int = 14, force: Boolean = false): List<TmdbMeta> =
        list("/3/movie/now_playing", "movie", limit, force)

    /** 本周在播的剧集（不是「今日播出」，更新节奏更稳）。 */
    suspend fun onTheAir(limit: Int = 14, force: Boolean = false): List<TmdbMeta> =
        list("/3/tv/on_the_air", "tv", limit, force)

    /** 影史高分，用来兜住「最近没好片」时的页面观感。 */
    suspend fun topRated(limit: Int = 14, force: Boolean = false): List<TmdbMeta> =
        list("/3/movie/top_rated", "movie", limit, force)

    // ---------------- 分类页：类型表 + 按类型拉片 ----------------

    private const val GENRE_TTL_MS = 24L * 60 * 60 * 1000

    private class GenreEntry(val at: Long, val items: List<TmdbGenre>)

    /** key = mediaType。类型表一天内不会变，24 小时够用；不需要淘汰。 */
    private val genreCache = java.util.concurrent.ConcurrentHashMap<String, GenreEntry>()

    /**
     * 类型表（动作 / 喜剧 / 科幻 …），走 `/3/genre/{movie|tv}/list?language=zh-CN`。
     *
     * 剧集有少数类型 TMDB 不给中文（`Sci-Fi & Fantasy` 之类），由 [TmdbGenreZh] 兜底翻译；
     * 不在映射表里的原样返回，宁可显示英文也不吞掉一行内容。
     *
     * 失败返回空表（不是上一次的缓存）：调用方据此显示「加载失败」，而不是给一屏空行。
     */
    internal suspend fun genres(mediaType: String, force: Boolean = false): List<TmdbGenre> {
        if (!force) {
            genreCache[mediaType]?.let {
                if (SystemClock.elapsedRealtime() - it.at < GENRE_TTL_MS) return it.items
            }
        }
        val body = request("/3/genre/$mediaType/list", mapOf("language" to LANGUAGE))
            ?: return genreCache[mediaType]?.items.orEmpty()
        val parsed = runCatching {
            gson.fromJson(body, TmdbGenreListResponse::class.java)
        }.getOrNull()
        val items = parsed?.genres.orEmpty().mapNotNull { g ->
            val name = TmdbGenreZh.of(g.name)
            if (g.id <= 0 || name.isEmpty()) null else TmdbGenre(g.id, name)
        }
        if (items.isNotEmpty()) {
            genreCache[mediaType] = GenreEntry(SystemClock.elapsedRealtime(), items)
        }
        return items
    }

    /**
     * 某个类型下的热门片：`discover` + `sort_by=popularity.desc`。
     *
     * `vote_count.gte=50` 不能省 —— 不带它，「科幻」第一屏会是一堆刚上线、0 分的新片，
     * 海报墙立刻变得不能看。`include_adult=false` 与 TMDB 自己的默认值一致，写明更明确。
     *
     * 复用 [list] 的 30 分钟缓存：key 里带了 `with_genres`，不同类型互不覆盖。
     */
    suspend fun discover(
        mediaType: String,
        genreId: Int,
        limit: Int = 14,
        force: Boolean = false
    ): List<TmdbMeta> = list(
        path = "/3/discover/$mediaType",
        mediaType = mediaType,
        limit = limit,
        force = force,
        extra = mapOf(
            "with_genres" to genreId.toString(),
            "sort_by" to "popularity.desc",
            "vote_count.gte" to "50",
            "include_adult" to "false"
        )
    )

    /** 轮播点进详情时先用内存里的那份，命中就不必再等网络。 */
    fun peek(mediaType: String, tmdbId: Int): TmdbMeta? =
        synchronized(idIndex) { idIndex[indexKey(mediaType, tmdbId)] }

    /** 内存没有（冷启动直达）才按 id 拉一次完整详情。失败返回 null，不抛给 UI。 */
    suspend fun load(mediaType: String, tmdbId: Int): TmdbMeta? {
        val cached = peek(mediaType, tmdbId)
        // 轮播那份来自 /trending，只有标题 / 图 / 简介 / 评分，**没有演职员和剧照**。
        // 所以「命中就不请求」是错的：详情页要用这两块，缺了必须补一次完整详情。
        if (cached != null && (cached.cast.isNotEmpty() || cached.stills.isNotEmpty())) return cached
        val detail = fetchDetail(tmdbId, mediaType) ?: return cached
        val title = detail.title ?: detail.name.orEmpty()
        if (title.isBlank()) return cached
        val logo = pickLogo(detail.images)
        return remember(
            TmdbMeta(
                tmdbId = tmdbId,
                mediaType = mediaType,
                title = title,
                overview = detail.overview.orEmpty().trim(),
                voteAverage = detail.voteAverage ?: 0.0,
                voteCount = detail.voteCount ?: 0,
                year = (detail.releaseDate ?: detail.firstAirDate).orEmpty().take(4),
                genres = detail.genres.orEmpty()
                    .mapNotNull { TmdbGenreZh.of(it.name).takeIf(String::isNotBlank) },
                posterUrl = TmdbImage.poster(detail.posterPath),
                backdropUrl = TmdbImage.backdrop(detail.backdropPath),
                logoUrl = logo?.first.orEmpty(),
                logoAspect = logo?.second ?: 0f,
                cast = pickCast(detail),
                stills = pickStills(detail),
                updatedAt = System.currentTimeMillis()
            )
        )
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
            remember(
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
                    ?.let {
                        TmdbPerson(
                            name = it,
                            role = "导演",
                            profileUrl = TmdbImage.profile(crew.profilePath),
                            personId = crew.id
                        )
                    }
            }
        // 剧集没有 Director 这个 crew 职位，主创信息在详情顶层的 created_by 上。
        val creators = detail.createdBy.orEmpty()
            .mapNotNull { crew ->
                crew.name?.takeIf(String::isNotBlank)
                    ?.let {
                        TmdbPerson(
                            name = it,
                            role = "主创",
                            profileUrl = TmdbImage.profile(crew.profilePath),
                            personId = crew.id
                        )
                    }
            }
        val actors = credits.cast.orEmpty()
            .sortedBy { it.order }
            .mapNotNull { member ->
                member.name?.takeIf(String::isNotBlank)?.let {
                    TmdbPerson(
                        name = it,
                        // 角色名为空时显示「演员」，避免头像下面留一条空白
                        role = member.character?.trim().orEmpty().ifBlank { "演员" },
                        profileUrl = TmdbImage.profile(member.profilePath),
                        personId = member.id
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

    // ---------------- 片名 logo（透明 PNG） ----------------

    /** logo 选中结果：URL + 宽高比。宽高比用来在 UI 里定宽，避免拉伸。 */
    private class Logo(val url: String, val aspect: Float)

    /**
     * 从 `images.logos` 里挑一张。
     *
     *  · 语言优先级 zh > en > 无语言（纯图形标识）> 其它；
     *  · **只收 `.png`**：TMDB 的 logos 里混着 `.svg`，Coil 没有 SVG 解码器，收进来就是空白；
     *  · 同语言取最宽的一张（分辨率越高越清楚）；
     *  · 宽高比 < 1.1 的竖版 / 方形 logo 直接跳过 —— 贴在海报左下角会变成一个矮方块。
     */
    private fun pickLogo(images: TmdbImages?): Pair<String, Float>? {
        val logos = images?.logos.orEmpty().filter {
            val path = it.filePath
            !path.isNullOrBlank() &&
                path.endsWith(".png", ignoreCase = true) &&
                (it.aspectRatio ?: 0.0) >= 1.1
        }
        if (logos.isEmpty()) return null
        val rank = { lang: String? ->
            when (lang) {
                "zh" -> 0
                "en" -> 1
                null, "" -> 2
                else -> 3
            }
        }
        val best = logos.minWithOrNull(
            compareBy({ rank(it.language) }, { -(it.width ?: 0) })
        ) ?: return null
        val url = TmdbImage.logo(best.filePath)
        if (url.isBlank()) return null
        return url to (best.aspectRatio ?: 0.0).toFloat()
    }

    /** 每个条目的 logo 只查一次（含「确实没有」的负结果），进程内永久缓存。 */
    private val logoCache = java.util.concurrent.ConcurrentHashMap<String, Logo>()

    /**
     * 给单条轮播补片名 logo，查到就返回新对象、没有就原样返回。
     *
     * 轮播数据来自 `/trending`（列表接口，**不带 images**），所以只能按 id 单独查
     * `/3/{type}/{id}/images`。**刻意只做单条**：调用方并发跑八条，谁先回来谁先换，
     * 不攒批 —— 原来 `withLogos` 要等最慢的一条，片名（尤其首屏那一条）就得陪着一起等。
     */
    suspend fun logoOf(meta: TmdbMeta): TmdbMeta {
        if (meta.logoUrl.isNotBlank()) return meta
        val key = indexKey(meta.mediaType, meta.tmdbId)
        val logo = logoCache[key]
            ?: fetchLogo(meta.mediaType, meta.tmdbId).also { logoCache[key] = it }
        return if (logo.url.isBlank()) meta
        else meta.copy(logoUrl = logo.url, logoAspect = logo.aspect)
    }

    private suspend fun fetchLogo(mediaType: String, tmdbId: Int): Logo {
        val body = request(
            "/3/$mediaType/$tmdbId/images",
            mapOf("include_image_language" to "zh,en,null")
        ) ?: return Logo("", 0f)
        val parsed = runCatching { gson.fromJson(body, TmdbImages::class.java) }.getOrNull()
        val pick = pickLogo(parsed) ?: return Logo("", 0f)
        return Logo(pick.first, pick.second)
    }

    // ---------------- 演员作品年表 ----------------

    private const val PERSON_TTL_MS = 6L * 60 * 60 * 1000
    /** 年表上限： prolific 演员的 combined_credits 动辄两三白条，整页滚不完也没意义。 */
    private const val PERSON_WORK_LIMIT = 60

    private class PersonEntry(val at: Long, val profile: TmdbPersonProfile)

    /** 进程内缓存。详情页点击时已经查过一次，时间轴页命中即首帧有数据。 */
    private val personCache = java.util.concurrent.ConcurrentHashMap<String, PersonEntry>()

    /** 姓名 → personId：订阅源文本没有 id，按姓名搜过一次就别再搜。 */
    private val personNameIndex = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * 查一位演职人员的作品年表。
     *
     * 有 personId（TMDB credits 带出来的）直接按 id 拉；没有（订阅源 actor 文本兜底）
     * 走 `/3/search/person` 找人。**失败或没有作品一律返回 null**，调用方据此「不展开」时间轴，
     * 与详情页的刮削降级口径一致：TMDB 是增强，不是功能依赖。
     */
    suspend fun personProfile(personId: Int, name: String): TmdbPersonProfile? {
        val cleanName = name.trim()
        if (personId <= 0 && cleanName.isEmpty()) return null
        val key = if (personId > 0) "id:$personId" else "name:$cleanName"
        personCache[key]?.let {
            if (SystemClock.elapsedRealtime() - it.at < PERSON_TTL_MS) return it.profile
        }

        val resolvedId = if (personId > 0) personId
        else personNameIndex[cleanName] ?: searchPerson(cleanName)
            ?.also { personNameIndex[cleanName] = it }
        if (resolvedId == null || resolvedId <= 0) return null

        // combined_credits 一次拿到电影 + 剧集的全部参演/幕后记录，external_ids 给社交账号。
        val body = request(
            "/3/person/$resolvedId",
            mapOf(
                "language" to LANGUAGE,
                "append_to_response" to "combined_credits,external_ids"
            )
        ) ?: return null
        val detail = runCatching {
            gson.fromJson(body, TmdbPersonDetailResponse::class.java)
        }.getOrNull() ?: return null

        val works = buildWorks(detail)
        if (works.isEmpty()) return null

        val profile = TmdbPersonProfile(
            personId = resolvedId,
            name = detail.name?.trim().orEmpty().ifBlank { cleanName },
            profileUrl = TmdbImage.profile(detail.profilePath),
            department = detail.department.orEmpty(),
            twitterId = detail.externalIds?.twitterId?.trim().orEmpty(),
            instagramId = detail.externalIds?.instagramId?.trim().orEmpty(),
            works = works
        )
        personCache[key] = PersonEntry(SystemClock.elapsedRealtime(), profile)
        android.util.Log.d("MB-TMDB", "person ${profile.name} -> ${works.size} works")
        return profile
    }

    /** 时间轴页直达时先看缓存（详情点击那次已经填好了）。 */
    fun peekPerson(personId: Int): TmdbPersonProfile? =
        personCache["id:$personId"]?.profile

    /**
     * 按姓名找人：同名时优先「姓名完全一致 → 表演部门 → 热度」，避免搜出一个同名幕后。
     * 只取第一个，年表页不做选人 UI —— 用户点的是某部片里的这个人，不该再让他挑一次。
     */
    private suspend fun searchPerson(name: String): Int? {
        val body = request(
            "/3/search/person",
            mapOf("language" to LANGUAGE, "query" to name, "page" to "1")
        ) ?: return null
        val parsed = runCatching {
            gson.fromJson(body, TmdbPersonSearchResponse::class.java)
        }.getOrNull() ?: return null
        return parsed.results.orEmpty()
            .filter { it.id > 0 }
            .maxWithOrNull(
                compareBy<TmdbPersonSearchItem> {
                    if (it.name?.trim().equals(name, ignoreCase = true)) 1 else 0
                }
                    .thenBy { if (it.department == "Acting") 1 else 0 }
                    .thenBy { it.popularity ?: 0.0 }
            )?.id
    }

    /**
     * cast + 主要 crew 合成时间轴。
     *
     * 同一部片既演又导会在 combined_credits 里出现两条，按 `type:id` 合并成一条、
     * 身份并列（截图里的「导演 · Writer · 制作人」就是这个效果）。日期按 ISO 字符串倒序，
     * 无日期的排最后；票数很少的评分由 UI 决定是否显示，这里只原样保留。
     */
    private fun buildWorks(detail: TmdbPersonDetailResponse): List<TmdbPersonWork> {
        val cast = detail.combinedCredits?.cast.orEmpty()
        val crew = detail.combinedCredits?.crew.orEmpty()
            .filter { TmdbJobNames.of(it.job) != null }
        val buckets = LinkedHashMap<String, MutableList<TmdbCreditItem>>()
        (cast + crew).forEach { item ->
            val type = item.mediaType
            if (item.id <= 0 || (type != "movie" && type != "tv")) return@forEach
            if (item.displayTitle.isEmpty()) return@forEach
            buckets.getOrPut("$type:${item.id}") { mutableListOf() }.add(item)
        }
        return buckets.values.mapNotNull { items ->
            val first = items.first()
            val roles = LinkedHashSet<String>()
            items.forEach { item ->
                val job = item.job?.trim().orEmpty()
                if (job.isEmpty()) {
                    val character = item.character?.trim().orEmpty()
                    roles.add(if (character.isEmpty()) "演员" else "饰演 $character")
                } else {
                    TmdbJobNames.of(job)?.let { roles.add(it) }
                }
            }
            if (roles.isEmpty()) return@mapNotNull null
            TmdbPersonWork(
                tmdbId = first.id,
                mediaType = first.mediaType ?: "movie",
                title = first.displayTitle,
                posterPath = items.map { it.posterPath.orEmpty() }
                    .firstOrNull { it.isNotBlank() }.orEmpty(),
                dateText = items.map { it.dateText }
                    .firstOrNull { it.isNotBlank() }.orEmpty(),
                genres = TmdbGenreNames.of(first.genreIds),
                roles = roles.take(3),
                voteAverage = first.voteAverage ?: 0.0,
                voteCount = first.voteCount ?: 0,
                popularity = first.popularity ?: 0.0
            )
        }
            .sortedWith(
                compareByDescending<TmdbPersonWork> { it.dateText }
                    .thenByDescending { it.popularity }
            )
            .take(PERSON_WORK_LIMIT)
    }

    // ---------------- 详情页：相关作品 / 分集剧照 ----------------

    private const val DETAIL_EXTRA_TTL_MS = 6L * 60 * 60 * 1000

    private class RelatedEntry(val at: Long, val items: List<TmdbRelatedWork>)
    private val relatedCache = java.util.concurrent.ConcurrentHashMap<String, RelatedEntry>()

    /**
     * 「相关作品」：优先 `/recommendations`（相关性更高），空结果回退 `/similar`。
     * 两条都是读接口、Worker 白名单早已放行。只保留有标题 + 有海报的条目，
     * 最多 12 条；拿不到就返回空列表，UI 整块隐藏（TMDB 是增强，不是功能依赖）。
     */
    suspend fun relatedWorks(mediaType: String, tmdbId: Int): List<TmdbRelatedWork> {
        if (tmdbId <= 0 || mediaType.isBlank()) return emptyList()
        val key = "$mediaType:$tmdbId"
        relatedCache[key]?.let {
            if (SystemClock.elapsedRealtime() - it.at < DETAIL_EXTRA_TTL_MS) return it.items
        }
        val params = mapOf("language" to LANGUAGE)
        val paths = listOf(
            "/3/$mediaType/$tmdbId/recommendations",
            "/3/$mediaType/$tmdbId/similar"
        )
        for (path in paths) {
            val body = request(path, params) ?: continue
            val results = runCatching { gson.fromJson(body, TmdbSearchResponse::class.java) }
                .getOrNull()?.results.orEmpty()
            val items = results.mapNotNull { item ->
                val title = item.displayTitle.trim()
                val poster = TmdbImage.poster(item.posterPath)
                if (item.id <= 0 || title.isBlank() || poster.isBlank()) null
                else TmdbRelatedWork(
                    tmdbId = item.id,
                    // recommendations / similar 的响应不带 media_type，沿用当前影片类型。
                    mediaType = mediaType,
                    title = title,
                    posterUrl = poster,
                    voteAverage = item.voteAverage ?: 0.0,
                    year = item.yearText,
                    overview = item.overview.orEmpty().trim()
                )
            }.distinctBy { it.tmdbId }.take(12)
            if (items.isNotEmpty()) {
                relatedCache[key] = RelatedEntry(SystemClock.elapsedRealtime(), items)
                return items
            }
        }
        // 空结果也缓存，避免每次进详情页都打两遍接口。
        relatedCache[key] = RelatedEntry(SystemClock.elapsedRealtime(), emptyList())
        return emptyList()
    }

    private class EpisodeEntry(val at: Long, val items: Map<Int, TmdbEpisodeInfo>)
    private val episodeCache = java.util.concurrent.ConcurrentHashMap<String, EpisodeEntry>()
    private val seasonBriefCache =
        java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, List<TmdbSeasonBrief>>>()

    /** `/3/tv/{id}` 顶层的 seasons（排除 season 0 特别篇），按季号升序。 */
    private suspend fun seasonBriefs(tmdbId: Int): List<TmdbSeasonBrief> {
        seasonBriefCache[tmdbId]?.let { (at, list) ->
            if (SystemClock.elapsedRealtime() - at < DETAIL_EXTRA_TTL_MS) return list
        }
        val body = request("/3/tv/$tmdbId", mapOf("language" to LANGUAGE)) ?: return emptyList()
        val seasons = runCatching { gson.fromJson(body, TmdbSeasonListResponse::class.java) }
            .getOrNull()?.seasons.orEmpty()
            .filter { it.seasonNumber > 0 && it.episodeCount > 0 }
            .sortedBy { it.seasonNumber }
        if (seasons.isNotEmpty()) {
            seasonBriefCache[tmdbId] = SystemClock.elapsedRealtime() to seasons
        }
        return seasons
    }

    /**
     * 给采集站剧集的每一集补 TMDB 分集剧照 / 简介，key = 采集站集号（1-based）。
     *
     * 采集站只有「第 N 集」没有季信息，所以按 TMDB seasons 顺序把各季串成连续编号：
     * 单季剧直接按 `episode_count == 源站集数` 精确命中；多季剧按季序累加，最多拉 3 季
     * （避免一部 200 集的老剧打出十几个请求）。**只在 TV 且确实拿到分集资料时返回非空**，
     * 映射不上的集由 UI 保持原来的 chips，不伪造剧照和简介。
     */
    suspend fun episodeInfos(tmdbId: Int, episodeCount: Int): Map<Int, TmdbEpisodeInfo> {
        if (tmdbId <= 0 || episodeCount <= 1) return emptyMap()
        val key = "tv:$tmdbId:$episodeCount"
        episodeCache[key]?.let {
            if (SystemClock.elapsedRealtime() - it.at < DETAIL_EXTRA_TTL_MS) return it.items
        }
        val seasons = seasonBriefs(tmdbId)
        if (seasons.isEmpty()) return emptyMap()

        val exact = seasons.firstOrNull { it.episodeCount == episodeCount }
        val picked = if (exact != null) listOf(exact) else buildList {
            var total = 0
            for (season in seasons) {
                if (size >= 3) break
                add(season)
                total += season.episodeCount
                if (total >= episodeCount) break
            }
        }

        val result = LinkedHashMap<Int, TmdbEpisodeInfo>()
        var global = 1
        for (season in picked) {
            if (global > episodeCount) break
            val body = request(
                "/3/tv/$tmdbId/season/${season.seasonNumber}",
                mapOf("language" to LANGUAGE)
            ) ?: continue
            val episodes = runCatching { gson.fromJson(body, TmdbSeasonResponse::class.java) }
                .getOrNull()?.episodes.orEmpty()
                .sortedBy { it.episodeNumber }
            for (ep in episodes) {
                if (global > episodeCount) break
                result[global] = TmdbEpisodeInfo(
                    episodeId = global,
                    name = ep.name.orEmpty().trim(),
                    overview = ep.overview.orEmpty().trim(),
                    stillUrl = TmdbImage.still(ep.stillPath),
                    airDate = ep.airDate.orEmpty(),
                    runtimeMinutes = ep.runtime ?: 0
                )
                global++
            }
        }
        val map: Map<Int, TmdbEpisodeInfo> = result.toMap()
        episodeCache[key] = EpisodeEntry(SystemClock.elapsedRealtime(), map)
        return map
    }

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
