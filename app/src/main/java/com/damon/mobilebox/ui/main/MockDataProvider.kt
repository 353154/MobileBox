package com.damon.mobilebox.ui.main

/**
 * 仿真测试数据仓库 (Mock Repository)
 * 设计为标准数据类，支持一级分类、二级子分类过滤，并包含真实网络海报图片。
 * 方便后续数据层重构中 1:1 无缝替换为 Spider 爬虫与网络真实数据。
 */
data class MockMovie(
    val id: String,
    val title: String,
    val remarks: String,     // 清晰度或更新集数
    val category: String,    // 一级分类: 电影, 电视剧, 动漫, 综艺, 纪录片, 4K专区
    val subCategory: String = "全部", // 二级子分类: 动作, 科幻, 华语, 美剧, 热血 等
    val rating: String = "8.9",
    val posterUrl: String = "" // 真实海报网络图
)

data class MockLiveChannel(
    val id: String,
    val name: String,
    val currentProgram: String,
    val nextProgram: String,
    val group: String
)

data class MockDataSource(
    val id: String,
    val name: String,
    val description: String,
    val isDirect: Boolean = true
)

object MockDataProvider {

    // 仿真数据源列表 (支持动态切换)
    val dataSources = listOf(
        MockDataSource("bf", "暴风片库", "自建专属轻量 Hub · 直连秒播", true),
        MockDataSource("hn", "红牛片库", "4K 蓝光高码专区 · 原盘画质", true),
        MockDataSource("ff", "非凡影视", "全网聚合采集站 · 综合类目全", false),
        MockDataSource("lz", "量子秒播", "高速多线 CDN · 极速加载", true),
        MockDataSource("hub", "自建 LiveHub", "个人 NAS 云端安全鉴权源", true)
    )

