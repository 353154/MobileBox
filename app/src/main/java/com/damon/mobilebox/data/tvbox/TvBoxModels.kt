package com.damon.mobilebox.data.tvbox

import com.google.gson.annotations.SerializedName

// ==========================================
// 1. TVBox 订阅配置根结构 (兼容单仓与多仓)
// ==========================================
data class TvBoxConfig(
    val spider: String? = null,
    val wallpaper: String? = null,
    val sites: List<TvBoxSite>? = null,
    val lives: List<TvBoxLive>? = null,
    val parses: List<TvBoxParse>? = null,
    val rules: List<TvBoxRule>? = null,
    val urls: Any? = null // 多仓核心：支持 List<TvBoxWarehouse> 或 List<String>
)

/**
 * TVBox 多仓子线路结构
 */
data class TvBoxWarehouse(
    val name: String,
    val url: String
)

/**
 * 订阅结果统一封装：自动区分单仓 vs 多仓
 */
sealed class TvBoxSubscriptionResult {
    data class SingleConfig(val config: TvBoxConfig, val rawUrl: String) : TvBoxSubscriptionResult()
    data class MultiWarehouse(val warehouses: List<TvBoxWarehouse>, val rawUrl: String) : TvBoxSubscriptionResult()
}

data class TvBoxSite(
    val key: String,
    val name: String,
    val type: Int = 1, // 0: xml/maccms, 1: json/maccms, 3: spider, 4: appys
    val api: String,
    val searchable: Int? = 1,
    val quickSearch: Int? = 1,
    val filterable: Int? = 1,
    val ext: Any? = null
) {
    val typeLabel: String
        get() = when (type) {
            0 -> "CMS-XML"
            1 -> "CMS-JSON"
            3 -> "Spider爬虫"
            4 -> "AppYs原生"
            else -> "标准源"
        }
}

data class TvBoxLive(
    val name: String,
    val type: Int? = 0,
    val url: String? = null,
    val playerType: Int? = null,
    val ua: String? = null,
    val epg: String? = null,
    val logo: String? = null
)

data class LiveChannelGroup(
    val groupName: String,
    val channels: List<LiveChannel>
)

/** 直播列表解析结果：频道分组 + 从 M3U 头发现的 EPG 地址 */
data class LiveParseResult(
    val groups: List<LiveChannelGroup>,
    val epgUrls: List<String> = emptyList()
)

data class LiveChannel(
    val name: String,
    val url: String,
    val urls: List<String> = listOf(url),
    val group: String = "默认分组",
    val epg: String? = null,
    val logo: String? = null,
    val tvgId: String? = null
)

data class TvBoxParse(
    val name: String,
    val type: Int = 0, // 0: web/sniffer, 1: json, 2: parallel
    val url: String,
    val ext: Any? = null
)

data class TvBoxRule(
    val name: String? = null,
    val hosts: List<String>? = null,
    val regex: List<String>? = null
)

// ==========================================
// 2. MacCMS (苹果CMS v10) 标准采集接口返回结构
// ==========================================
data class MacCmsResponse(
    val code: Int? = null,
    val msg: String? = null,
    val page: Any? = null,
    val pagecount: Any? = null,
    val limit: Any? = null,
    val total: Any? = null,
    val list: List<MacCmsVod>? = null,
    @SerializedName("class")
    val categories: List<MacCmsCategory>? = null
)

data class MacCmsCategory(
    @SerializedName("type_id")
    val typeId: Int,
    @SerializedName("type_pid")
    val typePid: Int = 0,
    @SerializedName("type_name")
    val typeName: String
)

data class MacCmsVod(
    @SerializedName("vod_id")
    val vodId: Any,
    @SerializedName("vod_name")
    val vodName: String,
    @SerializedName("vod_sub")
    val vodSub: String? = null,
    @SerializedName("vod_remarks")
    val vodRemarks: String? = null,
    @SerializedName("vod_pic")
    val vodPic: String? = null,
    @SerializedName("vod_year")
    val vodYear: String? = null,
    @SerializedName("type_name")
    val typeName: String? = null,
    @SerializedName("type_id")
    val typeId: Any? = null,
    @SerializedName("vod_score")
    val vodScore: String? = null,
    @SerializedName("vod_content")
    val vodContent: String? = null,
    @SerializedName("vod_actor")
    val vodActor: String? = null,
    @SerializedName("vod_director")
    val vodDirector: String? = null,
    @SerializedName("vod_area")
    val vodArea: String? = null,
    @SerializedName("vod_play_from")
    val vodPlayFrom: String? = null,
    @SerializedName("vod_play_url")
    val vodPlayUrl: String? = null
)
