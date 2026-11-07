package com.damon.mobilebox.ui.person

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.damon.mobilebox.data.tmdb.TmdbPersonProfile
import com.damon.mobilebox.data.tmdb.TmdbPersonWork
import com.damon.mobilebox.data.tmdb.TmdbRepository
import com.damon.mobilebox.ui.components.PosterImage
import java.util.Locale

/**
 * 演职人员作品时间轴（2026-11-08 新增）。
 *
 * 入口只有一个：详情页「演职人员」点头像。详情页会**先确认 TMDB 查得到这个人**，
 * 查得到才导航到这里，并把结果填进 `TmdbRepository` 的进程缓存，所以正常路径首帧就有数据；
 * 只有进程被杀后直达 / 缓存过期才会在这里重新拉一次。
 *
 * **层级定案（用户 02:02 明确）**：
 * - A 层：**铺满全屏**的海报墙（+ 渐变 + 加载转圈），固定在 Box 底层，自身不位移、不淡出。
 *   整墙逆时针倾斜 -15°；墙沿自己的 Y 轴向上匀速平移，倾斜后看起来是向左上方斜向流动。
 *   动画是**独立时间动画**，与列表滚动 / 手指完全无关；**逐格取模定位**实现无限无缝循环：
 *   每格 Y = `(基准Y - drift × 周期) mod 周期`，滑出上边界立刻从循环底部补回，
 *   `RepeatMode.Restart` 在 drift=1 与 drift=0 时取模结果完全一致，绝不会「整块移走后露白」。
 * - 中间：全屏纯 surface 色遮罩，alpha 随滚动 0→1 均匀交叉淡入（详情页 A/B 同款）。
 * - B 层：**人物信息 + 时间轴同属这一层**、完全透明，一起随手指上下滚。
 *   第 0 项是等高透明占位；**A 层的白纱渐变整体落在「时间轴首行」附近**（02:34 微调），
 *   之前整片海报墙都是清晰的，演员信息压在清晰海报上、用白字 + 柔和阴影，
 *   随整屏遮罩淡入同步过渡到 onSurface 深色字。
 *   02:31 版把起点精确对齐首行顶端，结果 120dp 过渡整段压在首行**下方**、观感偏低；
 *   02:34 把渐变前移 88dp，首行顶端已收敛到约 3/4 不透明，32dp 后完全纯色。
 *   绝不做「每行刷背景块」或「空间渐变起始段」—— 那会随滚动出现硬分界线和长方块。
 *
 * X / Instagram 由 `external_ids` 带出，有值才显示，点击直接跳对应主页。
 */