    // 首页精选点播仿真数据 (24部全球口碑大作，全量覆盖高清海报，绝无重复)
    val homeVodList = listOf(
        MockMovie("h5", "死侍与金刚", "4K 杜比视界", "电影", "动作", "8.2", "https://image.tmdb.org/t/p/w500/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg"),
        MockMovie("h10", "黑袍纠察队 第四季", "全8集 未删减 4K", "电视剧", "美剧", "8.5", "https://image.tmdb.org/t/p/w500/2zmTngn1tYC1AvfnrFLhxeD82hz.jpg"),
        MockMovie("h16", "进击的巨人 最终季", "完结篇 4K", "动漫", "热血", "9.5", "https://image.tmdb.org/t/p/w500/hTP1IILEoBgvgzbvg1swUh9rfua.jpg"),
        MockMovie("h20", "狂暴之路: 麦克斯4", "黑白重金属 4K", "电影", "动作", "8.7", "https://image.tmdb.org/t/p/w500/8tZYtuWezp8JbcsvHYO0O46tFbo.jpg"),
        MockMovie("h1", "沙丘 2", "IMAX 4K 臻彩", "电影", "科幻", "8.5", "https://image.tmdb.org/t/p/w500/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg"),
        MockMovie("h2", "奥本海默", "蓝光中字 4K", "电影", "科幻", "8.8", "https://image.tmdb.org/t/p/w500/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg"),
        MockMovie("h3", "阿凡达 2: 水之道", "4K 60帧 杜比视界", "4K专区", "科幻", "9.0", "https://image.tmdb.org/t/p/w500/t6HIqrRAclMCA60NsSmeqe9RmNV.jpg"),
        MockMovie("h4", "星际穿越", "IMAX 4K 重置", "4K专区", "科幻", "9.4", "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg"),
        MockMovie("h6", "新蝙蝠侠", "4K 蓝光高码", "电影", "动作", "8.0", "https://image.tmdb.org/t/p/w500/74xTEgt7R36Fpooo50r9T25onhq.jpg"),
        MockMovie("h7", "盗梦空间", "IMAX 4K 典藏", "电影", "科幻", "9.3", "https://image.tmdb.org/t/p/w500/edv5CZvWj09upOsy2Y6IwDhK8bt.jpg"),
        MockMovie("h8", "复仇者联盟4", "4K HDR 终局之战", "电影", "动作", "8.9", "https://image.tmdb.org/t/p/w500/or06FN3Dka5tukK1e9sl16pB3iy.jpg"),
        MockMovie("h9", "龙之家族 第二季", "全8集 4K HDR", "电视剧", "美剧", "8.6", "https://image.tmdb.org/t/p/w500/7V0Ebks0GgpKvQ7QbLAIdX5dos4.jpg"),
        MockMovie("h11", "绝命毒师", "全五季 4K 重置", "电视剧", "美剧", "9.6", "https://image.tmdb.org/t/p/w500/ggFHVNu6YYI5L9pCfOacjizRGt.jpg"),
        MockMovie("h12", "怪奇物语 第四季", "全9集 4K 杜比", "电视剧", "美剧", "9.0", "https://image.tmdb.org/t/p/w500/49WJfeN0moxb9IPfGn8AIqMGskD.jpg"),
        MockMovie("h13", "鬼灭之刃: 柱训练篇", "全8集 1080P", "动漫", "热血", "8.8", "https://image.tmdb.org/t/p/w500/xUfRZu2mi8jH6SzQEJGP6tjBuYj.jpg"),
        MockMovie("h14", "葬送的芙莉莲", "全28集 4K 治愈", "动漫", "奇幻", "9.4", "https://image.tmdb.org/t/p/w500/dqZENchTd7lp5zht7BdlqM7RBhD.jpg"),
        MockMovie("h15", "蜘蛛侠: 纵横宇宙", "4K 高码无损", "动漫", "奇幻", "8.9", "https://image.tmdb.org/t/p/w500/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg"),
        MockMovie("h17", "疾速追杀 4", "1080P 枪斗术典藏", "电影", "动作", "8.1", "https://image.tmdb.org/t/p/w500/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg"),
        MockMovie("h18", "头号玩家", "4K 60帧 杜比全景声", "电影", "科幻", "8.7", "https://image.tmdb.org/t/p/w500/pU1ULUq8D3iRxl1fdX2lZIzdHuI.jpg"),
        MockMovie("h19", "指环王3: 王者归来", "4K 蓝光加长典藏", "电影", "奇幻", "9.5", "https://image.tmdb.org/t/p/w500/rCzpDGLbOoPwLjy3OAm5NUPOTrC.jpg"),
        MockMovie("h21", "黑客帝国", "4K 杜比视界典藏", "电影", "科幻", "9.1", "https://image.tmdb.org/t/p/w500/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg"),
        MockMovie("h22", "泰坦尼克号", "25周年 4K 3D重置", "电影", "爱情", "9.5", "https://image.tmdb.org/t/p/w500/9xjZS2rlVxm8SFx8kPC3aIGCOYQ.jpg"),
        MockMovie("h23", "千与千寻", "吉卜力 4K 修复", "动漫", "奇幻", "9.4", "https://image.tmdb.org/t/p/w500/39wmItIWsg5sZMyRUHLkWBcuVCM.jpg"),
        MockMovie("h24", "寄生虫", "金棕榈 4K 极清", "电影", "悬疑", "8.8", "https://image.tmdb.org/t/p/w500/7IiTTgloJzvGI1TAYymCfbfl3vT.jpg")
    )

