package com.damon.mobilebox.data.tmdb

import com.google.gson.annotations.SerializedName

/**
 * TMDB 数据模型（只声明实际用到的字段）。
 *
 * 全部字段可空：Gson 走反射不经过 Kotlin 构造器默认值，缺失字段会直接塞 null 进来，
 * 声明成非空类型会在第一次解析时就崩。
 */
object TmdbImage {
    private const val BASE = "https://image.tmdb.org/t/p/"

    /** 列表卡 / 详情 A 层兜底用的竖版海报。 */
    fun poster(path: String?): String =
        path?.takeIf { it.isNotBlank() }?.let { BASE + "w500" + it }.orEmpty()

    /** 演员页顶部海报拼接墙专用：一屏要铺十几张，w185 够用且省一个数量级的流量。 */
    fun posterSmall(path: String?): String =
        path?.takeIf { it.isNotBlank() }?.let { BASE + "w185" + it }.orEmpty()

    /** 详情页背景大图。 */
    fun backdrop(path: String?): String =
        path?.takeIf { it.isNotBlank() }?.let { BASE + "w780" + it }.orEmpty()

    /** 剧照横滑/网格：比 backdrop 小一档，省流量。 */
    fun still(path: String?): String =
        path?.takeIf { it.isNotBlank() }?.let { BASE + "w500" + it }.orEmpty()

    /** 演员头像：w185 已经够 54dp 圆头像用，再大就是浪费流量。 */
    fun profile(path: String?): String =
        path?.takeIf { it.isNotBlank() }?.let { BASE + "w185" + it }.orEmpty()

    /**
     * 片名 logo：TMDB 的 `images.logos` 是**带透明通道的 PNG**（官方 title treatment），
     * 很多是彩色或纯白艺术字，直接贴在海报上比 Text 标题好看得多。
     * 尺寸用 w500 —— 首页高度 56dp 左右，w500 已经超过 3 倍屏密度，再大纯浪费。
     */
    fun logo(path: String?): String =
        path?.takeIf { it.isNotBlank() }?.let { BASE + "w500" + it }.orEmpty()
}

// ---------------- 搜索 ----------------

internal data class TmdbSearchResponse(
    val page: Int = 1,
    val results: List<TmdbSearchItem>? = null
)