@Composable
fun PersonTimelineScreen(
    personId: Int,
    initialName: String,
    onBack: () -> Unit,
    onOpenWork: (TmdbPersonWork) -> Unit
) {
    val background = MaterialTheme.colorScheme.background
    // 遮罩色照详情页用 colorScheme.surface：浅色主题下整屏「变白」，深色主题下「压暗」。
    val veilColor = MaterialTheme.colorScheme.surface
    val primary = MaterialTheme.colorScheme.onSurface
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    // 02:16 修正：0.62 太靠下，头像上方留了一大块空白（截图反馈）。回到 0.50 屏高，
    // 人物信息往上走一截；A 层墙改成铺满全屏、渐变在这个位置收敛成纯背景色，
    // 于是「演员上方」是海报墙、演员信息本身落在纯色区，文字用 onSurface 系都读得清。
    val headerHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.50f)
        .coerceIn(360.dp, 480.dp)
    val density = LocalDensity.current
    val headerHeightPx = with(density) { headerHeight.toPx() }
    // 白纱起点 = 等高占位 + 人物信息块实测高度 = 时间轴首行顶端。
    // 用 onGloballyPositioned 量高度而不是写死 dp：有无 X/IG、字体缩放、不同机型都自动对齐。
    // 高度不随列表滚动变化，所以这里不会因为回调反复重组。
    var infoBlockHeightPx by remember {
        mutableFloatStateOf(with(density) { 268.dp.toPx() })
    }
    val timelineStartPx = headerHeightPx + infoBlockHeightPx
    val listState = rememberLazyListState()
    // 第 0 项是等高占位，所以它的滚动偏移就是「用户已经往上滑了多远」。
    // 与详情页 veilProgress 同一口径：整屏遮罩 alpha 随滚动 0→1 均匀增长，
    // 系数 0.9 让海报墙被「慢慢」盖掉（详情页 hero 用 0.65）。
    val veilProgress by remember(headerHeightPx) {
        derivedStateOf {
            val scrolled = if (listState.firstVisibleItemIndex == 0) {
                listState.firstVisibleItemScrollOffset.toFloat()
            } else {
                headerHeightPx * 2f
            }
            (scrolled / (headerHeightPx * 0.9f).coerceAtLeast(1f)).coerceIn(0f, 1f)
        }
    }

    var profile by remember(personId) {
        mutableStateOf(TmdbRepository.peekPerson(personId))
    }
    var loading by remember(personId) { mutableStateOf(profile == null) }
    var failed by remember(personId) { mutableStateOf(false) }

    LaunchedEffect(personId) {
        if (profile != null) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        failed = false
        val result = runCatching {
            TmdbRepository.personProfile(personId, initialName)
        }.getOrNull()
        if (result != null) profile = result else failed = true
        loading = false
    }

    Box(modifier = Modifier.fillMaxSize().background(background)) {
        // 1. A 层：倾斜的海报墙铺满全屏，固定不动，自身不做任何 alpha / 位移变化。
        //    墙内部的取模传送带是独立时间动画，与用户滚动无关。
        PersonHeader(
            profile = profile,
            loading = loading,
            fadeStartPx = timelineStartPx,
            modifier = Modifier.fillMaxSize()
        )

        // 2. 整屏遮罩（详情页 A/B 同款）：全屏纯 surface 色矩形，alpha 随滚动 0→1
        //    均匀交叉淡入。不是白色面板从下往上盖，也不是空间上的透明→白渐变 ——
        //    整屏同一个 alpha，任何时刻都没有边界、没有渐变起始段、没有灰带。
        val veil = veilProgress
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(veilColor.copy(alpha = veil)) }
        )

        // 3. B 层：整层完全透明，内容直接浮在遮罩上；第 0 项只是等高透明占位。
        //    人物信息和时间轴一样都是列表项 —— 同一层级、一起随手指滚动。
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 36.dp)
        ) {
            item(key = "personHeaderSpacer") {
                Spacer(modifier = Modifier.fillMaxWidth().height(headerHeight))
            }

            item(key = "personInfo") {
                PersonInfoBlock(
                    profile = profile,
                    initialName = initialName,
                    primary = primary,
                    secondary = secondary,
                    veil = veil,
                    modifier = Modifier.onGloballyPositioned {
                        infoBlockHeightPx = it.size.height.toFloat()
                    }
                )
            }

            if (failed) {
                item(key = "personEmpty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp, start = 32.dp, end = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "没有找到作品年表",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "TMDB 暂时没有这位演职人员的资料，或网络不可用",
                            fontSize = 12.sp,
                            color = secondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(
                    items = profile?.works.orEmpty(),
                    key = { "${it.mediaType}:${it.tmdbId}" }
                ) { work ->
                    TimelineRow(
                        work = work,
                        dotRingColor = veilColor,
                        primary = primary,
                        secondary = secondary,
                        onClick = { onOpenWork(work) }
                    )
                }
            }
        }

        // 返回按钮独立悬浮，不随列表滚动；半透明白圆底 + 近黑箭头，明暗主题下都读得清。
        val backInteraction = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, top = 12.dp)
                .size(42.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.88f))
                .clickable(
                    interactionSource = backInteraction,
                    indication = null,
                    onClick = onBack
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color(0xFF1F2328),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * A 层：**铺满全屏**的倾斜海报墙 + 渐变 + 加载转圈，人物信息已经移到 B 层。
 *
 * 02:16 修正：墙原来只占 `headerHeight`，加上整块平移后第二组补不进来，演员上方整片露白。
 * 现在 A 层改为 `fillMaxSize()`，取模传送带铺满全屏。
 *
 * **02:26 定案（用户截图反馈「背景从演员中部就开始模糊」）**：渐变拆成互不重叠的两层：
 * 1. 顶部压暗只在 `0 ~ fadeStart × 0.5` 之间，用「黑色 alpha 0.34 → 0」保证状态栏可读，
 *    之后完全透明 —— 演员信息整块区域是**清晰海报**，不再提前发白。
 * 2. 底部白纱的**收敛段以时间轴首行的 y 为准**（`fadeStartPx`）：02:31 起点=首行顶端、
 *    120dp 全在首行下方，观感偏低；02:34 起点前移 88dp（`gradientStartPx`），
 *    于是首行顶端约 73% 不透明、其后 32dp 完全收敛成纯色。
 *    始终由 `background.copy(alpha = 0f)` → 不透明 `background`，只变 alpha 不变 RGB，
 *    中间不会掺出灰雾。
 *
 * 渐变终点必须是**不透明的页面背景色**，否则海报墙与时间轴之间会出现一条水平接缝
 * （首页底部渐隐层踩过同一个坑）。
 *
 * 动画值以 `State<Float>` 的**引用**往下传，只在 `Modifier.offset { }` 的 layout 阶段读 .value：
 * 海报节点不参与每帧重组。01:39 那次「墙不动」是幅度太小看不出，不是 State 订阅失效。
 */
@Composable
private fun PersonHeader(
    profile: TmdbPersonProfile?,
    loading: Boolean,
    fadeStartPx: Float,
    modifier: Modifier = Modifier
) {
    val background = MaterialTheme.colorScheme.background
    val density = LocalDensity.current
    val fadeLengthPx = with(density) { 120.dp.toPx() }
    // 02:34 微调：02:31 把起点对齐到首行顶端后，整段 120dp 过渡都落在首行下方，
    // 观感上「过渡被下调了」。这里把渐变整段前移 88dp，让「清晰 → 纯背景」的过渡
    // 大约发生在时间轴刚开始的位置：首行顶端已约 73% 不透明，32dp 后完全纯色。
    // 起点仍在头像下方且 alpha≈0，不会重演「从演员中部就开始发白」。
    val fadeLeadPx = with(density) { 88.dp.toPx() }
    val gradientStartPx = (fadeStartPx - fadeLeadPx).coerceAtLeast(0f)
    val posters = remember(profile) {
        profile?.works.orEmpty()
            .map { it.smallPosterUrl }
            .filter { it.isNotBlank() }
            .distinct()
            .take(30)
    }
    // 独立时间动画，与列表滚动 / 手指无关：0→1 匀速；逐格取模让 Restart 前后画面完全一致。
    val transition = rememberInfiniteTransition(label = "personWall")
    val drift = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(38_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "personWallDrift"
    )

    Box(modifier = modifier.clip(RectangleShape)) {
        PosterCollage(
            posters = posters,
            drift = drift,
            modifier = Modifier.fillMaxSize()
        )

        // 顶部状态栏压暗：只覆盖到「时间轴起点的一半」，之后 clamp 成全透明，
        // 演员头像 / 姓名 / 社交后面依旧是清晰流动的海报。
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to Color.Black.copy(alpha = 0.34f),
                        0.42f to Color.Black.copy(alpha = 0.10f),
                        1.00f to Color.Black.copy(alpha = 0f)
                    ),
                    startY = 0f,
                    endY = fadeStartPx * 0.5f
                )
            )
        )

        // 底部白纱：以时间轴首行为收敛位置，120dp 内只把「背景色的 alpha」从 0 拉到 1，
        // 不变 RGB，所以没有灰雾；终点之后保持不透明 background，时间轴文字落在纯色上。
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(background.copy(alpha = 0f), background),
                    startY = gradientStartPx,
                    endY = gradientStartPx + fadeLengthPx
                )
            )
        )

        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * B 层的人物信息：和时间轴一样是 LazyColumn 的一项，随手指一起滚动。
 *
 * 02:34：白纱收敛点对齐时间轴首行，人物信息主体压在**清晰海报**上
 * （只有最底部一小段是几乎看不见的淡入尾段）。
 * 所以首屏用白字 + 柔和阴影（不画任何背景块 —— 上一版「一排长方块跟着滚」就是
 * 透明层里混进了不透明面板）；用户上滑时整屏遮罩逐渐变白，文字同步 lerp 到
 * onSurface 深色，不会「白字掉进白底」。
 */
