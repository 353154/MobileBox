package com.damon.mobilebox.data.tvbox

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.damon.mobilebox.BuildConfig
import com.damon.mobilebox.ui.player.VideoDetailData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private val Context.tvBoxDataStore by preferencesDataStore(name = "tvbox_subscription_prefs")

object TvBoxRepository {

    private val KEY_SUBSCRIPTION_URL = stringPreferencesKey("tvbox_sub_url")
    private val KEY_SUBSCRIPTION_HISTORY = stringPreferencesKey("tvbox_sub_history_json")
    private val KEY_SELECTED_SITE_KEY = stringPreferencesKey("tvbox_selected_site_key")
    private val KEY_CACHED_SITES = stringPreferencesKey("tvbox_cached_sites_json")
    private val KEY_CACHED_WAREHOUSES = stringPreferencesKey("tvbox_cached_warehouses_json")
    private val KEY_SELECTED_WAREHOUSE_URL = stringPreferencesKey("tvbox_selected_warehouse_url")
    private val KEY_CACHED_LIVES = stringPreferencesKey("tvbox_cached_lives_json")
    private val KEY_CACHED_EPG_URLS = stringPreferencesKey("tvbox_cached_epg_urls_json")
    private val KEY_CACHED_RAW_LIVES = stringPreferencesKey("tvbox_cached_raw_lives_json")

    private val gson = Gson()

    // 内存共享：当前点击选中的影视详情数据（支持跨页面共享元素精准寻址）
    var selectedDetail: VideoDetailData? = null

    // 当前已保存并生效的订阅 URL
    private val _savedSubscriptionUrl = MutableStateFlow("")
    val savedSubscriptionUrl: StateFlow<String> = _savedSubscriptionUrl.asStateFlow()

    // 原始直播配置源列表 (用于下拉刷新)
    private val _rawLives = MutableStateFlow<List<TvBoxLive>>(emptyList())
    val rawLives: StateFlow<List<TvBoxLive>> = _rawLives.asStateFlow()

    // 历史订阅记录列表 (最多保留 10 条)
    private val _subscriptionHistory = MutableStateFlow<List<String>>(emptyList())
    val subscriptionHistory: StateFlow<List<String>> = _subscriptionHistory.asStateFlow()

    // 若解析到多仓，保存多仓子线路
    private val _currentWarehouses = MutableStateFlow<List<TvBoxWarehouse>>(emptyList())
    val currentWarehouses: StateFlow<List<TvBoxWarehouse>> = _currentWarehouses.asStateFlow()

    private val _selectedWarehouse = MutableStateFlow<TvBoxWarehouse?>(null)
    val selectedWarehouse: StateFlow<TvBoxWarehouse?> = _selectedWarehouse.asStateFlow()

    // 严禁内置任何虚假/默认数据源，必须由用户自主配置或从订阅解析
    private val _currentSites = MutableStateFlow<List<TvBoxSite>>(emptyList())
    val currentSites: StateFlow<List<TvBoxSite>> = _currentSites.asStateFlow()

    private val _selectedSite = MutableStateFlow<TvBoxSite?>(null)
    val selectedSite: StateFlow<TvBoxSite?> = _selectedSite.asStateFlow()

    // 实时抓取到的真实视频数据列表 (无任何 Mock 数据混入)
    private val _realVodList = MutableStateFlow<List<VideoDetailData>>(emptyList())
    val realVodList: StateFlow<List<VideoDetailData>> = _realVodList.asStateFlow()

    // 实时抓取到的分类列表
    private val _realCategories = MutableStateFlow<List<MacCmsCategory>>(emptyList())
    val realCategories: StateFlow<List<MacCmsCategory>> = _realCategories.asStateFlow()

    // 真实电视直播频道列表与分组
    private val _liveGroups = MutableStateFlow<List<LiveChannelGroup>>(emptyList())
    val liveGroups: StateFlow<List<LiveChannelGroup>> = _liveGroups.asStateFlow()

    private val _liveChannels = MutableStateFlow<List<LiveChannel>>(emptyList())
    val liveChannels: StateFlow<List<LiveChannel>> = _liveChannels.asStateFlow()

    // 归一化 key -> 当天节目表；无 EPG 地址或拉取失败时为空，UI 据此整行不显示节目信息
    private val _epgPrograms = MutableStateFlow<Map<String, List<EpgSlot>>>(emptyMap())
    val epgPrograms: StateFlow<Map<String, List<EpgSlot>>> = _epgPrograms.asStateFlow()

    // TVBox 常见 EPG 是「按频道 + 日期查询」的模板（URL 含 {name}/{date}），
    // 不能像静态 XMLTV 那样一次拉全量；保存模板，由 UI 按可见频道按需查询。
    // 做成 StateFlow：initFromCache 是异步的，直播页可能先组合完。
    // UI 以它为 LaunchedEffect 的 key，模板就绪时自动补查，避免竞态导致节目单永远为空。
    private val _epgTemplate = MutableStateFlow<String?>(null)
    val epgTemplate: StateFlow<String?> = _epgTemplate.asStateFlow()
    private val epgLock = Any()
    /** 归一化频道 key -> 已查询的 yyyyMMdd，避免同一频道当天反复请求 */
    private val epgFetchedDay = ConcurrentHashMap<String, String>()