internal data class TmdbSearchItem(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val original_title: String? = null,
    val original_name: String? = null,
    val release_date: String? = null,
    val first_air_date: String? = null,
    @SerializedName("vote_average") val voteAverage: Double? = null,
    @SerializedName("vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerializedName("media_type") val mediaType: String? = null
) {
    val displayTitle: String
        get() = title ?: name ?: original_title ?: original_name.orEmpty()

    val yearText: String
        get() = (release_date ?: first_air_date).orEmpty().take(4)
}

// ---------------- 详情 ----------------

internal data class TmdbDetailResponse(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val status: String? = null,
    val runtime: Int? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("vote_average") val voteAverage: Double? = null,
    @SerializedName("vote_count") val voteCount: Int? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("backdrop_path") val backdropPath: String? = null,
    @SerializedName("imdb_id") val imdbId: String? = null,
    val genres: List<TmdbGenre>? = null,
    /** 剧集专属：TMDB 用 created_by 表达「主创」，没有 Director 这个 crew 职位。 */
    @SerializedName("created_by") val createdBy: List<TmdbCrewMember>? = null,
    /** append_to_response=credits 附带；演职员表。 */
    val credits: TmdbCredits? = null,
    /** append_to_response=images 附带；只取 backdrops 当剧照。 */
    val images: TmdbImages? = null
)

internal data class TmdbCredits(
    val cast: List<TmdbCastMember>? = null,
    val crew: List<TmdbCrewMember>? = null
)

internal data class TmdbCastMember(
    val id: Int = 0,
    val name: String? = null,
    /** 片中角色名，可能是空串（群演/纪录片）。 */
    val character: String? = null,
    @SerializedName("profile_path") val profilePath: String? = null,
    /** TMDB 的排序权重，越小越靠前（通常按戏份）。 */
    val order: Int = 0
)

internal data class TmdbCrewMember(
    val id: Int = 0,
    val name: String? = null,
    /** Director / Producer / Writer / Screenplay ... */
    val job: String? = null,
    @SerializedName("profile_path") val profilePath: String? = null
)

internal data class TmdbImages(
    val backdrops: List<TmdbImageItem>? = null,
    /** 片名 logo（透明 PNG）。详情页与首页轮播都用它替代纯文本标题。 */
    val logos: List<TmdbImageItem>? = null
)

internal data class TmdbImageItem(
    @SerializedName("file_path") val filePath: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** logos 专属：语言码（zh / en / null = 无对白图形标识）。 */
    @SerializedName("iso_639_1") val language: String? = null,
    /** logos 专属：宽高比，UI 直接按它算宽，避免拉伸。 */
    @SerializedName("aspect_ratio") val aspectRatio: Double? = null
)

internal data class TmdbGenre(
    val id: Int = 0,
    val name: String? = null
)

/** `/3/genre/{movie|tv}/list` 的响应。分类页的类型表入口。 */
internal data class TmdbGenreListResponse(
    val genres: List<TmdbGenre>? = null
)

/**
 * TMDB 的**剧集**类型有一部分没有中文译名（实测《伟大的长征》返回 `War & Politics`，
 * 《蜘蛛侠》剧集返回 `Sci-Fi & Fantasy`），会直接漏到「分类」标签里。
 * 这里做一层兜底映射；不在表里的原样返回，宁可显示英文也不吞掉标签。
 */
object TmdbGenreZh {
    private val MAP = mapOf(
        "Action & Adventure" to "动作冒险",
        "Sci-Fi & Fantasy" to "科幻奇幻",
        "War & Politics" to "战争政治",
        "Science Fiction" to "科幻",
        "TV Movie" to "电视电影",
        "Talk" to "脱口秀",
        "News" to "新闻",
        "Soap" to "肥皂剧",
        "Reality" to "真人秀",
        "Kids" to "儿童",
        "Action" to "动作",
        "Adventure" to "冒险",
        "Animation" to "动画",
        "Comedy" to "喜剧",
        "Crime" to "犯罪",
        "Documentary" to "纪录片",
        "Drama" to "剧情",
        "Family" to "家庭",
        "Fantasy" to "奇幻",
        "History" to "历史",
        "Horror" to "恐怖",
        "Music" to "音乐",
        "Mystery" to "悬疑",
        "Romance" to "爱情",
        "Thriller" to "惊悚",
        "War" to "战争",
        "Western" to "西部"
    )

    fun of(name: String?): String {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return ""
        return MAP[n] ?: n
    }
}

// ---------------- 对外模型 ----------------

/**
 * 刮削结果，同时作为缓存单元直接序列化落盘。
 *
 * 只保留 UI 真正消费的字段：多余字段会让 500 条缓存膨胀到几 MB，冷启动读文件变慢。
 */
data class TmdbMeta(
    val tmdbId: Int = 0,
    /** movie / tv */
    val mediaType: String = "movie",
    val title: String = "",
    val overview: String = "",
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
    val year: String = "",
    val genres: List<String> = emptyList(),
    val posterUrl: String = "",
    val backdropUrl: String = "",
    /** 透明 PNG 片名 logo；空串表示 TMDB 没有，UI 退回纯文本标题。 */
    val logoUrl: String = "",
    /** logo 的宽高比（宽/高）。0 表示未知，UI 按 3:1 兜底。 */
    val logoAspect: Float = 0f,
    /** 演职人员：导演在前，其后按 TMDB 戏份排序，已裁到 12 条（够横滑一屏，也不撑爆缓存）。 */
    val cast: List<TmdbPerson> = emptyList(),
    /** 剧照（TMDB backdrops），不再裁剪 —— 详情页横滑 + 全屏查看器都靠它。 */
    val stills: List<String> = emptyList(),
    /** 匹配得分，仅用于排查刮错；>= 45 才会被采纳。 */
    val score: Int = 0,
    val updatedAt: Long = 0L
)

/** 演职人员一条：姓名 + 身份/角色 + 头像（TMDB 没有头像时为空串，UI 用姓名首字兜底）。 */
data class TmdbPerson(
    val name: String = "",
    val role: String = "",
    val profileUrl: String = "",
    /**
     * TMDB person id。详情页点头像要拿它去拉作品年表，0 表示来自订阅源文本（没有 id），
     * 那种情况只能按姓名搜人。旧缓存里没这个字段，Gson 反射赋 0，不影响兼容。
     */
    val personId: Int = 0
)

// ---------------- 人物 / 作品年表 ----------------

internal data class TmdbPersonSearchResponse(
    val results: List<TmdbPersonSearchItem>? = null
)

internal data class TmdbPersonSearchItem(
    val id: Int = 0,
    val name: String? = null,
    val popularity: Double? = null,
    @SerializedName("known_for_department") val department: String? = null
)

/** `/3/person/{id}?append_to_response=combined_credits,external_ids` 的响应。 */
internal data class TmdbPersonDetailResponse(
    val id: Int = 0,
    val name: String? = null,
    val birthday: String? = null,
    @SerializedName("profile_path") val profilePath: String? = null,
    @SerializedName("known_for_department") val department: String? = null,
    @SerializedName("combined_credits") val combinedCredits: TmdbCombinedCredits? = null,
    @SerializedName("external_ids") val externalIds: TmdbExternalIds? = null
)

internal data class TmdbCombinedCredits(
    val cast: List<TmdbCreditItem>? = null,
    val crew: List<TmdbCreditItem>? = null
)

/** combined_credits 里的电影 / 剧集条目，cast 与 crew 共用同一个结构。 */
internal data class TmdbCreditItem(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    @SerializedName("original_title") val originalTitle: String? = null,
    @SerializedName("original_name") val originalName: String? = null,
    @SerializedName("poster_path") val posterPath: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null,
    @SerializedName("media_type") val mediaType: String? = null,
    val character: String? = null,
    val job: String? = null,
    @SerializedName("genre_ids") val genreIds: List<Int>? = null,
    @SerializedName("vote_average") val voteAverage: Double? = null,
    @SerializedName("vote_count") val voteCount: Int? = null,
    val popularity: Double? = null
) {
    val displayTitle: String
        get() = (title ?: name ?: originalTitle ?: originalName).orEmpty().trim()

    /** 电影用 release_date，剧集用 first_air_date；ISO `yyyy-MM-dd` 直接字符串排序即可。 */
    val dateText: String
        get() = (releaseDate ?: firstAirDate).orEmpty().trim()
}

internal data class TmdbExternalIds(
    @SerializedName("twitter_id") val twitterId: String? = null,
    @SerializedName("instagram_id") val instagramId: String? = null
)

/** 时间轴一条作品：一部片既演又导时合并成一条，身份全部收进 [roles]。 */
data class TmdbPersonWork(
    val tmdbId: Int = 0,
    /** movie / tv：点进详情时直接沿用首页轮播那条 TMDB 路由。 */
    val mediaType: String = "movie",
    val title: String = "",
    val posterPath: String = "",
    val dateText: String = "",
    val genres: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
    val popularity: Double = 0.0
) {
    val posterUrl: String get() = TmdbImage.poster(posterPath)
    val smallPosterUrl: String get() = TmdbImage.posterSmall(posterPath)
    val year: String get() = dateText.take(4)
    /** 去前导 0：截图里是「9月」不是「09月」。 */
    val month: String
        get() = if (dateText.length >= 7) dateText.substring(5, 7).toIntOrNull()?.toString().orEmpty()
        else ""
}

/** 演员页首帧所需的全部信息。进程内缓存，不落盘（作品年表会随 TMDB 更新）。 */
data class TmdbPersonProfile(
    val personId: Int = 0,
    val name: String = "",
    val profileUrl: String = "",
    val department: String = "",
    val twitterId: String = "",
    val instagramId: String = "",
    val works: List<TmdbPersonWork> = emptyList()
)

/**
 * combined_credits 只给 `genre_ids`，不给名字。TMDB 的 movie / tv 类型 id 是同一套编号空间，
 * 合并成一张表即可；不在表里的 id 直接丢掉，宁可少显示一个标签也不显示「10765」。
 */
object TmdbGenreNames {
    private val MAP = mapOf(
        28 to "动作", 12 to "冒险", 16 to "动画", 35 to "喜剧", 80 to "犯罪",
        99 to "纪录片", 18 to "剧情", 10751 to "家庭", 14 to "奇幻", 36 to "历史",
        27 to "恐怖", 10402 to "音乐", 9648 to "悬疑", 10749 to "爱情", 878 to "科幻",
        10770 to "电视电影", 53 to "惊悚", 10752 to "战争", 37 to "西部",
        10759 to "动作冒险", 10762 to "儿童", 10763 to "新闻", 10764 to "真人秀",
        10765 to "科幻奇幻", 10766 to "肥皂剧", 10767 to "脱口秀", 10768 to "战争政治"
    )

    fun of(ids: List<Int>?, limit: Int = 2): List<String> =
        ids.orEmpty().mapNotNull { MAP[it] }.distinct().take(limit)
}

/** crew 里只留用户看得懂的职位；灯光、场务这类杂职会把时间轴刷成噪音。 */
object TmdbJobNames {
    private val MAP = mapOf(
        "Director" to "导演", "Writer" to "编剧", "Screenplay" to "编剧",
        "Producer" to "制作人", "Executive Producer" to "制作人", "Creator" to "主创",
        "Story" to "故事", "Novel" to "原著", "Characters" to "角色创作",
        "Director of Photography" to "摄影", "Original Music Composer" to "配乐",
        "Editor" to "剪辑"
    )

    fun of(job: String?): String? = MAP[job?.trim()]
}

// ---------------- 详情页：相关作品 / 分集剧照 ----------------

/** 详情页「相关作品」横滑卡片一条。点进去复用 `detail/tmdb/{type}/{id}` 路由。 */
data class TmdbRelatedWork(
    val tmdbId: Int = 0,
    /** movie / tv，跟随被推荐的那部片的类型（recommendations 响应不带 media_type）。 */
    val mediaType: String = "movie",
    val title: String = "",
    val posterUrl: String = "",
    val voteAverage: Double = 0.0,
    val year: String = "",
    val overview: String = ""
)

/** 一集的 TMDB 补充资料。`episodeId` 是**采集站集号**（1-based 尽力映射），不是 TMDB 季内编号。 */
data class TmdbEpisodeInfo(
    val episodeId: Int = 0,
    val name: String = "",
    val overview: String = "",
    /** TMDB season still（横版剧照），空串表示这一集没有图。 */
    val stillUrl: String = "",
    val airDate: String = "",
    val runtimeMinutes: Int = 0
)

/** `/3/tv/{id}` 顶层就带 seasons；单独解析一份，不把分集数据塞进 TmdbMeta / 磁盘缓存。 */
internal data class TmdbSeasonListResponse(
    val seasons: List<TmdbSeasonBrief>? = null
)

internal data class TmdbSeasonBrief(
    @SerializedName("season_number") val seasonNumber: Int = 0,
    @SerializedName("episode_count") val episodeCount: Int = 0,
    val name: String? = null
)

internal data class TmdbSeasonResponse(
    val episodes: List<TmdbSeasonEpisode>? = null
)

internal data class TmdbSeasonEpisode(
    @SerializedName("episode_number") val episodeNumber: Int = 0,
    val name: String? = null,
    val overview: String? = null,
    @SerializedName("still_path") val stillPath: String? = null,
    @SerializedName("air_date") val airDate: String? = null,
    val runtime: Int? = null,
    @SerializedName("vote_average") val voteAverage: Double? = null
)