@Composable
private fun PersonInfoBlock(
    profile: TmdbPersonProfile?,
    initialName: String,
    primary: Color,
    secondary: Color,
    veil: Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val textColor = lerp(Color.White, primary, veil)
    val subColor = lerp(Color.White.copy(alpha = 0.82f), secondary, veil)
    val infoShadow = Shadow(
        color = Color.Black.copy(alpha = 0.55f),
        offset = Offset(0f, 2f),
        blurRadius = 8f
    )
    val infoTextStyle = LocalTextStyle.current.copy(shadow = infoShadow)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 18.dp, bottom = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // PosterImage 会输出「骨架 + 真图」两个节点，必须放进 Box 让它们叠放；
        // 直接放在 Column / Row 里会被当成两个平级元素排成一格灰框 + 一格真图。
        Box(
            modifier = Modifier
                .size(96.dp, 128.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            PosterImage(
                url = profile?.profileUrl,
                title = profile?.name ?: initialName,
                seed = "person_${profile?.personId}",
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = profile?.name?.takeIf { it.isNotBlank() } ?: initialName,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            style = infoTextStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (profile == null) "正在查找作品资料…" else "${profile.works.size} 部作品",
            fontSize = 12.sp,
            color = subColor,
            style = infoTextStyle
        )
        if (profile != null && (profile.twitterId.isNotBlank() || profile.instagramId.isNotBlank())) {
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                if (profile.twitterId.isNotBlank()) {
                    SocialHandle("X", "@${profile.twitterId}", textColor, infoShadow) {
                        openSocialLink(context, "X", profile.twitterId)
                    }
                }
                if (profile.instagramId.isNotBlank()) {
                    SocialHandle("IG", "@${profile.instagramId}", textColor, infoShadow) {
                        openSocialLink(context, "IG", profile.instagramId)
                    }
                }
            }
        }
    }
}

