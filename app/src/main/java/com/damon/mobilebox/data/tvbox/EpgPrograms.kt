package com.damon.mobilebox.data.tvbox

import java.util.Locale

/** XMLTV 单条节目 */
data class EpgSlot(
    val title: String,
    val startMillis: Long,
    val endMillis: Long
)

/** 频道当前 / 下一档节目；两者都为 null 时表示没有可用节目单 */
data class EpgNowNext(
    val current: EpgSlot?,
    val next: EpgSlot?
)

/**
 * 节目单查询工具。
 * EPG 的 channel id、tvg-id、频道名三方命名往往不一致（CCTV1 / CCTV-1 综合 / cctv1hd），
 * 这里统一归一化后建索引，匹配不到就返回 null —— 调用方据此**整行不显示**，不回填假数据。
 */
object EpgPrograms {

    fun normalize(raw: String): String = raw
        .lowercase(Locale.ROOT)
        .replace(Regex("""[\s\-_.,，。:：（）()\[\]【】]"""), "")
        .replace(Regex("""(hd|fhd|uhd|4k|8k|高清|超清|标清|蓝光|频道|综合|卫视|台)"""), "")
        .trim()

    /**
     * 按当前时间从当天节目表里取出「正在播」与「稍后」。
     * 传入 nowMillis 是为了让 UI 可以定时刷新触发重算，而无需重新拉取 EPG。
     */
    fun lookup(
        programs: Map<String, List<EpgSlot>>,
        channel: LiveChannel,
        nowMillis: Long = System.currentTimeMillis()
    ): EpgNowNext? {
        if (programs.isEmpty()) return null

        val keys = buildList {
            channel.tvgId?.let { add(it) }
            add(channel.name)
            channel.name.replace(Regex("""\d+[KkPp]"""), "").let { add(it) }
        }
        val slots = keys.firstNotNullOfOrNull { programs[normalize(it)] } ?: return null
        if (slots.isEmpty()) return null

        val current = slots.lastOrNull { it.startMillis <= nowMillis && nowMillis < it.endMillis }
        val next = slots.firstOrNull { it.startMillis > nowMillis }
        return if (current == null && next == null) null else EpgNowNext(current, next)
    }

    /** 列表与顶部共用的展示文案；缺项用 -- 占位，但不会凭空编造节目名 */
    fun formatList(slot: EpgNowNext): String =
        "正在播: ${slot.current?.title ?: "--"} | 稍后: ${slot.next?.title ?: "--"}"

    fun formatHeader(slot: EpgNowNext): String =
        "当前节目: ${slot.current?.title ?: "--"} · 稍后: ${slot.next?.title ?: "--"}"
}