    // 切换到“红牛片库 (4K)”时的专属片单 (全量 4K 原盘高码作品)
    val hnVodList = listOf(
        MockMovie("hn1", "指环王3: 王者归来", "4K 蓝光加长典藏", "4K专区", "奇幻", "9.5", "https://image.tmdb.org/t/p/w500/rCzpDGLbOoPwLjy3OAm5NUPOTrC.jpg"),
        MockMovie("hn2", "头号玩家", "4K 60帧 杜比全景声", "4K专区", "科幻", "8.7", "https://image.tmdb.org/t/p/w500/pU1ULUq8D3iRxl1fdX2lZIzdHuI.jpg"),
        MockMovie("hn3", "星际穿越", "IMAX 4K 蓝光原盘", "4K专区", "科幻", "9.4", "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg"),
        MockMovie("hn4", "阿凡达 2: 水之道", "4K 60帧 杜比视界", "4K专区", "科幻", "9.0", "https://image.tmdb.org/t/p/w500/t6HIqrRAclMCA60NsSmeqe9RmNV.jpg"),
        MockMovie("hn5", "狂暴之路: 麦克斯4", "4K 原盘高码", "4K专区", "动作", "8.7", "https://image.tmdb.org/t/p/w500/8tZYtuWezp8JbcsvHYO0O46tFbo.jpg"),
        MockMovie("hn6", "黑客帝国", "4K 杜比视界典藏", "4K专区", "科幻", "9.1", "https://image.tmdb.org/t/p/w500/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg"),
        MockMovie("hn7", "泰坦尼克号", "4K HDR 典藏版", "4K专区", "爱情", "9.5", "https://image.tmdb.org/t/p/w500/9xjZS2rlVxm8SFx8kPC3aIGCOYQ.jpg"),
        MockMovie("hn8", "盗梦空间", "IMAX 4K 蓝光原盘", "4K专区", "悬疑", "9.3", "https://image.tmdb.org/t/p/w500/edv5CZvWj09upOsy2Y6IwDhK8bt.jpg")
    )

    // 一级分类标签
    val categoryTabs = listOf("全部", "电影", "电视剧", "动漫", "综艺", "纪录片")

    // 二级子分类映射表
    val subCategoriesMap = mapOf(
        "全部" to listOf("全部", "高分榜单", "最新上线", "热播排行"),
        "电影" to listOf("全部", "动作", "科幻", "奇幻", "悬疑"),
        "电视剧" to listOf("全部", "美剧", "华语", "韩剧", "古装"),
        "动漫" to listOf("全部", "热血", "奇幻", "治愈", "神作"),
        "综艺" to listOf("全部", "真人秀", "音乐", "竞技", "脱口秀"),
        "纪录片" to listOf("全部", "自然", "宇宙", "人文", "历史")
    )

