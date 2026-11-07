package com.damon.mobilebox.data.tmdb

import kotlin.math.abs

/**
 * 片名匹配与类型推断。
 *
 * 刮削的核心风险是「刮错」——给《三体》配上动画版简介，用户一眼就能看出来。
 * 但阈值定太高（原来的 55）会把「标题包含 + 年份差一年」这类正确匹配也拒掉，
 * 国产剧源标注年份普遍偏移一两年，漏匹配比误匹配更常见。
 *
 * 放宽到 45 后仍有一条硬底线：**标题必须是完全相同或包含关系**。
 * 原来还有一档「4 字公共前缀 +12 分」的近似分支，配上年份与热度也能凑到 55，
 * 但那恰恰是《名侦探柯南》命中一堆同系列剧场版的来源，已删除。
 */
object TmdbMatcher {
    /** 达到该分数才认为匹配成功。45 = 包含关系(30) + 年份差一年(10) + 一点热度(5)。 */
    const val ACCEPT_SCORE = 45
    /** 括号内容（年份 / 别名 / 清晰度）、季数后缀这类噪声，比对前先剥掉。 */
    private val NOISE = listOf(
        Regex("[（(\\[【].*?[)）\\]】]"),
        Regex("第\\s*[一二三四五六七八九十百\\d]+\\s*[季部]"),
        Regex("(?i)\\b(s\\d{1,2}|season\\d{1,2}|part\\d)\\b")
    )

    /** 归一化：去噪声、去大小写、去空白与标点。只保留可比较的字面内容。 */
    fun normalize(raw: String): String {
        var s = raw
        NOISE.forEach { s = it.replace(s, " ") }
        s = s.lowercase()
        s = s.replace(Regex("[\\s\\p{Punct}·、，。！？：；“”‘’（）《》【】「」…—－]+"), "")
        listOf("全集", "高清", "完整版", "国语版", "粤语版", "未删减").forEach { s = s.removeSuffix(it) }
        return s.trim()
    }

    fun extractYear(raw: String): Int? =
        raw.filter { it.isDigit() }.take(4).toIntOrNull()?.takeIf { it in 1880..2100 }

    /**
     * 类型推断。订阅源的分类名是最可靠信号（「国产剧」「动漫」「纪录片」），
     * 副标题里带「集」也算剧集。都判断不出来时按电影处理 —— 电影比例更高。
     */
    fun guessType(category: String, subtitle: String = ""): String {
        val c = category + subtitle
        val tvKeywords = listOf("剧", "电视", "连续", "动漫", "动画", "纪录", "综艺", "番剧", "短剧")
        return if (tvKeywords.any { c.contains(it) }) "tv" else "movie"
    }

    /** 候选打分；返回 0 表示直接淘汰。internal：入参是对外的搜索 DTO，不暴露到模块外。 */
    internal fun score(query: String, year: Int?, item: TmdbSearchItem): Int {
        val title = normalize(item.displayTitle)
        if (title.isBlank() || query.isBlank()) return 0

        var score = 0
        when {
            title == query -> score += 60
            title.contains(query) || query.contains(title) -> score += 30
            // 不再接受「公共前缀近似」：源站与 TMDB 的差异绝大多数是标点/季数/别名，
            // normalize 之后已经相等或包含；剩下的前缀相似基本都是同系列的不同作品。
            else -> return 0
        }

        val itemYear = item.yearText.toIntOrNull()
        if (year != null && itemYear != null) {
            score += when {
                itemYear == year -> 25
                abs(itemYear - year) <= 1 -> 10
                // 年份差太多只扣分不淘汰：剧集首播年与源标注年份常有出入。
                else -> -10
            }
        }

        val popularity = item.popularity ?: 0.0
        score += when {
            popularity > 50.0 -> 15
            popularity > 10.0 -> 10
            popularity > 1.0 -> 5
            else -> 0
        }
        if ((item.voteCount ?: 0) > 50) score += 5
        return score
    }
}