    // 订阅源可能同时提供多个 EPG 模板（配置 lives[].epg + M3U 头 url-tvg），
    // 且旧缓存里可能残留已失效的域名。这里保留全部候选并逐个尝试，
    // 记住上次成功的模板优先命中，避免每个频道都先撞一次超时。
    @Volatile
    private var epgTemplateCandidates: List<String> = emptyList()
    @Volatile
    private var successfulTemplate: String? = null
    /** 模板连续失败次数：失败够多就本次会话不再尝试，直接走全局兜底，避免每个频道都撞超时 */
    private val epgTemplateFailures = ConcurrentHashMap<String, Int>()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 应用上下文：供 selectSite 持久化用户选中的站点、以及 ensureChannelEpg 的全局节目单兜底使用
    @Volatile
    private var appContext: Context? = null
    // initFromCache 幂等：详情页返回时 AnimatedMainScreen 会重新组合，
    // 旧实现每次都重跑恢复逻辑 -> 覆盖 _selectedSite、重新拉片库 -> 数据源被重置 + 海报卡片抖动
    private val cacheInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

    // 分类影片两级内存缓存池：Key 为 "${site.key}_${categoryId ?: "0"}"
    private val categoryVodCache = java.util.concurrent.ConcurrentHashMap<String, List<VideoDetailData>>()

    // 当前选中的分类影片列表 StateFlow (全局持久托管，页面回退 0ms 恢复，绝不丢状态)
    private val _categoryVodList = MutableStateFlow<List<VideoDetailData>>(emptyList())
    val categoryVodList: StateFlow<List<VideoDetailData>> = _categoryVodList.asStateFlow()

    private val _isCategoryLoading = MutableStateFlow(false)
    val isCategoryLoading: StateFlow<Boolean> = _isCategoryLoading.asStateFlow()

    // 记录当前已加载的分类 Key
    private var currentCategoryKey: String = ""

    /**
     * 加载并解析用户的 TVBox 订阅链接 (兼容单仓与多仓)
     */
    suspend fun loadSubscription(context: Context, url: String): Result<List<TvBoxSite>> {
        appContext = context.applicationContext
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) {
            return Result.failure(Exception("订阅链接不能为空"))
        }