    // 全量分类测试影视库 (全量配备有效高清海报，每个分类各具特色，零重复)
    val allCategoryMovies = listOf(
        // --- 电影：动作 ---
        MockMovie("m1", "疾速追杀 4", "1080P 枪斗术典藏", "电影", "动作", "8.1", "https://image.tmdb.org/t/p/w500/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg"),
        MockMovie("m2", "死侍与金刚", "4K 杜比视界", "电影", "动作", "8.2", "https://image.tmdb.org/t/p/w500/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg"),
        MockMovie("m3", "新蝙蝠侠", "4K 蓝光高码", "电影", "动作", "8.0", "https://image.tmdb.org/t/p/w500/74xTEgt7R36Fpooo50r9T25onhq.jpg"),
        MockMovie("m4", "狂暴之路: 麦克斯4", "4K 蓝光重金属", "电影", "动作", "8.7", "https://image.tmdb.org/t/p/w500/8tZYtuWezp8JbcsvHYO0O46tFbo.jpg"),

        // --- 电影：科幻 ---
        MockMovie("m5", "沙丘 2", "IMAX 4K 臻彩", "电影", "科幻", "8.5", "https://image.tmdb.org/t/p/w500/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg"),
        MockMovie("m6", "星际穿越", "IMAX 4K 重置", "电影", "科幻", "9.4", "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg"),
        MockMovie("m7", "黑客帝国", "4K 杜比视界", "电影", "科幻", "9.1", "https://image.tmdb.org/t/p/w500/f89U3ADr1oiB1s9GkdPOEpXUk5H.jpg"),
        MockMovie("m8", "阿凡达 2: 水之道", "4K 60帧 杜比视界", "电影", "科幻", "9.0", "https://image.tmdb.org/t/p/w500/t6HIqrRAclMCA60NsSmeqe9RmNV.jpg"),

        // --- 电影：奇幻与悬疑 ---
        MockMovie("m9", "指环王3: 王者归来", "4K 加长典藏", "电影", "奇幻", "9.5", "https://image.tmdb.org/t/p/w500/rCzpDGLbOoPwLjy3OAm5NUPOTrC.jpg"),
        MockMovie("m10", "盗梦空间", "IMAX 4K 蓝光", "电影", "悬疑", "9.3", "https://image.tmdb.org/t/p/w500/edv5CZvWj09upOsy2Y6IwDhK8bt.jpg"),
        MockMovie("m11", "奥本海默", "4K HDR 原盘", "电影", "悬疑", "8.8", "https://image.tmdb.org/t/p/w500/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg"),
        MockMovie("m12", "寄生虫", "金棕榈 4K 极清", "电影", "悬疑", "8.8", "https://image.tmdb.org/t/p/w500/7IiTTgloJzvGI1TAYymCfbfl3vT.jpg"),

        // --- 电视剧：美剧 ---
        MockMovie("tv1", "龙之家族 第二季", "全8集 4K HDR", "电视剧", "美剧", "8.6", "https://image.tmdb.org/t/p/w500/7V0Ebks0GgpKvQ7QbLAIdX5dos4.jpg"),
        MockMovie("tv2", "黑袍纠察队 第四季", "全8集 未删减", "电视剧", "美剧", "8.5", "https://image.tmdb.org/t/p/w500/2zmTngn1tYC1AvfnrFLhxeD82hz.jpg"),
        MockMovie("tv3", "绝命毒师", "全五季 4K 原盘", "电视剧", "美剧", "9.6", "https://image.tmdb.org/t/p/w500/ggFHVNu6YYI5L9pCfOacjizRGt.jpg"),
        MockMovie("tv4", "怪奇物语 第四季", "全9集 4K 杜比", "电视剧", "美剧", "9.0", "https://image.tmdb.org/t/p/w500/49WJfeN0moxb9IPfGn8AIqMGskD.jpg"),

        // --- 动漫：热血与奇幻 ---
        MockMovie("a1", "进击的巨人 最终季", "完结篇 4K", "动漫", "热血", "9.5", "https://image.tmdb.org/t/p/w500/hTP1IILEoBgvgzbvg1swUh9rfua.jpg"),
        MockMovie("a2", "鬼灭之刃: 柱训练篇", "全8集 1080P", "动漫", "热血", "8.8", "https://image.tmdb.org/t/p/w500/xUfRZu2mi8jH6SzQEJGP6tjBuYj.jpg"),
        MockMovie("a3", "葬送的芙莉莲", "全28集 4K 治愈", "动漫", "奇幻", "9.4", "https://image.tmdb.org/t/p/w500/dqZENchTd7lp5zht7BdlqM7RBhD.jpg"),
        MockMovie("a4", "蜘蛛侠: 纵横宇宙", "4K 高码无损", "动漫", "奇幻", "8.9", "https://image.tmdb.org/t/p/w500/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg"),
        MockMovie("a5", "千与千寻", "吉卜力 4K 修复", "动漫", "神作", "9.4", "https://image.tmdb.org/t/p/w500/39wmItIWsg5sZMyRUHLkWBcuVCM.jpg")
    )

    // 仿真直播频道列表
    val liveChannels = listOf(
        MockLiveChannel("1", "CCTV-1 综合 4K", "新闻联播", "焦点访谈", "央视频道"),
        MockLiveChannel("2", "CCTV-5 体育超清", "天下足球", "NBA 焦点战回放", "央视频道"),
        MockLiveChannel("3", "CCTV-6 电影典藏", "《星际穿越》IMAX版", "佳片有约", "央视频道"),
        MockLiveChannel("4", "湖南卫视 HD", "快乐大本营精选", "声生不息", "卫视频道"),
        MockLiveChannel("5", "浙江卫视 HD", "奔跑吧精选", "王牌对王牌", "卫视频道"),
        MockLiveChannel("6", "东方卫视 HD", "东方大剧场", "东方新闻", "卫视频道"),
        MockLiveChannel("7", "凤凰中文台 HD", "时事开讲", "军情观察室", "港澳频道"),
        MockLiveChannel("8", "TVB 翡翠台", "爱·回家之开心速递", "晚间新闻", "港澳频道")
    )
}
