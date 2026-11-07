package com.damon.mobilebox.ui.category

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damon.mobilebox.data.tvbox.MacCmsCategory
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.data.tvbox.TvBoxSite
import com.damon.mobilebox.ui.components.PosterImage
import com.damon.mobilebox.ui.components.StandardPullToRefreshBox
import com.damon.mobilebox.ui.player.VideoDetailData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * 分类页（采集源版，2026-11-07 新建，替换 TMDB 版作为默认分类页）。
 *
 * 版式沿用上一版：**没有大海报**，只有「分类名 + 一行可横滑的竖版海报」，
 * 海报宽度 / 圆角 / 行距 / 左右边距都和首页第二屏以下取同一组数值。
 *
 * 数据全部来自用户自己配置的采集源，没有任何假数据：
 * - 左上角标题 = 当前数据源名字，点击弹出数据源列表（`currentSites`），选中即全局切换；
 * - 行 = `realCategories` 里的一级分类（`typePid == 0`，没有层级时退回整张表）；
 * - 行内海报 = `fetchCategoryVodList(site, typeId)`，每行独立请求、独立缓存。
 *
 * **为什么一行一个 `LaunchedEffect`**：`LazyColumn` 只组合屏幕内的行，请求自然按需发出。
 * 一次性并发拉 20 个分类会瞬间打满采集源的接口频率限制，首屏也会被一堆转圈拖慢。
 *
 * 行内缓存（[CategoryRowCache]）放在进程级单例、按 站点+分类 存整行结果：
 * 行滚出屏幕再滚回来、或从搜索 / 详情返回（home 曾整体移出组合）都直接命中，
 * 不会二次闪烁或重新打接口。下拉刷新会按站点清空并让所有行重新请求。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceCategoryScreen(
    onMovieClick: (VideoDetailData) -> Unit,
    modifier: Modifier = Modifier,
    onSearchClick: () -> Unit = {},
    /** 一个数据源都没有时，点按钮走订阅配置弹窗。 */
    onConfigureSource: () -> Unit = {},
    onShowToast: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val selectedSite by TvBoxRepository.selectedSite.collectAsState()
    val sites by TvBoxRepository.currentSites.collectAsState()
    val realCategories by TvBoxRepository.realCategories.collectAsState()

    // 行片单缓存在进程级单例（CategoryRowCache）里，不用页面 remember：
    // 搜索 / 详情是 NavHost 的独立 destination，跳过去时 home 整个移出组合，
    // 返回后所有 remember 都重建。缓存只活在页面里的话，可见行会全部重新发请求，
    // 于是「骨架 → Semaphore 排队 → 空结果 500ms 重试」整套再走一遍，就是回来卡一下的原因。
    // 行请求的并发闸门。采集源不是无限制的接口：同一时刻打四五个分类，
    // 服务器会偶发返回空 body（连 JSON 都不是），客户端就拿到「空分组」。
    // 22:57 截图里欧美剧 / 台湾剧 / 泰剧 全空、后面的分类却有片，就是这个原因。
    // 限到 2 路并发 + 空结果重试一次，能拿到数据又不至于排队太久。
    val requestGate = remember(selectedSite?.key) { Semaphore(2) }
    var reloadTick by remember(selectedSite?.key) { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var showSiteSheet by remember { mutableStateOf(false) }
    var categoriesFailed by remember(selectedSite?.key) { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // 一级分类：优先取 typePid == 0 的顶层；有的源是平铺表（全部 pid=0），
    // 也有的源一个顶层都不给（子分类的父 id 指向不存在的行），这时退回整张表。
    // 同名分类去重：部分源会把「电影」「电影片」这类条目重复下发。
    val primaryCategories = remember(realCategories) {
        val top = realCategories.filter { it.typePid == 0 }
        val effective = if (top.isNotEmpty()) top else realCategories
        effective
            .filter { it.typeName.isNotBlank() }
            .distinctBy { it.typeName }
    }

    // 冷启动时 initFromCache 已经拉过一次；这里兜住「那次失败 / 还没回来」的情况。
    // 下拉刷新只是把 reloadTick+1 并清空行缓存，分类树与所有的片单都由这里 / 各行重新拉，
    // 不在这里和 onRefresh 里各发一次（否则每次下拉都会打两遍接口）。
    LaunchedEffect(selectedSite?.key, reloadTick) {
        val site = selectedSite
        if (site == null) {
            refreshing = false
            return@LaunchedEffect
        }
        if (reloadTick == 0 && realCategories.isNotEmpty()) return@LaunchedEffect
        categoriesFailed = false
        val result = TvBoxRepository.fetchCategories(site)
        categoriesFailed = result.isFailure && TvBoxRepository.realCategories.value.isEmpty()
        refreshing = false
        result.exceptionOrNull()?.takeIf { it !is CancellationException }?.let {
            if (categoriesFailed) onShowToast("分类拉取失败：${it.message}")
        }
    }

    // 换数据源后回到顶部，否则会停在上一份数据的中段。
    LaunchedEffect(selectedSite?.key) {
        if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0)
    }

    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- 标题行：当前数据源（可点切换） + 搜索 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showSiteSheet = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedSite?.name ?: "未配置数据源",
                        color = scheme.onBackground,
                        // 数据源名字通常很长（「暴风影视 (17万+高清海报)」），
                        // 26sp 会把标题行撑成一块广告牌，还挤掉搜索入口。18sp 刚好压过
                        // 下面 17sp 的分类名，主次仍在。
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = "切换数据源",
                        tint = scheme.onBackground.copy(alpha = 0.55f),
                        modifier = Modifier
                            .padding(start = 2.dp)
                            .size(22.dp)
                    )
                }
                IconButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "搜索",
                        tint = scheme.onBackground,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            val site = selectedSite
            when {
                site == null -> CategoryNotice(
                    title = "还没有可用的数据源",
                    subtitle = "在设置里添加一条订阅，分类会按源里的真实分类铺开",
                    actionText = "去配置",
                    onAction = onConfigureSource
                )

                categoriesFailed -> CategoryNotice(
                    title = "分类加载失败",
                    subtitle = "这个源没有返回分类树，换一个源或重试",
                    actionText = "重试",
                    onAction = { reloadTick++ }
                )

                primaryCategories.isEmpty() -> CategorySkeleton()

                else -> StandardPullToRefreshBox(
                    isRefreshing = refreshing,
                    onRefresh = {
                        val target = selectedSite
                        if (target == null) {
                            onShowToast("当前没有激活的数据源")
                        } else {
                            // 只负责「清缓存 + 放信号」，真正的请求在上面那个
                            // LaunchedEffect（分类树）与各行（片单）里发出。
                            CategoryRowCache.invalidate(target.key)
                            refreshing = true
                            reloadTick++
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                    ) {
                        items(primaryCategories, key = { it.typeId }) { category ->
                            CategoryRowSection(
                                site = site,
                                category = category,
                                gate = requestGate,
                                reloadTick = reloadTick,
                                onMovieClick = onMovieClick
                            )
                        }
                    }
                }
            }
        }

        // ---- 底部渐隐：与首页同一套「化开」----
        // 导航栏没有托底面板，图标直接浮在页面上，内容滚到底部必须淡进底色，
        // 否则每一行海报都会在图标上方被一条硬边切掉。
        // 纯 drawBehind，不带指针输入，点击与滚动照常穿透到下面的列表。
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    val h = size.height.coerceAtLeast(1f)
                    val from = ((h - FADE_HEIGHT.toPx()) / h).coerceIn(0f, 1f)
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            from to Color.Transparent,
                            1f to scheme.background
                        )
                    )
                }
        )
    }

    if (showSiteSheet) {
        SiteSwitcherSheet(
            sites = sites,
            selectedKey = selectedSite?.key,
            onDismiss = { showSiteSheet = false },
            onPick = { site ->
                showSiteSheet = false
                if (site.key == selectedSite?.key) return@SiteSwitcherSheet
                scope.launch {
                    // selectSite 内部会清空分类与分类片单缓存，并重新拉片库 / 分类树。
                    TvBoxRepository.selectSite(site)
                }
            },
            onConfigure = {
                showSiteSheet = false
                onConfigureSource()
            }
        )
    }
}

