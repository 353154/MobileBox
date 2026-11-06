package com.damon.mobilebox.data.tvbox

import com.google.gson.annotations.SerializedName

// ==========================================
// 1. TVBox 订阅配置根结构
// ==========================================
data class TvBoxConfig(
    val spider: String? = null,
    val sites: List<TvBoxSite>? = null,
    val lives: List<TvBoxLive>? = null
)

data class TvBoxSite(
    val key: String,
    val name: String,
    val type: Int = 1, // 0: xml/maccms, 1: json/maccms, 3: spider, 4: appys
    val api: String,
    val searchable: Int? = 1,
    val quickSearch: Int? = 1,
    val filterable: Int? = 1,
    val ext: Any? = null
)

data class TvBoxLive(
    val name: String,
    val type: Int? = 0,
    val url: String? = null
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
    @SerializedName("vod_play_from")
    val vodPlayFrom: String? = null,
    @SerializedName("vod_play_url")
    val vodPlayUrl: String? = null
)
