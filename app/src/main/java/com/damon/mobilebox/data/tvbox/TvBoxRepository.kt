package com.damon.mobilebox.data.tvbox

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.damon.mobilebox.ui.player.VideoDetailData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Context.tvBoxDataStore by preferencesDataStore(name = "tvbox_subscription_prefs")

object TvBoxRepository {

    private val KEY_SUBSCRIPTION_URL = stringPreferencesKey("tvbox_sub_url")
    private val KEY_SUBSCRIPTION_HISTORY = stringPreferencesKey("tvbox_sub_history_json")
    private val KEY_SELECTED_SITE_KEY = stringPreferencesKey("tvbox_selected_site_key")
    private val KEY_CACHED_SITES = stringPreferencesKey("tvbox_cached_sites_json")
    private val KEY_CACHED_WAREHOUSES = stringPreferencesKey("tvbox_cached_warehouses_json")
    private val KEY_SELECTED_WAREHOUSE_URL = stringPreferencesKey("tvbox_selected_warehouse_url")
    private val KEY_CACHED_LIVES = stringPreferencesKey("tvbox_cached_lives_json")
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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

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
        val defaultSite = sortedSites.firstOrNull()
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
        for (live in lives) {
            val liveUrl = live.url ?: continue
            val result = TvBoxParser.fetchLiveChannels(liveUrl)
            if (result.isSuccess) {
                val groups = result.getOrNull() ?: emptyList()
                rawGroups.addAll(groups)
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

                if (convertedList.isNotEmpty()) {
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
