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
    val profileUrl: String = ""
)
