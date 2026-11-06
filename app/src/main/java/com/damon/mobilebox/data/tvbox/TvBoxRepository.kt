package com.damon.mobilebox.data.tvbox

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.damon.mobilebox.ui.player.VideoDetailData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull

private val Context.tvBoxDataStore by preferencesDataStore(name = "tvbox_subscription_prefs")

object TvBoxRepository {

    private val KEY_SUBSCRIPTION_URL = stringPreferencesKey("tvbox_sub_url")
    private val KEY_SELECTED_SITE_KEY = stringPreferencesKey("tvbox_selected_site_key")
    private val KEY_CACHED_SITES = stringPreferencesKey("tvbox_cached_sites_json")

    private val gson = Gson()

    // 内存共享：当前点击选中的影视详情数据（支持跨页面共享元素精准寻址）
    var selectedDetail: VideoDetailData? = null

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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * 加载并解析用户的 TVBox 订阅链接
     */
    suspend fun loadSubscription(context: Context, url: String): Result<List<TvBoxSite>> {
        _isLoading.value = true
        return try {
            val result = TvBoxParser.fetchSubscription(url)
            if (result.isSuccess) {
                val config = result.getOrNull()
                // 筛选出所有标准可直接请求的站源 (Type 1 或 Type 0 为通用苹果CMS标准API)
                val validSites = config?.sites?.filter { it.type == 0 || it.type == 1 || it.api.contains("api.php") }
                    ?: emptyList()

                if (validSites.isEmpty()) {
                    return Result.failure(Exception("订阅解析成功，但未找到可用的视频站点源"))
                }

                _currentSites.value = validSites
                _selectedSite.value = validSites.first()

                // 持久化存储
                context.tvBoxDataStore.edit { prefs ->
                    prefs[KEY_SUBSCRIPTION_URL] = url
                    prefs[KEY_SELECTED_SITE_KEY] = validSites.first().key
                    prefs[KEY_CACHED_SITES] = gson.toJson(validSites)
                }

                // 立即拉取选中站点的首批真实数据与分类树
                fetchRealVodData(validSites.first())
                fetchCategories(validSites.first())

                Result.success(validSites)
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("订阅解析失败"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * 切换当前激活站点
     */
    suspend fun selectSite(site: TvBoxSite) {
        _selectedSite.value = site
        _realCategories.value = emptyList()
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
     * 针对指定分类 ID 独立拉取影片列表 (不覆盖首页流，用于分类页精准过滤)
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
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        _currentSites.value = emptyList()
        _selectedSite.value = null
        _realVodList.value = emptyList()
        _realCategories.value = emptyList()
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

                _realVodList.value = convertedList
                response?.categories?.let {
                    if (it.isNotEmpty()) {
                        _realCategories.value = it
                    }
                }

                // 如果此时分类列表仍为空，补充拉取分类树
                if (_realCategories.value.isEmpty()) {
                    fetchCategories(site)
                }

                Result.success(convertedList)
            } else {
                Result.failure(result.exceptionOrNull() ?: Exception("拉取数据失败"))
            }
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
            val savedUrl = prefs[KEY_SUBSCRIPTION_URL]
            val cachedJson = prefs[KEY_CACHED_SITES]

            // 如果没有用户主动保存的订阅链接，则坚决视为空白初始状态，并清空历史旧缓存
            if (savedUrl.isNullOrBlank()) {
                clearSubscription(context)
                return
            }

            if (!cachedJson.isNullOrEmpty()) {
                val type = object : TypeToken<List<TvBoxSite>>() {}.type
                val sites: List<TvBoxSite>? = gson.fromJson(cachedJson, type)
                if (!sites.isNullOrEmpty()) {
                    _currentSites.value = sites
                    val selectedKey = prefs[KEY_SELECTED_SITE_KEY]
                    val chosen = sites.find { it.key == selectedKey } ?: sites.first()
                    _selectedSite.value = chosen
                    fetchRealVodData(chosen)
                    fetchCategories(chosen)
                    return
                }
            }

            // 如果缓存为空但有保存的订阅链接，重新拉取一次
            loadSubscription(context, savedUrl)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