        _isLoading.value = true
        return try {
            val result = TvBoxParser.fetchSubscription(trimmedUrl)
            if (result.isFailure) {
                return Result.failure(result.exceptionOrNull() ?: Exception("订阅解析失败"))
            }

            // 更新历史记录
            val currentHistory = _subscriptionHistory.value.toMutableList()
            currentHistory.remove(trimmedUrl)
            currentHistory.add(0, trimmedUrl)
            val updatedHistory = currentHistory.take(10)
            _subscriptionHistory.value = updatedHistory
            _savedSubscriptionUrl.value = trimmedUrl

            when (val subData = result.getOrThrow()) {
                is TvBoxSubscriptionResult.MultiWarehouse -> {
                    // 多仓订阅
                    val warehouses = subData.warehouses
                    _currentWarehouses.value = warehouses
                    if (warehouses.isEmpty()) {
                        return Result.failure(Exception("多仓订阅中未发现任何有效子线路"))
                    }

                    // 默认选择第一条子线路展开
                    val firstWarehouse = warehouses.first()
                    _selectedWarehouse.value = firstWarehouse

                    // 异步请求该子仓的单仓配置
                    val singleResult = loadSingleConfigFromWarehouse(context, firstWarehouse)

                    // 持久化保存
                    context.tvBoxDataStore.edit { prefs ->
                        prefs[KEY_SUBSCRIPTION_URL] = trimmedUrl
                        prefs[KEY_SUBSCRIPTION_HISTORY] = gson.toJson(updatedHistory)
                        prefs[KEY_CACHED_WAREHOUSES] = gson.toJson(warehouses)
                        prefs[KEY_SELECTED_WAREHOUSE_URL] = firstWarehouse.url
                    }

                    singleResult
                }
                is TvBoxSubscriptionResult.SingleConfig -> {
                    // 单仓标准订阅
                    _currentWarehouses.value = emptyList()
                    _selectedWarehouse.value = null
                    val sites = applySingleConfig(context, subData.config, trimmedUrl)

                    // 持久化保存
                    context.tvBoxDataStore.edit { prefs ->
                        prefs[KEY_SUBSCRIPTION_URL] = trimmedUrl
                        prefs[KEY_SUBSCRIPTION_HISTORY] = gson.toJson(updatedHistory)
                        prefs.remove(KEY_CACHED_WAREHOUSES)
                        prefs.remove(KEY_SELECTED_WAREHOUSE_URL)
                    }

                    Result.success(sites)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * 切换多仓中的子线路
     */
    suspend fun selectWarehouse(context: Context, warehouse: TvBoxWarehouse): Result<List<TvBoxSite>> {
        _selectedWarehouse.value = warehouse
        _isLoading.value = true
        return try {
            val res = loadSingleConfigFromWarehouse(context, warehouse)
            context.tvBoxDataStore.edit { prefs ->
                prefs[KEY_SELECTED_WAREHOUSE_URL] = warehouse.url
            }
            res
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun loadSingleConfigFromWarehouse(context: Context, warehouse: TvBoxWarehouse): Result<List<TvBoxSite>> {
        val res = TvBoxParser.fetchSubscription(warehouse.url)
        if (res.isFailure) {
            return Result.failure(res.exceptionOrNull() ?: Exception("子线路拉取失败: ${warehouse.name}"))
        }
        val data = res.getOrThrow()
        val config = when (data) {
            is TvBoxSubscriptionResult.SingleConfig -> data.config
            is TvBoxSubscriptionResult.MultiWarehouse -> {
                // 如果子仓仍然是多仓，递归取其第一个子源
                val deeperWarehouse = data.warehouses.firstOrNull()
                    ?: return Result.failure(Exception("子仓中无有效资源"))
                val deeperRes = TvBoxParser.fetchSubscription(deeperWarehouse.url).getOrNull()
                if (deeperRes is TvBoxSubscriptionResult.SingleConfig) deeperRes.config else null
            }
        } ?: return Result.failure(Exception("无法解析子线路 ${warehouse.name} 的内容"))

        val sites = applySingleConfig(context, config, warehouse.url)
        return Result.success(sites)
    }

    /**
     * 将单仓配置应用至内存与持久化
     */
    private suspend fun applySingleConfig(context: Context, config: TvBoxConfig, sourceUrl: String): List<TvBoxSite> {
        val rawSites = config.sites ?: emptyList()
        // 智能优选排布：将通用 CMS 采集源置前，爬虫源居后，支持全量展示
        val sortedSites = rawSites.sortedBy { site ->
            if (site.type == 0 || site.type == 1 || site.api.contains("provide/vod") || site.api.contains("api.php")) 0 else 1
        }

        _currentSites.value = sortedSites
        // 保留用户已保存的选择：只有 key 不在新列表里时才回退到第一个站点。
        // 旧实现无条件取 firstOrNull 并覆盖 KEY_SELECTED_SITE_KEY，导致每次订阅刷新都重置成「暴风影视」。
        val savedKey = context.tvBoxDataStore.data.firstOrNull()?.get(KEY_SELECTED_SITE_KEY)
        val defaultSite = sortedSites.find { it.key == savedKey } ?: sortedSites.firstOrNull()
        _selectedSite.value = defaultSite

        // 处理直播源
        val lives = config.lives ?: emptyList()
        _rawLives.value = lives
        if (lives.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                syncLiveStreams(context, lives)
            }
        } else {
            _liveGroups.value = emptyList()
            _liveChannels.value = emptyList()
        }

        // 持久化存储
        context.tvBoxDataStore.edit { prefs ->
            prefs[KEY_CACHED_SITES] = gson.toJson(sortedSites)
            prefs[KEY_CACHED_RAW_LIVES] = gson.toJson(lives)
            if (defaultSite != null) {
                prefs[KEY_SELECTED_SITE_KEY] = defaultSite.key
            }
        }

        // 立即拉取默认选中站点的首批真实数据与分类树
        if (defaultSite != null) {
            fetchRealVodData(defaultSite)
            fetchCategories(defaultSite)
        }

        return sortedSites
    }

    /**
     * 自动同步并解析 TVBox 配置中的直播流 (智能去重与多线路聚合)
     */
    suspend fun syncLiveStreams(context: Context, lives: List<TvBoxLive>) {
        val rawGroups = mutableListOf<LiveChannelGroup>()
        val epgUrls = mutableSetOf<String>()
        for (live in lives) {
            // 订阅配置 lives[].epg 是 TVBox 标准 EPG 入口
            live.epg?.takeIf { it.isNotBlank() }?.let { epgUrls.add(it.trim()) }
            val liveUrl = live.url ?: continue
            val result = TvBoxParser.fetchLiveChannels(liveUrl)
            if (result.isSuccess) {
                val parsed = result.getOrNull() ?: continue
                rawGroups.addAll(parsed.groups)
                // M3U 头 #EXTM3U 的 url-tvg / x-tvg-url
                epgUrls.addAll(parsed.epgUrls)
            }
        }
        if (rawGroups.isNotEmpty()) {
            val finalGroups = deduplicateGroups(rawGroups)
            _liveGroups.value = finalGroups
            _liveChannels.value = finalGroups.flatMap { it.channels }
            try {
                context.tvBoxDataStore.edit { prefs ->
                    prefs[KEY_CACHED_LIVES] = gson.toJson(finalGroups)
                }
            } catch (e: Exception) { /* ignore */ }
        }
        try {
            context.tvBoxDataStore.edit { prefs ->
                prefs[KEY_CACHED_EPG_URLS] = gson.toJson(epgUrls.toList())
            }
        } catch (e: Exception) { /* ignore */ }
        // 有地址才查询；没有地址则清空，UI 直接不显示节目行
        loadEpg(epgUrls.toList())
    }

    /** 区分两类 EPG 源：静态 XMLTV 全量拉取 / 模板按频道查询；无地址一律不发请求 */
    private fun loadEpg(urls: List<String>) {
        val distinct = urls.map { it.trim() }
            .filter { it.startsWith("http", ignoreCase = true) }
            .distinct()
            .take(6)
        val templates = distinct.filter {
            it.contains("{name}", ignoreCase = true) ||
                it.contains("{ch}", ignoreCase = true) ||
                it.contains("{date}", ignoreCase = true)
        }
        val staticUrls = distinct - templates.toSet()

        // 与当前订阅同域的模板优先（手机缓存里常残留旧订阅的失效域名）
        val subHost = runCatching { URL(_savedSubscriptionUrl.value).host }.getOrNull()
        val newCandidates = templates.sortedByDescending { t ->
            if (!subHost.isNullOrBlank() && t.contains(subHost, ignoreCase = true)) 1 else 0
        }
        if (newCandidates != epgTemplateCandidates) {
            epgTemplateCandidates = newCandidates
            synchronized(epgLock) {
                epgFetchedDay.clear()
                successfulTemplate = null
            }
            _epgTemplate.value = newCandidates.firstOrNull()
            Log.i("MB-EPG", "模板 ${newCandidates.size} 个，首选 ${newCandidates.firstOrNull()}")
        }
        val newTemplate = newCandidates.firstOrNull()
        if (newTemplate == null && staticUrls.isEmpty()) {
            // 订阅里没有任何 EPG 地址也要预取：全局 XMLTV 兜底不依赖配置
            _epgPrograms.value = emptyMap()
            prefetchDefaultChannels()
            return
        }
        // 不依赖直播页是否被组合：模板就绪后直接在数据层为首屏频道预取节目单。
        // ensureChannelEpg 内部按「频道 + 日期」去重，重复调用不会产生额外请求。
        prefetchDefaultChannels()
        if (staticUrls.isEmpty()) return   // 纯模板模式：结果由 ensureChannelEpg 按需写入

        CoroutineScope(Dispatchers.IO).launch {
            val merged = mutableMapOf<String, MutableList<EpgSlot>>()
            // 保留按需查询已写入的频道，避免被静态源结果整体覆盖
            _epgPrograms.value.forEach { (k, v) -> merged[k] = v.toMutableList() }
            for (url in staticUrls) {
                val parsed = TvBoxParser.fetchEpg(url).getOrNull() ?: continue
                for ((key, slots) in parsed) {
                    merged.getOrPut(key) { mutableListOf() }.addAll(slots)
                }
            }
            _epgPrograms.value = merged.mapValues { (_, slots) ->
                slots.distinctBy { it.startMillis }.sortedBy { it.startMillis }
            }
        }
    }

    /** 频道在 EPG 索引里可能出现的所有 key（tvg-id / 原始名 / 去掉清晰度后缀的名字） */
    private fun epgKeys(channel: LiveChannel): List<String> = buildList {
        channel.tvgId?.let { add(it) }
        add(channel.name)
        add(channel.name.replace(Regex("""\d+[KkPp]"""), ""))
    }.map { EpgPrograms.normalize(it) }.filter { it.isNotEmpty() }.distinct()

    /**
     * 节目单预取。
     *
     * 全局 XMLTV 是本地数据（已下载/已缓存），一次就能覆盖绝大多数频道，
     * 所以先用它把所有频道一次性铺满；只有它没有的频道（港澳 / 日韩等）
     * 才逐个走订阅模板，避免「每个频道各发一次注定失败的请求」。
     */
    private fun prefetchDefaultChannels() {
        val channels = _liveChannels.value.distinctBy { EpgPrograms.normalize(it.name) }
        if (channels.isEmpty()) return
        val ctx = appContext
        CoroutineScope(Dispatchers.IO).launch {
            if (ctx != null) {
                val global = EpgGlobalSource.load(ctx)
                if (global.isNotEmpty()) {
                    val updated = _epgPrograms.value.toMutableMap()
                    var filled = 0
                    for (channel in channels) {
                        val keys = epgKeys(channel)
                        val slots = keys.firstNotNullOfOrNull { global[it] } ?: continue
                        keys.forEach { updated[it] = slots }
                        filled++
                    }
                    synchronized(epgLock) { _epgPrograms.value = updated }
                    Log.i("MB-EPG", "全局节目单覆盖 $filled/${channels.size} 个频道")
                }
            }
            // 全局源没覆盖到的频道（XMLTV 里没有这些台）再按模板补
            val covered = _epgPrograms.value.keys
            channels.filter { channel -> epgKeys(channel).none { it in covered } }
                .take(12)
                .forEach { ensureChannelEpg(it) }
        }
    }

    /**
     * UI 渲染频道时调用：只有存在 EPG 模板才查询，同一频道当天只请求一次，
     * 结果写回 epgPrograms（UI 侧 lookup 逻辑无需改动）。查不到 / 失败则留空，整行不显示。
     * LazyColumn 只组合可见项，因此请求数约等于屏幕上的频道数，不会全量轰炸接口。
     */
    fun ensureChannelEpg(channel: LiveChannel) {
        val key = EpgPrograms.normalize(channel.name)
        if (key.isEmpty()) return
        val now = System.currentTimeMillis()
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date(now))
        val preferred = successfulTemplate
        // 连续失败的模板本次会话直接跳过：订阅里的 EPG 域名一旦失效，
        // 每个频道都去撞一次握手超时会把首屏拖慢好几秒。
        val candidates = buildList {
            preferred?.let { add(it) }
            addAll(epgTemplateCandidates.filter { it != preferred })
        }.filter { (epgTemplateFailures[it] ?: 0) < 3 }
        val ctx = appContext
        if (candidates.isEmpty() && ctx == null) return
        val dayKey = day
        synchronized(epgLock) {
            if (epgFetchedDay[key] == dayKey) return
            epgFetchedDay[key] = dayKey
        }
        CoroutineScope(Dispatchers.IO).launch {
            val candidateKeys = buildList {
                channel.tvgId?.let { add(it) }
                add(channel.name)
                add(channel.name.replace(Regex("""\d+[KkPp]"""), ""))
            }.map { EpgPrograms.normalize(it) }.filter { it.isNotEmpty() }

            // 1. 先走全局 XMLTV 兜底：命中就是本地内存查询，零网络、零超时。
            //    订阅里的 EPG 域名 2026-11 起已相继失效，先撞模板会让每个频道白等 1~2 秒。
            var slots: List<EpgSlot>? = null
            var source: String? = null
            if (ctx != null) {
                val global = EpgGlobalSource.load(ctx, now)
                if (global.isNotEmpty()) {
                    slots = candidateKeys.firstNotNullOfOrNull { global[it] }
                    if (slots != null) source = "全局XMLTV"
                }
            }
            // 2. 全局源没有这个频道（或整源不可用）时，再回落到订阅模板
            if (slots.isNullOrEmpty()) {
                for (t in candidates) {
                    val result = TvBoxParser.fetchChannelEpg(t, channel.name, now).getOrNull()
                    if (!result.isNullOrEmpty()) {
                        slots = result
                        source = t
                        epgTemplateFailures[t] = 0
                        break
                    }
                    val fails = (epgTemplateFailures[t] ?: 0) + 1
                    epgTemplateFailures[t] = fails
                    Log.i("MB-EPG", "模板失败 ${channel.name} <- $t ($fails)")
                }
            }
            val resolved = slots
            if (resolved.isNullOrEmpty()) {
                // 真的没有：清掉当天标记，下次进入页面仍可重试
                synchronized(epgLock) { epgFetchedDay.remove(key) }
                Log.i("MB-EPG", "无节目单 ${channel.name}")
                return@launch
            }
            if (source != null && source != "全局XMLTV") successfulTemplate = source
            Log.i("MB-EPG", "命中 ${channel.name} ${resolved.size} 档 <- $source")
            synchronized(epgLock) {
                val updated = _epgPrograms.value.toMutableMap()
                candidateKeys.forEach { updated[it] = resolved }
                _epgPrograms.value = updated
            }
        }
    }

    /**
     * 跨站点搜索：并发向所有可搜索站点发 `ac=videolist&wd=` 请求，合并去重后返回。
     *
     * 每个站只取第一页（20 条）。同名影片只保留信息最全的一条（总集数最多），
     * 名称完全匹配的排在最前 —— 各站 id 是自增的、会互相撞，所以列表 key 用 [SearchHit.key]。
     */
    suspend fun searchAll(keyword: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val word = keyword.trim()
        if (word.isEmpty()) return@withContext emptyList()
        val sites = _currentSites.value.filter {
            it.searchable != 0 && it.api.startsWith("http", ignoreCase = true)
        }
        if (sites.isEmpty()) return@withContext emptyList()

        val merged = coroutineScope {
            sites.map { site ->
                async {
                    runCatching {
                        // 8 秒封顶：某个站点卡住时不能把整个搜索拖到十几秒
                        TvBoxParser.fetchMacCmsVodList(
                            site.api,
                            page = 1,
                            keyword = word,
                            callTimeoutMs = 8_000
                        ).getOrNull()?.list.orEmpty()
                            .map { vod ->
                                SearchHit(
                                    detail = TvBoxParser.parseMacCmsVodToDetail(vod),
                                    siteKey = site.key,
                                    siteName = site.name
                                )
                            }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
        if (merged.isEmpty()) return@withContext emptyList()

        merged
            .groupBy { it.detail.title.trim() }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.detail.routes.sumOf { r -> r.episodes.size } } }
            .sortedWith(
                compareByDescending<SearchHit> { it.detail.title.trim() == word }
                    .thenByDescending { it.detail.routes.sumOf { r -> r.episodes.size } }
            )
    }

    /**
     * 对跨数据源 (如同时存在 TXT 和 M3U 直播源) 的同组同名电视台进行去重与多线路归一化聚合
     */
    fun deduplicateGroups(rawGroups: List<LiveChannelGroup>): List<LiveChannelGroup> {
        val mergedGroupMap = linkedMapOf<String, LinkedHashMap<String, LiveChannel>>()
        for (group in rawGroups) {
            val channelMap = mergedGroupMap.getOrPut(group.groupName) { linkedMapOf() }
            for (channel in group.channels) {
                val existing = channelMap[channel.name]
                if (existing == null) {
                    channelMap[channel.name] = channel
                } else {
                    // 合并多线路 URL (去重)
                    val combinedUrls = (existing.urls + channel.urls).distinct()
                    channelMap[channel.name] = existing.copy(
                        urls = combinedUrls,
                        url = existing.url,
                        logo = existing.logo ?: channel.logo,
                        epg = existing.epg ?: channel.epg
                    )
                }
            }
        }
        return mergedGroupMap.map { (groupName, channelMap) ->
            LiveChannelGroup(groupName = groupName, channels = channelMap.values.toList())
        }
    }

    /**
     * 切换当前激活站点
     */
    suspend fun selectSite(site: TvBoxSite) {
        _selectedSite.value = site
        // 持久化用户选择：否则 DataStore 里永远是订阅解析时的第一个站点，
        // 任何一次 initFromCache 都会把选择覆盖回去。
        appContext?.let { ctx ->
            try {
                ctx.tvBoxDataStore.edit { prefs -> prefs[KEY_SELECTED_SITE_KEY] = site.key }
            } catch (_: Exception) { }
        }
        _realCategories.value = emptyList()
        categoryVodCache.clear()
        _categoryVodList.value = emptyList()
        currentCategoryKey = ""
        fetchRealVodData(site)
        fetchCategories(site)
    }

    /**
     * 拉取当前站点的真实分类树 (ac=list)
     */
    suspend fun fetchCategories(site: TvBoxSite): Result<List<MacCmsCategory>> {
        val result = TvBoxParser.fetchMacCmsCategories(site.api)
        if (result.isSuccess) {
            val cats = result.getOrNull() ?: emptyList()
            if (cats.isNotEmpty()) {
                _realCategories.value = cats
            }
            return Result.success(cats)
        }
        return Result.failure(result.exceptionOrNull() ?: Exception("获取分类失败"))
    }

    /**
     * 加载分类影片列表 (带内存缓存穿透保护：缓存命中时 0ms 呈现，绝无二次刷新与闪烁)
     */
    suspend fun loadCategoryVods(
        site: TvBoxSite,
        categoryId: String? = null,
        forceRefresh: Boolean = false
    ): Result<List<VideoDetailData>> {
        val cacheKey = "${site.key}_${categoryId ?: "0"}"
        currentCategoryKey = cacheKey

        // 1. 若非强制刷新且内存缓存已存在，直接立刻发射缓存，0 延迟无菊花
        if (!forceRefresh) {
            val cached = categoryVodCache[cacheKey]
            if (cached != null) {
                _categoryVodList.value = cached
                _isCategoryLoading.value = false
                return Result.success(cached)
            }
        }

        // 2. 内存无缓存或强制刷新，发起网络拉取
        _isCategoryLoading.value = true
        return try {
            val result = TvBoxParser.fetchMacCmsVodList(
                api = site.api,
                categoryId = categoryId,
                page = 1
            )
            _isCategoryLoading.value = false
            if (result.isSuccess) {
                val response = result.getOrNull()
                val rawVodList = response?.list ?: emptyList()
                val convertedList = rawVodList.map { TvBoxParser.parseMacCmsVodToDetail(it) }
                categoryVodCache[cacheKey] = convertedList
                // 只有当前请求的 key 依然匹配时才更新流，防止并发乱序
                if (currentCategoryKey == cacheKey) {
                    _categoryVodList.value = convertedList
                }
                Result.success(convertedList)
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("拉取分类影片失败"))
            }
        } catch (e: CancellationException) {
            _isCategoryLoading.value = false
            throw e
        } catch (e: Exception) {
            _isCategoryLoading.value = false
            Result.failure(e)
        }
    }

    /**
     * 针对指定分类 ID 独立拉取影片列表
     */
    suspend fun fetchCategoryVodList(
        site: TvBoxSite,
        categoryId: String? = null,
        page: Int = 1
    ): Result<List<VideoDetailData>> {
        return try {
            val result = TvBoxParser.fetchMacCmsVodList(
                api = site.api,
                categoryId = categoryId,
                page = page
            )
            if (result.isSuccess) {
                val response = result.getOrNull()
                val rawVodList = response?.list ?: emptyList()
                val convertedList = rawVodList.map { TvBoxParser.parseMacCmsVodToDetail(it) }
                Result.success(convertedList)
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("拉取分类影片失败"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 清空当前所有订阅与缓存数据源
     */
    suspend fun clearSubscription(context: Context) {
        try {
            context.tvBoxDataStore.edit { prefs ->
                prefs.remove(KEY_SUBSCRIPTION_URL)
                prefs.remove(KEY_SELECTED_SITE_KEY)
                prefs.remove(KEY_CACHED_SITES)
                prefs.remove(KEY_CACHED_WAREHOUSES)
                prefs.remove(KEY_SELECTED_WAREHOUSE_URL)
                prefs.remove(KEY_CACHED_LIVES)
                prefs.remove(KEY_CACHED_RAW_LIVES)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _savedSubscriptionUrl.value = ""
        _rawLives.value = emptyList()
        _currentWarehouses.value = emptyList()
        _selectedWarehouse.value = null
        _currentSites.value = emptyList()
        _selectedSite.value = null
        _realVodList.value = emptyList()
        _realCategories.value = emptyList()
        _liveGroups.value = emptyList()
        _liveChannels.value = emptyList()
    }

    /**
     * 从当前选中的站点拉取真实影视海报与首屏流
     */
    suspend fun fetchRealVodData(
        site: TvBoxSite,
        categoryId: String? = null,
        page: Int = 1,
        keyword: String? = null
    ): Result<List<VideoDetailData>> {
        _isLoading.value = true
        return try {
            val result = TvBoxParser.fetchMacCmsVodList(
                api = site.api,
                categoryId = categoryId,
                page = page,
                keyword = keyword
            )

            if (result.isSuccess) {
                val response = result.getOrNull()
                val rawVodList = response?.list ?: emptyList()
                val convertedList = rawVodList.map { TvBoxParser.parseMacCmsVodToDetail(it) }

                // 内容相同则不发射新值：避免多余刷新让 LazyVerticalGrid 整表重组、海报抖动
                if (convertedList.isNotEmpty() && convertedList != _realVodList.value) {
                    _realVodList.value = convertedList
                }
                Result.success(convertedList)
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("未能从该站点解析到视频"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * 初始化时读取本地缓存的真实订阅与站点 (杜绝任何默认假数据源注入)
     */
    suspend fun initFromCache(context: Context) {
        appContext = context.applicationContext
        if (!cacheInitialized.compareAndSet(false, true)) return
        try {
            val prefs = context.tvBoxDataStore.data.firstOrNull() ?: return
            val savedUrl = prefs[KEY_SUBSCRIPTION_URL]?.trim() ?: ""

            // 恢复历史记录
            val historyJson = prefs[KEY_SUBSCRIPTION_HISTORY]
            if (!historyJson.isNullOrBlank()) {
                val listType = object : TypeToken<List<String>>() {}.type
                val history: List<String>? = gson.fromJson(historyJson, listType)
                if (history != null) _subscriptionHistory.value = history
            }

            // 如果没有用户主动保存的订阅链接，则坚决视为空白初始状态，并清空历史旧缓存
            if (savedUrl.isBlank()) {
                _savedSubscriptionUrl.value = ""
                _currentSites.value = emptyList()
                _selectedSite.value = null
                _realVodList.value = emptyList()
                _realCategories.value = emptyList()
                _liveGroups.value = emptyList()
                _liveChannels.value = emptyList()

                // 例外：分发版内置了默认订阅源（gradle 从 local.properties 注入）时自动订阅一次，
                // 让收到 APK 的人装上就能直接看片，不用自己去设置里找源。
                // 注意这不是「内置假数据」—— 源地址来自真实订阅，解析失败也不会伪造任何内容。
                val builtInUrl = BuildConfig.DEFAULT_SUB_URL
                if (builtInUrl.isNotBlank()) {
                    Log.i("MB-SUB", "首次启动，使用内置默认订阅: $builtInUrl")
                    val builtInResult = loadSubscription(context, builtInUrl)
                    if (builtInResult.isFailure) {
                        Log.w("MB-SUB", "内置默认订阅失败: ${builtInResult.exceptionOrNull()?.message}")
                    }
                }
                return
            }

            _savedSubscriptionUrl.value = savedUrl

            // 恢复多仓列表
            val warehouseJson = prefs[KEY_CACHED_WAREHOUSES]
            val selectedWarehouseUrl = prefs[KEY_SELECTED_WAREHOUSE_URL]
            if (!warehouseJson.isNullOrBlank()) {
                val warehouseType = object : TypeToken<List<TvBoxWarehouse>>() {}.type
                val warehouses: List<TvBoxWarehouse>? = gson.fromJson(warehouseJson, warehouseType)
                if (!warehouses.isNullOrEmpty()) {
                    _currentWarehouses.value = warehouses
                    _selectedWarehouse.value = warehouses.find { it.url == selectedWarehouseUrl } ?: warehouses.first()
                }
            }

            // 恢复站点列表
            val cachedSitesJson = prefs[KEY_CACHED_SITES]
            val selectedKey = prefs[KEY_SELECTED_SITE_KEY]
            var chosenSite: TvBoxSite? = null
            if (!cachedSitesJson.isNullOrBlank()) {
                val listType = object : TypeToken<List<TvBoxSite>>() {}.type
                val sites: List<TvBoxSite>? = gson.fromJson(cachedSitesJson, listType)
                if (!sites.isNullOrEmpty()) {
                    _currentSites.value = sites
                    chosenSite = sites.find { it.key == selectedKey } ?: sites.first()
                    _selectedSite.value = chosenSite
                }
            }

            // 恢复原始直播配置
            val rawLivesJson = prefs[KEY_CACHED_RAW_LIVES]
            if (!rawLivesJson.isNullOrBlank()) {
                val liveListType = object : TypeToken<List<TvBoxLive>>() {}.type
                val parsedLives: List<TvBoxLive>? = gson.fromJson(rawLivesJson, liveListType)
                if (parsedLives != null) {
                    _rawLives.value = parsedLives
                }
            }

            // 恢复直播列表 (执行智能去重) - 启动即刻在毫秒级还原，绝不阻塞无配置
            val cachedLivesJson = prefs[KEY_CACHED_LIVES]
            if (!cachedLivesJson.isNullOrBlank()) {
                val liveGroupType = object : TypeToken<List<LiveChannelGroup>>() {}.type
                val groups: List<LiveChannelGroup>? = gson.fromJson(cachedLivesJson, liveGroupType)
                if (!groups.isNullOrEmpty()) {
                    val finalGroups = deduplicateGroups(groups)
                    _liveGroups.value = finalGroups
                    _liveChannels.value = finalGroups.flatMap { it.channels }
                }
            }

            // 冷启动恢复：仅当缓存里存有 EPG 地址时才查询，否则保持空（不显示节目行）
            val cachedEpgUrls: List<String> = prefs[KEY_CACHED_EPG_URLS]
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        gson.fromJson(raw, object : TypeToken<List<String>>() {}.type) as? List<String>
                    }.getOrNull()
                }
                ?: emptyList()
            loadEpg(cachedEpgUrls + _rawLives.value.mapNotNull { it.epg })

            // 冷启动异步刷新一次订阅配置里的 EPG 地址：既能补齐旧缓存缺失的字段，
            // 也能让新配置的有效模板与缓存里残留的失效域名一起进入候选列表
            CoroutineScope(Dispatchers.IO).launch {
                val sub = TvBoxParser.fetchSubscription(savedUrl).getOrNull()
                val cfg = (sub as? TvBoxSubscriptionResult.SingleConfig)?.config
                val urls = cfg?.lives.orEmpty()
                    .mapNotNull { it.epg?.takeIf { value -> value.isNotBlank() } }
                if (urls.isNotEmpty()) {
                    Log.i("MB-EPG", "订阅配置 EPG 地址: $urls")
                    loadEpg(urls + cachedEpgUrls)
                }
            }

            // 异步刷新点播首页片库与分类树，绝不卡顿启动主流程
            if (chosenSite != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    fetchRealVodData(chosenSite)
                    fetchCategories(chosenSite)
                }
            }

            // 如果缓存为空但有保存的订阅链接，重新拉取一次
            if (_currentSites.value.isEmpty()) {
                loadSubscription(context, savedUrl)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 手动下拉刷新直播源，重新抓取最新的直播频道列表并智能去重
     */
    suspend fun refreshLiveStreams(context: Context): Result<List<LiveChannelGroup>> = withContext(Dispatchers.IO) {
        var lives = _rawLives.value
        if (lives.isEmpty()) {
            val prefs = context.tvBoxDataStore.data.firstOrNull()
            val rawLivesJson = prefs?.get(KEY_CACHED_RAW_LIVES)
            if (!rawLivesJson.isNullOrBlank()) {
                val liveListType = object : TypeToken<List<TvBoxLive>>() {}.type
                lives = gson.fromJson(rawLivesJson, liveListType) ?: emptyList()
                _rawLives.value = lives
            }
        }

        if (lives.isEmpty()) {
            // 尝试重新从当前订阅拉取一次配置
            val savedUrl = _savedSubscriptionUrl.value
            if (savedUrl.isNotBlank()) {
                val subResult = TvBoxParser.fetchSubscription(savedUrl)
                if (subResult.isSuccess) {
                    val config = when (val res = subResult.getOrNull()) {
                        is TvBoxSubscriptionResult.SingleConfig -> res.config
                        is TvBoxSubscriptionResult.MultiWarehouse -> {
                            val firstUrl = res.warehouses.firstOrNull()?.url
                            if (firstUrl != null) {
                                val deeper = TvBoxParser.fetchSubscription(firstUrl).getOrNull()
                                if (deeper is TvBoxSubscriptionResult.SingleConfig) deeper.config else null
                            } else null
                        }
                        null -> null
                    }
                    if (config?.lives != null) {
                        lives = config.lives
                        _rawLives.value = lives
                        try {
                            context.tvBoxDataStore.edit { it[KEY_CACHED_RAW_LIVES] = gson.toJson(lives) }
                        } catch (e: Exception) { /* ignore */ }
                    }
                }
            }
        }

        if (lives.isEmpty()) {
            return@withContext Result.failure(Exception("未在当前订阅中找到电视直播源"))
        }

        return@withContext try {
            syncLiveStreams(context, lives)
            Result.success(_liveGroups.value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