/** 下划线 + ↗ 提示可点，但不画背景块；关 ripple，避免整块高亮。 */
@Composable
private fun SocialHandle(
    prefix: String,
    handle: String,
    color: Color,
    shadow: Shadow,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = "$prefix  $handle ↗",
        fontSize = 12.sp,
        color = color,
        style = LocalTextStyle.current.copy(shadow = shadow),
        textDecoration = TextDecoration.Underline,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick
        )
    )
}

/** external_ids 给的是 screen name（不带 @），跳网页由系统决定是开 App 还是浏览器。 */
private fun openSocialLink(context: Context, platform: String, id: String) {
    val clean = id.trim().removePrefix("@")
    if (clean.isEmpty()) return
    val url = if (platform == "X") "https://x.com/$clean" else "https://www.instagram.com/$clean/"
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * 背景墙（用户 02:02 定案 + 02:16 修正）：整墙倾斜 + 列式交错拼接 + 沿自身 Y 轴的取模传送带。
 *
 * 1. **整体旋转 -15°**：静态 `rotationZ = -15f` 加在整个网格容器上，同时放大 1.8 倍，
 *    旋转后四角仍被海报盖住，绝不露出底色。渐变层不跟着转。
 * 2. **不做任何对角线计算**：每一格只改自己的 Y（`baseY - drift × 周期`）；
 *    因为容器本身是斜的，用户看到的就是向左上方的斜向流动。动画由时间驱动，与滚动 / 手指无关。
 * 3. **取模无限循环（02:16 重做）**：`y = ((baseY - drift × 周期) mod 周期)`。
 *    上一版是「整块两遍拼接 + 一次性平移」，超高 Column 在旋转层里被裁掉，内容移走后
 *    第二组补不进来 → 演员上方整片露白。改成**逐格取模**后，滑出上边界的那一格
 *    立刻从循环底部补回，可见区任何时刻都铺满海报；`drift = 1` 与 `drift = 0` 的
 *    取模结果完全一致，`RepeatMode.Restart` 前后零跳帧。
 *    周期 = 5 格 × 154dp = 770dp，覆盖整屏可见区（倾斜缩放后约 y ∈ [157, 643]dp）。
 * 4. **交错列拼接**：7 个垂直列，奇数列整体错开半格（固定初始 Y-Offset）形成砖块错落。
 *
 * `drift` 传的是 `State` 引用，只在 `Modifier.offset { }` 的 **layout 阶段**读 `.value`：
 * 35 个海报节点不参与每帧重组，只重放各自的 offset。
 *
 * 海报统一走 [PosterImage]：空地址 / 死链会垫 Muted Palette 骨架，不会出现灰板。
 */
@Composable
private fun PosterCollage(
    posters: List<String>,
    drift: State<Float>,
    modifier: Modifier = Modifier
) {
    // 一张海报都没有时也照常铺满 35 格，每格走 PosterImage 的 Muted Palette 骨架。
    // 绝不能退化成「一整片全屏 PosterPlaceholder」—— 那只是一块平滑渐变白板，
    // 看不出网格、也看不出墙在流动，正是截图里演员上方那片白的另一种成因。
    val urls = posters.ifEmpty { listOf("") }

    val tileWidth = 96.dp
    val tileHeight = 144.dp
    val gap = 10.dp
    val columnCount = 7
    val rowsPerCycle = 5
    val density = LocalDensity.current
    val stepXPx = with(density) { (tileWidth + gap).toPx() }
    val cellPx = with(density) { (tileHeight + gap).toPx() }
    // 一个循环周期 = 5 格 × 154dp = 770dp；drift 0→1 恰好走完一整个周期。
    val cyclePx = cellPx * rowsPerCycle

    Box(
        modifier = modifier.graphicsLayer {
            rotationZ = -15f
            scaleX = 1.8f
            scaleY = 1.8f
        }
    ) {
        repeat(columnCount) { col ->
            repeat(rowsPerCycle) { row ->
                val url = urls[(col * rowsPerCycle + row) % urls.size]
                // 奇数列错开半格：砖块交错，且不破坏「一个周期内均匀分布」这个前提。
                val baseY = row * cellPx + if (col % 2 == 0) 0f else cellPx * 0.5f
                val baseX = col * stepXPx
                Box(
                    modifier = Modifier
                        .offset {
                            // layout 阶段读 State：只重放这一格的 offset，不触发任何重组。
                            // 取模是「无限」的关键：滑出上边界立刻从循环底部补回，绝不露白。
                            val shifted = baseY - drift.value * cyclePx
                            val wrapped = ((shifted % cyclePx) + cyclePx) % cyclePx
                            IntOffset(baseX.roundToInt(), wrapped.roundToInt())
                        }
                        .size(tileWidth, tileHeight)
                        .clip(RoundedCornerShape(7.dp))
                ) {
                    PosterImage(
                        url = url,
                        title = null,
                        seed = "wall-$col-$row",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * 时间轴一行：左侧年月 + 竖线圆点，右侧作品卡片。固定 122dp 高，竖线才能在行间连成一条；
 * 减掉 10dp 内边距后正好是 68×102 的 2:3 海报，行高不能随便加大。
 *
 * **整行与卡片都必须完全透明**（详情页 B 层同款，用户 01:41 定案）：
 * 之前给每行刷不透明背景，滚动时一堆 122dp 矩形块跟着冒出来，还切出一条上下硬分界线。
 * 隐藏 A 层只能靠上层的整屏 surface 遮罩均匀淡入，绝不能靠内容行去「盖」。
 */
@Composable
private fun TimelineRow(
    work: TmdbPersonWork,
    dotRingColor: Color,
    primary: Color,
    secondary: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(122.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(62.dp)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width - 14.dp.toPx()
                    val dotY = 20.dp.toPx()
                    drawLine(
                        color = primary.copy(alpha = 0.18f),
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawCircle(
                        color = dotRingColor,
                        radius = 5.dp.toPx(),
                        center = Offset(x, dotY)
                    )
                    drawCircle(
                        color = primary.copy(alpha = 0.85f),
                        radius = 4.dp.toPx(),
                        center = Offset(x, dotY)
                    )
                }
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 8.dp, end = 22.dp)
            ) {
                Text(
                    text = work.year.ifBlank { "—" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primary,
                    maxLines = 1
                )
                if (work.month.isNotBlank()) {
                    Text(
                        text = "${work.month}月",
                        fontSize = 12.sp,
                        color = secondary,
                        maxLines = 1
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        val interactionSource = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                // 不画卡片底色：B 层完全透明，文字 / 海报直接浮在整屏遮罩上。
                // 关掉 ripple：直播卡片那次默认波纹铺满整卡，被用户判为「选中框乱」。
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                )
                .padding(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(68.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
            ) {
                PosterImage(
                    url = work.posterUrl,
                    title = work.title,
                    seed = "${work.mediaType}:${work.tmdbId}",
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = work.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(5.dp))
                val typeLine = buildString {
                    append(if (work.mediaType == "tv") "电视剧" else "电影")
                    work.genres.forEach { append(" · ").append(it) }
                    // 评分并入类型行纯文本。原来那个独立小徽章在 122dp 行高里会被裁掉
                    // 下半截，看起来像一排莫名其妙的「空黑框」（用户 01:34 反馈）。
                    // 票数太少不采信评分（与详情页其它位置同一口径）。
                    if (work.voteCount >= 10 && work.voteAverage > 0.0) {
                        append("  ★ ").append(String.format(Locale.US, "%.1f", work.voteAverage))
                    }
                }
                Text(
                    text = typeLine,
                    fontSize = 12.sp,
                    color = secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = work.roles.joinToString(" · ").ifBlank { "演员" },
                    fontSize = 12.sp,
                    color = secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