/**
 * 分类行片单的进程级缓存。
 *
 * 搜索页 / 详情页各自是 NavHost 的独立 destination，`home` 在跳转期间会被移出组合，
 * 返回时 `AnimatedMainScreen` 连同分类页的所有 `remember` 全部重建。之前行缓存挂在
 * 页面作用域，返回后首屏可见行全都重新拉片单：先露骨架，再过 Semaphore(2) 排队，
 * 碰到空结果还要等 500ms 重试 —— 视觉上就是「页面重新加载、卡了一下」。
 *
 * 放进 object + 30 分钟 TTL：来回切页面首帧直接铺上次的海报，零网络；
 * 下拉刷新按站点清空并强制重拉；换源用 site.key 分桶互不污染。
 */
private object CategoryRowCache {
    private const val TTL_MS = 30 * 60 * 1000L
    private data class Entry(val at: Long, val items: List<VideoDetailData>)

    private val map = ConcurrentHashMap<String, Entry>()
    private fun key(siteKey: String, typeId: Int) = "$siteKey:$typeId"

    fun peek(siteKey: String, typeId: Int): List<VideoDetailData>? {
        val k = key(siteKey, typeId)
        val entry = map[k] ?: return null
        if (SystemClock.elapsedRealtime() - entry.at > TTL_MS) {
            map.remove(k)
            return null
        }
        return entry.items
    }

    fun put(siteKey: String, typeId: Int, items: List<VideoDetailData>) {
        map[key(siteKey, typeId)] = Entry(SystemClock.elapsedRealtime(), items)
    }

    fun invalidate(siteKey: String) {
        val prefix = "$siteKey:"
        map.keys.toList().forEach { if (it.startsWith(prefix)) map.remove(it) }
    }
}

