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
        MockDataSource("ff", "非凡影视", "全网聚合采集站 · 综合类目全", false),
        MockDataSource("hn", "红牛片库", "4K 蓝光高码专区 · 原盘画质", true),
        MockDataSource("lz", "量子秒播", "高速多线 CDN · 极速加载", true),
        MockDataSource("hub", "自建 LiveHub", "个人 NAS 云端安全鉴权源", true)
    )

    // 首页精选点播仿真数据 (默认暴风片库，带真实高清海报)
    val homeVodList = listOf(
        MockMovie("1", "沙丘 2", "IMAX 4K 臻彩", "电影", "科幻", "8.5", "https://image.tmdb.org/t/p/w500/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg"),
        MockMovie("2", "奥本海默", "蓝光中字 4K", "电影", "科幻", "8.8", "https://image.tmdb.org/t/p/w500/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg"),
        MockMovie("3", "阿凡达 2: 水之道", "4K 60帧 杜比视界", "4K专区", "杜比视界", "9.0", "https://image.tmdb.org/t/p/w500/t6HIqrRAclMCA60NsSmeqe9RmNV.jpg"),
        MockMovie("4", "星际穿越", "IMAX 4K 重置", "4K专区", "IMAX", "9.4", "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg"),
        MockMovie("5", "龙之家族 第二季", "全8集 4K HDR", "电视剧", "美剧", "8.6", "https://image.tmdb.org/t/p/w500/7V0Ebks0GgpKvQ7QbLAIdX5dos4.jpg"),
        MockMovie("6", "鬼灭之刃: 柱训练篇", "全8集 1080P", "动漫", "热血", "8.8", "https://image.tmdb.org/t/p/w500/xUfRZu2mi8jH6SzQEJGP6tjBuYj.jpg"),
        MockMovie("7", "葬送的芙莉莲", "全28集 4K 治愈", "动漫", "奇幻", "9.4", "https://image.tmdb.org/t/p/w500/dqZENchTd7lp5zht7BdlqM7RBhD.jpg"),
        MockMovie("8", "蜘蛛侠: 纵横宇宙", "4K 高码无损", "动漫", "奇幻", "8.9", "https://image.tmdb.org/t/p/w500/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg"),
        MockMovie("9", "疾速追杀 4", "1080P 枪斗术典藏", "电影", "动作", "8.1", "https://image.tmdb.org/t/p/w500/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg"),
        MockMovie("10", "庆余年 第二季", "更新至第36集", "电视剧", "华语", "9.1", "")
    )

    // 切换到“红牛片库 (4K)”时的测试片单
    val hnVodList = listOf(
        MockMovie("101", "阿凡达 2: 水之道", "4K 60帧 杜比视界", "4K专区", "杜比视界", "9.0", "https://image.tmdb.org/t/p/w500/t6HIqrRAclMCA60NsSmeqe9RmNV.jpg"),
        MockMovie("102", "星际穿越", "IMAX 4K 蓝光重置", "4K专区", "IMAX", "9.4", "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg"),
        MockMovie("103", "头号玩家", "4K 杜比全景声", "4K专区", "60帧高码", "8.7", ""),
        MockMovie("104", "指环王: 王者归来", "4K HDR 加长典藏", "4K专区", "杜比视界", "9.5", ""),
        MockMovie("105", "蜘蛛侠: 纵横宇宙", "4K 高码无损", "4K专区", "IMAX", "8.9", "https://image.tmdb.org/t/p/w500/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg"),
        MockMovie("106", "沙丘 2", "IMAX 4K 极清", "4K专区", "IMAX", "8.5", "https://image.tmdb.org/t/p/w500/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg")
    )

    // 一级分类标签
    val categoryTabs = listOf("全部", "电影", "电视剧", "动漫", "综艺", "纪录片")

    // 二级子分类映射表
    val subCategoriesMap = mapOf(
        "全部" to listOf("全部", "最新上线", "高分榜单", "热播排行"),
        "电影" to listOf("全部", "动作", "科幻", "喜剧", "悬疑"),
        "电视剧" to listOf("全部", "华语", "美剧", "韩剧", "古装"),
        "动漫" to listOf("全部", "热血", "奇幻", "治愈", "国产"),
        "综艺" to listOf("全部", "音乐", "真人秀", "搞笑", "竞技"),
        "纪录片" to listOf("全部", "自然", "人文", "美食", "历史")
    )

    // 全量分类测试影视库 (包含真实网络海报 URL)
    val allCategoryMovies = listOf(
        // --- 电影 ---
        MockMovie("m1", "疾速追杀 4", "1080P 高码版", "电影", "动作", "8.1", "https://image.tmdb.org/t/p/w500/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg"),
        MockMovie("m2", "碟中谍7: 致命清算", "4K 国英双语", "电影", "动作", "8.4", ""),
        MockMovie("m3", "沙丘 2", "IMAX 4K", "电影", "科幻", "8.5", "https://image.tmdb.org/t/p/w500/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg"),
        MockMovie("m4", "奥本海默", "4K HDR 原盘", "电影", "科幻", "8.8", "https://image.tmdb.org/t/p/w500/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg"),
        MockMovie("m5", "抓娃娃", "HD 高清国语", "电影", "喜剧", "7.6", ""),
        MockMovie("m6", "飞驰人生 2", "1080P 臻彩", "电影", "喜剧", "7.7", ""),
        MockMovie("m7", "默杀", "HD 1080P", "电影", "悬疑", "7.2", ""),
        MockMovie("m8", "周处除三害", "4K 极清版", "电影", "悬疑", "8.1", ""),

        // --- 电视剧 ---
        MockMovie("tv1", "龙之家族 第二季", "全8集 4K", "电视剧", "美剧", "8.6", "https://image.tmdb.org/t/p/w500/7V0Ebks0GgpKvQ7QbLAIdX5dos4.jpg"),
        MockMovie("tv2", "庆余年 第二季", "全36集完结", "电视剧", "华语", "9.1", ""),
        MockMovie("tv3", "边水往事", "更新至第21集", "电视剧", "华语", "8.5", ""),
        MockMovie("tv4", "黑袍纠察队 第四季", "全8集 未删减", "电视剧", "美剧", "8.0", ""),
        MockMovie("tv5", "泪之女王", "全16集 1080P", "电视剧", "韩剧", "8.2", ""),
        MockMovie("tv6", "死期将至", "全8集 1080P", "电视剧", "韩剧", "8.7", ""),
        MockMovie("tv7", "长相思 第二季", "全23集完结", "电视剧", "古装", "7.8", ""),
        MockMovie("tv8", "莲花楼", "全40集 4K", "电视剧", "古装", "8.5", ""),

        // --- 动漫 ---
        MockMovie("a1", "鬼灭之刃: 柱训练篇", "全8集 1080P", "动漫", "热血", "8.8", "https://image.tmdb.org/t/p/w500/xUfRZu2mi8jH6SzQEJGP6tjBuYj.jpg"),
        MockMovie("a2", "葬送的芙莉莲", "全28集 4K", "动漫", "奇幻", "9.4", "https://image.tmdb.org/t/p/w500/dqZENchTd7lp5zht7BdlqM7RBhD.jpg"),
        MockMovie("a3", "蜘蛛侠: 纵横宇宙", "4K 高码无损", "动漫", "奇幻", "8.9", "https://image.tmdb.org/t/p/w500/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg"),
        MockMovie("a4", "咒术回战 第二季", "全23集", "动漫", "热血", "8.9", ""),
        MockMovie("a5", "迷宫饭", "全24集 1080P", "动漫", "奇幻", "8.7", ""),
        MockMovie("a6", "夏目友人帐 第七季", "连载中", "动漫", "治愈", "9.3", ""),
        MockMovie("a7", "间谍过家家 代号:白", "1080P 高清", "动漫", "治愈", "8.1", ""),
        MockMovie("a8", "仙逆", "更新至第55集", "动漫", "国产", "9.0", ""),
        MockMovie("a9", "凡人修仙传", "年番连载中", "动漫", "国产", "8.9", ""),

        // --- 综艺 ---
        MockMovie("z1", "歌手 2024", "第12期 歌王之战", "综艺", "音乐", "8.0", ""),
        MockMovie("z2", "乘风 2024", "全12期 1080P", "综艺", "音乐", "7.4", ""),
        MockMovie("z3", "种地吧 第二季", "更新至第50期", "综艺", "真人秀", "8.8", ""),
        MockMovie("z4", "花儿与少年·丝路季", "全14期 完结", "综艺", "真人秀", "9.0", ""),
        MockMovie("z5", "脱口秀和Ta的朋友们", "第8期 竞技赛", "综艺", "搞笑", "8.2", ""),
        MockMovie("z6", "喜人奇妙夜", "第10期 决赛", "综艺", "搞笑", "8.1", ""),
        MockMovie("z7", "奔跑吧 第八季", "全12期", "综艺", "竞技", "6.9", ""),
        MockMovie("z8", "极限挑战 第十季", "全12期", "综艺", "竞技", "7.0", ""),

        // --- 纪录片 ---
        MockMovie("d1", "地球脉动 第三季", "全8集 4K 杜比", "纪录片", "自然", "9.8", ""),
        MockMovie("d2", "蓝色星球 第二季", "全7集 4K 原盘", "纪录片", "自然", "9.8", ""),
        MockMovie("d3", "我的阿勒泰", "全8集 4K HDR", "纪录片", "人文", "8.9", ""),
        MockMovie("d4", "人生一串 第三季", "全6集 1080P", "纪录片", "美食", "8.6", ""),
        MockMovie("d5", "风味人间 第四季", "全6集 4K", "纪录片", "美食", "8.9", ""),
        MockMovie("d6", "河西走廊", "全10集 1080P", "纪录片", "历史", "9.7", "")
    )

    // 直播分类与频道列表
    val liveChannels = listOf(
        MockLiveChannel("cctv1", "CCTV-1 综合", "今日说法", "晚间新闻", "央视频道"),
        MockLiveChannel("cctv5", "CCTV-5 体育", "欧洲杯精选赛事", "体育新闻", "央视频道"),
        MockLiveChannel("cctv6", "CCTV-6 电影", "经典译制片: 瓦尔特保卫萨拉热窝", "佳片有约", "央视频道"),
        MockLiveChannel("cctv13", "CCTV-13 新闻", "共同关注", "东方时空", "央视频道"),
        MockLiveChannel("hunan", "湖南卫视 HD", "快乐大本营精选", "晚间剧场", "各省卫视"),
        MockLiveChannel("zhejiang", "浙江卫视 HD", "奔跑吧精选", "中国蓝剧场", "各省卫视"),
        MockLiveChannel("dragon", "东方卫视 HD", "东方大剧场", "东方新闻", "各省卫视"),
        MockLiveChannel("phoenix", "凤凰卫视中文台", "军情观察室", "时事直通车", "港澳台特约"),
        MockLiveChannel("tvb", "翡翠台 HD", "爱·回家之开心速递", "晚间新闻", "港澳台特约")
    )
}