/** 一个分类行：分类名 + 一行横滑海报。 */
@Composable
private fun CategoryRowSection(
    site: TvBoxSite,
    category: MacCmsCategory,
    gate: Semaphore,
    reloadTick: Int,
    onMovieClick: (VideoDetailData) -> Unit
) {
    // 初值直接读进程级缓存：返回分类页的第一帧就有海报，不会先闪一排骨架。
    var items by remember(site.key, category.typeId, reloadTick) {
        mutableStateOf<List<VideoDetailData>?>(
            if (reloadTick == 0) CategoryRowCache.peek(site.key, category.typeId) else null
        )
    }
    LaunchedEffect(site.key, category.typeId, reloadTick) {
        // 刷新过（缓存已清空）就一定要走网络；否则命中缓存直接铺。
        if (reloadTick == 0) {
            CategoryRowCache.peek(site.key, category.typeId)?.let {
                items = it
                return@LaunchedEffect
            }
        }
        val list = loadCategoryRow(site, category.typeId, gate)
        CategoryRowCache.put(site.key, category.typeId, list)
        items = list
    }

    val scheme = MaterialTheme.colorScheme
    val row = items

    // 空分组整行不展示 —— 连标题也不留。
    // 采集源的分类树是「一份站点定义」，和当前真实有片的分类并不一致：
    // 欧美剧 / 台湾剧 / 泰剧 这类挂在树里但站点根本没上片，留一行光秃秃的标题
    // 只会在列表里凿出一串空白（23:57 截图）。等数据回来且非空才占位。
    if (row == null || row.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
            Text(
                text = category.typeName,
                color = scheme.onBackground,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            )
            if (row == null) {
                RowSkeleton()
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        count = row.size,
                        key = { index -> "${site.key}:${row[index].id}:$index" }
                    ) { index ->
                        SourcePoster(entry = row[index], onClick = onMovieClick)
                    }
                }
            }
        }
    }
}

/**
 * 拉一行片单。空结果会再试一次 —— 采集源在并发下偶尔直接吐空 body，
 * 一次空不等于这个分类真的没片，直接把它从列表里抹掉会误伤有内容的分类。
 */
private suspend fun loadCategoryRow(
    site: TvBoxSite,
    typeId: Int,
    gate: Semaphore
): List<VideoDetailData> {
    suspend fun once() = gate.withPermit {
        TvBoxRepository.fetchCategoryVodList(site, typeId.toString()).getOrNull().orEmpty()
    }
    val first = once()
    if (first.isNotEmpty()) return first
    delay(500)
    return once()
}

/** 竖版海报卡：尺寸 / 圆角 / 标题字号与首页 `PosterTile` 逐项对齐。 */
@Composable
private fun SourcePoster(entry: VideoDetailData, onClick: (VideoDetailData) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .width(112.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick(entry) }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.onSurface.copy(alpha = 0.06f))
        ) {
            // 采集源没有海报时用占位图顶上，按条目 id 稳定挑图。
            PosterImage(
                url = entry.posterUrl,
                title = entry.title,
                seed = entry.id,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = entry.title,
            color = scheme.onBackground.copy(alpha = 0.92f),
            fontSize = 12.sp,
            lineHeight = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
    }
}

/** 数据源切换面板。列出用户配置的全部源，当前生效的打勾。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SiteSwitcherSheet(
    sites: List<TvBoxSite>,
    selectedKey: String?,
    onDismiss: () -> Unit,
    onPick: (TvBoxSite) -> Unit,
    onConfigure: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "切换数据源",
                color = scheme.onSurface,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp)
            )
            Text(
                text = "分类与片单会跟着数据源一起换",
                color = scheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            )

            if (sites.isEmpty()) {
                CategoryNotice(
                    title = "还没有配置数据源",
                    subtitle = "添加订阅后这里会列出全部站点",
                    actionText = "去配置",
                    onAction = onConfigure
                )
            } else {
                sites.forEach { site ->
                    val selected = site.key == selectedKey
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(site) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = site.name,
                                color = if (selected) scheme.primary else scheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = site.typeLabel,
                                color = scheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (selected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = scheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 空态 / 失败态：一句标题 + 一句说明 + 一个胶囊按钮。 */
@Composable
private fun CategoryNotice(
    title: String,
    subtitle: String,
    actionText: String,
    onAction: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = scheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                color = scheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(scheme.onSurface.copy(alpha = 0.08f))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(actionText, color = scheme.onBackground, fontSize = 13.sp)
            }
        }
    }
}

/** 分类树还没回来：三行占位，避免白屏一跳。 */
@Composable
private fun CategorySkeleton() {
    Column(Modifier.fillMaxSize()) {
        repeat(3) {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Box(
                    Modifier
                        .padding(start = 20.dp)
                        .width(72.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                )
                Spacer(Modifier.height(12.dp))
                RowSkeleton()
            }
        }
    }
}

/** 单行的海报位占位。 */
@Composable
private fun RowSkeleton() {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(5) {
            Box(
                Modifier
                    .width(112.dp)
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            )
        }
    }
}

/** 底部渐隐的高度。与首页 `TmdbHomeContent` 的 110dp 取齐，两页滚到底的观感才一致。 */
private val FADE_HEIGHT = 110.dp
