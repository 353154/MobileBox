# MobileBox 交接与开发维护文档

> **文档版本**：v3.3（阶段二收官 · 全站搜索 · 测试分发版）
> **更新时间**：2026-11-07
> **分支**：`main` ｜ **远端**：`https://github.com/353154/MobileBox.git`
> **当前规模**：38 个 Kotlin 文件，约 10.5k 行
> **真机验证**：`CPH2723`（Android 17 / arm64-v8a，设备号 `3B1F67EA9BBUHK76`）

本文档面向「接手继续开发的人或 Agent」。**第四章（详情页）与第五章（转场/3D 定案）是踩坑最多的部分，动这两块之前必须先读完**，里面每一条负面清单都是真实回归过的。

---

## 一、项目速览

基于 **Jetpack Compose + Material 3 + Media3 (ExoPlayer)** 的原生竖屏影视客户端，数据侧直连 TVBox 订阅源（JSON 配置 → 站点规则 → 列表/详情/播放地址）。

| 维度 | 现状 |
| :--- | :--- |
| UI | 单 Activity + Compose + Navigation，五大 Tab（首页/分类/直播/设置/我的）由 `HorizontalPager` 承载；搜索页与详情页是独立路由 |
| 主题 | 默认 / 动态取色 / 自定义 HSV 色盘，深浅双模式，`DataStore` 持久化。深色 `background #0D1117` / `surface #161B22`，浅色 `background #F2F2F7` / `surface #FFFFFF`（`AppTheme.kt` 为准，文档旧版写的 `#0E121A` 是错的） |
| 播放 | **Media3 ExoPlayer 单内核**，`PlayerDetailScreen` 全屏播放层。设置页的「MPV 解码内核」开关目前只是 UI 占位（见第十一章已知问题 1），并未真正切换内核 |
| 直播 | 频道分组 + 静音预拉流高斯模糊预览 + Bing 动态壁纸 + EPG 节目单 + 同频道多线路自动切换 |
| 数据 | 纯客户端直连，无后端；`TvBoxParser` 解析订阅，`TvBoxRepository` 统一状态源 |
| 依赖注入 | Hilt |

---

## 二、模块地图

| 路径 | 职责 | 注意点 |
| :--- | :--- | :--- |
| `MobileBoxApp.kt` / `ui/main/MainActivity.kt` | 应用入口、Hilt、Edge-to-Edge | — |
| `ui/main/AnimatedMainScreen.kt` | **五大 Tab 容器**（Pager + 底栏 + 各页组合） | 底栏图标自绘、跨 Tab `scrollToPage`、直播页所有状态都在这 |
| `ui/navigation/AppNavigation.kt` | `NavHost` + `SharedTransitionLayout` + detail 路由 | 转场时长统一 280ms；`DisposableEffect` 维护 `SharedPosterTransition.activeKey` |
| `ui/components/StandardPosterCard.kt` | **全局唯一海报卡片** | 3D 视差 + `sharedBounds` + `parallaxWeight` 抑制，层级结构不可随意调整 |
| `ui/components/ParallaxEffect.kt` | 陀螺仪角度（相对握持基线）+ 应用级常驻监听 | 见 7.3 |
| `ui/components/PosterTone.kt` | 海报明暗采样，决定叠在海报上的字色 | 见第六章 |
| `ui/detail/MovieDetailScreen.kt` | **详情页（A/B 层级 + 整屏遮罩 + 交错入场）** | 全项目最敏感的文件，改之前读第四章 |
| `ui/search/SearchScreen.kt` | 全站搜索页（搜索框 + 2 列结果网格） | **刻意不自动聚焦**，原因见 8.4 |
| `ui/components/StandardPullToRefreshBox.kt` | 自研下拉刷新（内容跟手位移） | 不要换成 M3 `PullToRefreshBox` |
| `ui/components/ImageUrlCompat.kt` | 图片 URL 降级 + 失败重试 | 采集站 CDN 只支持 http |
| `ui/components/LiveStreamPreview.kt` | 直播静音预拉流 + 实时模糊 | TextureView（SurfaceView 无法被 Compose 模糊） |
| `ui/components/BingWallpaper.kt` | 5 张 Bing 壁纸缓存 + 轮播 + Ken Burns | 首帧回调后淡出 |
| `ui/components/NavGlyphIcon.kt` | 5 枚自绘底栏图标（线框/实心双态） | 线宽统一靠这里 |
| `ui/components/HorizontalScrollFadingEdge.kt` | 横向滚动双向渐隐 | 按可滚方向动态 |
| `ui/player/MobileVideoPlayer.kt` | **点播/直播播放层**：ExoPlayer 封装 + 直播多线路自动切换状态机 | 所有切换相关状态都不能 `remember(streamUrl)`，见 3.1 |
| `ui/player/PlayerDetailScreen.kt` · `PlayerModels.kt` | 播放页外壳与数据模型 | 控制按钮用 M3 原生；**目前没有任何触摸手势**（亮度/音量/Seek 属于 ROADMAP Step 3.2） |
| `data/tvbox/TvBoxParser.kt` | 订阅 JSON / M3U / XMLTV 解析 | EPG 三种格式都在这里 |
| `data/tvbox/TvBoxRepository.kt` | **唯一数据源**（列表/详情/EPG/全站搜索/选中站点） | `cacheInitialized` 保证 `initFromCache` 幂等，见 7.2；首启自动订阅见 8.5 |

---

## 三、五大页面现状

- **首页**：双列 2:3 海报网格；下拉刷新内容跟手；程序化加载不显示顶部圈；3D 陀螺仪视差。
- **搜索**：首页顶栏放大镜进入；并发查所有站点、按片名跨站去重；点结果直进详情（搜索结果自带选集，无需再查详情接口）。
- **分类**：两级 FilterChip（分类/排序）+ 双层缓存（内存 + `TvBoxRepository`），返回 0ms 恢复。
- **直播**：分组 chips → 频道列表；右侧详情区静音预拉流并实时高斯模糊，加载期 Bing 壁纸轮播；**点播放按钮才开始播放**；EPG 有数据才显示，没有就整行不出现。同一频道配置了多条线路时**自动切换**（见 3.1）。
- **设置**：主题、色盘、3D 开关、订阅源管理。开关状态一律走 `StateFlow`（见 7.4）。
- **我的**：收藏/在看的指标卡 + 空状态（68dp 紧凑行）。
- **搜索**（独立路由 `search`，不在 Tab 内）：首页顶栏图标进入，并发查询所有可搜索站点、按片名去重，结果 2 列网格复用 `StandardPosterCard`；从详情页返回保留上次结果、不重查、不弹键盘（见 8.4）。

### 3.1 直播多线路自动切换
`live.txt` / `live.m3u` 里同一个频道通常配了 3 条以上线路，`deduplicateGroups` 会把它们聚合成 `LiveChannel.urls`。播放器按这个顺序当备用源：

- 触发条件三条：`onPlayerError`（还有备用线路就直接跳，不弹错误面板）、**起播超时 9s**（握手成功、播放列表能拉到，但分片永远拉不下来 —— 播放器不报错只转圈，这是免费源最常见的失败形态）、**卡顿超时 12s**（出过画面后长时间停在 buffering）。全部试完才提示「所有线路均不可用（共 N 条）」。
- 切换后顶部弹胶囊「已切到线路 2/4 · 起播超时」，2.6s 自动消失；日志 `MB-LIVE`。
- `LiveSourceMemory` 记住每个频道上次播通的线路，下次进同台优先用它，省掉重复超时等待。
- **状态坑**：`onPlayerError` 注册在 `DisposableEffect(exoPlayer)` 里只注册一次。任何在回调里读写的状态都不能用 `remember(streamUrl)`（换台后对象被重建，回调会写到废弃的 State 上，表现为换台后重试/切换逻辑失灵）。新增状态一律不带 key，换台时用 `LaunchedEffect(streamUrl/activeSourceUrl)` 显式重置。
- **能力边界**：只能处理「超时 / 卡顿 / 报错」。**「能播但播错台」识别不了** —— 例如源里 `CCTV-1` 三条线路分别指向 `cctv14hd`/`cctv17hd`/`cctv12hd`（2026-11 实测，源方无可用 CCTV-1 用别的台占位），客户端没有依据判断画面内容，要查就去改源。
- `LiveBlurPreview`（右侧静音模糊预览）**仍只用第一条线路**，不参与切换；换台抽屉的「当前台」判断用的是 `channel.url`，也不受影响。

---

## 四、详情页规范（A/B 层级）

**这是用户逐帧对照参考视频定下的规范，不是个人审美，改之前先确认。**

### 4.1 A 层：只有海报
- 全屏固定海报（`fillMaxSize`，`ContentScale.Crop`，只裁左右，纵向完整），**零 `translationY`**，绝不随手势上移。
- `Alpha` 恒为 1，不做淡入淡出。
- 滚动时只做 `1.0 → 1.06` 的轻微放大（`graphicsLayer`，`p = scroll / heroHeight`）。
- 内层 `sharedBounds(key = "poster_{movieId}")`，`ResizeMode.ScaleToBounds()`，`clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(0.dp))`。
- **A 层不允许出现任何文字**（片名、年份、评分都属于 B 层）。

### 4.2 遮罩层：整屏均匀交叉淡入
- 一层全屏矩形，颜色取 `MaterialTheme.colorScheme.surface`，`drawBehind { drawRect(surface.copy(alpha = p)) }`，`p = scroll / (heroHeightPx * 0.65f)`。
- **浅色主题 = 整屏变白**；**深色主题 = 整屏压暗**。两者都是「均匀交叉淡入」，不是面板从下往上盖。
- 禁止写法：`Box(background(Black)).graphicsLayer { alpha = p }` —— 会把整层盖成不透明纯黑。

### 4.3 B 层：只有 Y 位移
- 独立 `fillMaxSize().verticalScroll()`，声明在遮罩之后，因此绘制在其上。
- 首块是 `height(heroHeight - statusBarTop)` 的**透明** Box（片名底部对齐），其后是简介 / 播放资源 / 演职人员 / 分类 / 预告片 / 剧照 / 操作行。
- 内容面板**完全透明**，不加任何背景。
- **顶部安全区（Step 2.3，2026-11-07 验收）**：B 层是全屏滚动视口，视口上沿原本就是 y=0，内容滚到顶会画进状态栏/挖孔。修法是**移动视口而不是移动内容** —— `Column` 加 `.padding(top = statusBarTop)`，同时首块高度减去同一个 `statusBarTop`，保证 `statusBarTop + (heroHeight - statusBarTop) = heroHeight`，片名位置分毫不动。
  - `statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()`，`enableEdgeToEdge()` 已开启，返回值含挖孔安全区；若某设备返回 0 会自动退回原样。
  - **反例**：只给内容加 padding 而不减首块高度 —— 片名整体下移、脱离海报底部，首屏就变形了。

### 4.4 禁止添加的装饰（都被用户明确要求删过）
铺满首块的黑阶、片名局部暗底、内容面板「透明→白」空间渐变、衔接暗色层、纯白硬切面板、随滚动加深的黑 Scrim、132dp 大留白、浮动按钮。这些都会切出水平硬边或脏灰带。

### 4.5 字色
- `LocalBTextColors`（`MovieDetailScreen.kt`）提供 `primary/secondary`。**必须是 `compositionLocalOf`（动态）而不是 `staticCompositionLocalOf`**：值随滚动每帧变化，静态版本会让所有读取者无条件整页重组；动态版本只重组真正读它的节点。
- **起点**由海报亮度决定（见第六章）：深色海报 → 白字；浅色海报 → 近黑字。
- **终点**跟随主题：`surface.luminance() > 0.5` → `BTextPrimary/BTextSecondary`（近黑/中灰），否则 `BTextPrimaryDark/BTextSecondaryDark`（近白/浅灰）。
- 中途 `lerp(起点, 终点, veilProgress)`。
- **压在固定浅底上的文字必须用固定 `BTextPrimary`**：`立即播放` 按钮（容器 `#E4E7EE`）、演职人员头像首字（固定莫兰迪底色）。跟随动态字色会在深色主题下变成白字压浅灰。
- 状态栏图标：浅色主题 `prefersDarkSystemBar || veil > 0.5`；深色主题 `prefersDarkSystemBar && veil < 0.5`（滚上去背景变暗必须换回浅色图标）。

### 4.6 交错式入场
- 时钟：`entrance: Animatable<Float>` 从 `0 → EntranceTotalMs` 线性推进，**缓动在各组切片内单独做**（避免双重缓动糊掉层次）。
- 8 组按视觉顺序：片名区 → 简介 → 播放资源 → 演职人员 → 分类 → 预告片 → 剧照 → 操作行。
- 参数（`MovieDetailScreen.kt` 末尾）：`EntranceStartDelayMs = 200f`（海报转场 280ms 的 ~72% 处接力）、`EntranceRiseDurationMs = 320f`、`EntranceStaggerMs = 40f`，`EntranceTotalMs = 320 + 40*7 = 600ms`。
- 动势：`alpha 0→1` + 自下而上 `30~34dp`，组内 easeOutCubic。
- 实现：`Modifier.entranceMotion({ entrance.value }, order, rise)` 在 `graphicsLayer` 里读 lambda → **绘制阶段取时钟，动画每帧只重绘不重组**。
- **打断条件绝不能用 `scrollState.isScrollInProgress`**：页面刚组合时它会瞬间为 `true`，`snapTo(终值)` 会把整段动画一帧跳过（表现为文字 3 帧全亮，极易误判成「动画没接上」）。必须用 `snapshotFlow { scrollState.value }.first { it > 0 }`。

---

## 五、共享元素转场 + 3D 视差（定案，别再翻案）

### 5.1 现象与结论
用户反复报告「进入/返回详情页时，只有点进去的那张卡片抖一下」，并给出关键判据：**关掉 3D 开关就完全不抖**。

**根因**：`sharedBounds` 在转场期间把匹配元素提升进 `SharedTransitionLayout` 的全局 Overlay 重放，而两端绘制内容天生不一致——首页卡片带 3D 透视/角标/流光，详情页 A 层是纯海报。全屏只有被点中那张有匹配 key，所以只有它经历这次内容切换。把 3D 放内层 = 跳变落在起始帧；放外层 = 跳变落在收尾帧。**挪层级只是挪动跳变位置，不解决问题。**

### 5.2 现行解法
- `object SharedPosterTransition { val activeKey = mutableStateOf<String?>(null) }`
- `AppNavigation` 的 detail composable 用 `DisposableEffect(movieId)` 写入/清空。NavHost 要等 280ms 返回动画跑完才 dispose，所以**整个转场期间 key 都为真，Overlay 消失之后才清空**——时序天然对齐。
- 卡片用 `snapshotFlow { activeKey == posterKey }.distinctUntilChanged()` 驱动 `Animatable parallaxWeight`：命中详情页立即 `snapTo(0f)`（Overlay 第一帧就是纯海报），清空后不立即恢复。
- **返回后绝不播放定时恢复动画**。实测：画面静止 3 帧后定时恢复才启动，放大 + 倾斜 + 流光一起出现——这就是用户看到的那一「抖」。正确做法是只置 `waitingForMotion` 并记录基线角度，等用户真的倾斜（`|Δroll| + |Δpitch| > 0.05`）才 260ms 补上权重。
- 所有 3D 量（rotation / translation / 1.04 scale / 评分角标位移 / 流光位置与 alpha）统一乘 `w = parallaxWeight`。

### 5.3 负面清单（每条都回归过）
1. **不要用 `sharedTransitionScope.isTransitionActive` 开关 3D**——它是整个布局级别的全局状态，会让**全屏所有卡片**同帧跳变。
2. **不要靠挪 3D 的层级治抖动**（内层/外层都试过，都抖）。
3. **不要给返回加定时恢复动画**。
4. 3D `graphicsLayer` 仍在 `sharedBounds` 内层、与 `clip` 一起被提升进 Overlay——保证 Overlay 与卡片本体是同一份绘制。
5. `cameraDistance` 是绝对像素值，与 `ScaleToBounds` 的 bounds 缩放复合后透视强度非线性变化，是让这一帧更显眼的原因之一，不要单独去调它。
6. **不要把详情页 8 组内容拆成「每帧组合一组」去摊首帧开销**（2026-11-07 试过）。单组组合只要 5~9ms，但每加一组整帧就涨到 30~50ms，转场会从「一帧跳到终点」退化成「30fps 的多次跳跃」，更难看。正确做法是首帧只留前 3 组、后 5 组等入场结束再一次性补齐。
7. **详情页不要放可视化返回按钮**（用户明确要求）：返回交给系统手势/返回键，`BackHandler` 保留，播放器打开时先关播放器。

---

## 六、海报明暗自适应字色（`PosterTone.kt`）

问题：首屏片名/元信息直接压在海报上（不允许任何暗底），浅色海报 + 白字必然糊。

实现：
1. Coil 以 `72×108` + `allowHardware(false)` 再解一次同一 URL（走磁盘缓存，约百毫秒）。
2. 逐像素算 sRGB 线性化的**相对亮度**。
3. 只在**文字实际覆盖的条带**统计：`[heroFraction - 0.22, heroFraction]`，横向留白 10%。海报 `Crop` 只裁左右，所以图片纵向比例 == 屏幕纵向比例，可直接用屏幕坐标当图片坐标。
4. 取 20/80 分位，用 **WCAG 对比度**判定：白字 vs 条带最亮处、近黑字 vs 条带最暗处。谁先满足 3:1 用谁；都不满足（花海报）取对比度更高的一侧。
5. 顶部 16% 条带同样采样 → `prefersDarkSystemBar`，让状态栏图标在浅色海报上起手就深色。
6. 结果按 `url#条带位置` 缓存在 64 条 LRU，重进详情页不会先白后黑闪一下。
7. 日志：`MB-TONE: url=... Tone(darkText=..., darkBar=..., textBand=a..b, top=c)`。

**坑**：`remember { derivedStateOf { posterTone.xxx } }` 会一直捕获首次组合的 `posterTone`（那时 `analyzed=false`），必须写 `remember(posterTone)`，否则浅色海报永远不触发。

已知极限：条带里同时有亮区和深色人影的海报，深字压上去个别字会被吃掉。要彻底解决只能加极淡文字阴影或暗底——**用户明确禁止暗底/渐变**，所以保持现状，别自作主张加。

---

## 七、全局状态与传感器

### 7.1 主题状态
`ThemeManager.appThemeStateFlow` 是 `stateIn(Eagerly)` 的 **StateFlow**。所有订阅点一律 `collectAsState()`。

**不要写 `collectAsState(initial = AppThemeState())`**：默认值里 `parallaxEnabled = true`，会让设置页首帧渲染「开」再动画到「关」（用户描述为「我没碰它，开关自己动了一下」）。

### 7.2 数据源选择
`TvBoxRepository` 持久化 `KEY_SELECTED_SITE_KEY`，`applySingleConfig` 保留已选站点，`cacheInitialized` 用 `AtomicBoolean` 保证幂等。历史 bug：返回首页/重启后回落到默认「暴风影视」。

### 7.3 视差传感器
监听器**应用级常驻**（`ParallaxRuntime` 引用计数），不要随页面组合 `onDispose` 注销——首页被 NavHost 移出后重新注册会让 `smoothX/Y` 从旧值收敛，表现为「返回后全屏卡片一起缓缓转一下」。只在用户主动关闭 3D 开关时注销。重新注册首帧直接锚定当前姿态，输出加短低通抹平单帧不连续。

### 7.4 两个易被「顺手优化」掉的功能
- **下拉刷新必须内容跟手**：`StandardPullToRefreshBox` 是自研 `nestedScroll` + `graphicsLayer { translationY }`。M3 官方 `PullToRefreshBox` 只弹指示器、内容不动。
- **3D 陀螺仪**：历史上被转场重构删过两次。转场出问题应调幅度，不要整段删除。

---

## 八、数据链路

### 8.1 订阅与解析
`TvBoxParser` 解析 TVBox JSON（`sites` / `lives` / `parses`）与 M3U；`TvBoxRepository` 暴露 `realVodList` / `categoryVodList` / `selectedDetail` / `epgPrograms` 等 StateFlow。

### 8.2 EPG
- **两个来源，全局 XMLTV 优先**（2026-11-07 起）：
  1. `data/tvbox/EpgGlobalSource.kt` —— 公开 XMLTV `cdn.jsdelivr.net/gh/fanmingming/live@main/e.xml`（135 频道 / 3.6 万档）。OkHttp 流式 `XmlPullParser` 解析，只保留 `[今天 00:00, +36h)`；按天落盘 `cache/epg_global_v1.txt`（约 220KB，头行带日期，12h 内直接读盘）；`Mutex` + memo 保证多频道并发只下载解析一次，失败记 `failedDay` 当天不重试。冷缓存首启约 3s，命中缓存约 120ms。
  2. 订阅模板（`lives[].epg` / M3U `url-tvg`，`{name}` `{date}` 替换）：只作为**兜底**，用于全局源没有的频道（凤凰系等）。模板连续失败 3 次本次会话跳过，避免每频道白等握手超时。
- `prefetchDefaultChannels()` 用全局源一次性铺满所有频道（实测 35/54），不依赖页面组合时机；订阅里没有 EPG 地址时也照样预取。
- 匹配走 `EpgPrograms.normalize`（去大小写/横线/清晰度/「卫视」「台」），`CCTV-1` ↔ `CCTV1`、`CCTV-5+` ↔ `CCTV5+` 都对得上。
- 匹配不到就返回 null，UI 整行不显示，**绝不回填假数据**。
- 日志：`MB-EPG`。
- **历史坑**：订阅里原有的 `xyzweb.cyou/tvbox/epg` 会恒定返回 `{"epg_data":[]}`（上游后端空了），`epg.112114.xyz` 域名已不可达 —— 排查时先用手机 `curl` 验源，别怀疑自己的解析。核对解析结果要看设备侧 `cache/epg_global_v1.txt`，本机 curl 拿到的 jsdelivr 副本可能是旧日期的。

### 8.3 图片 CDN 兼容（`ImageUrlCompat.kt`）
`p.bfvp26.com` 等采集站 CDN：https 端口 SSL 握手直接失败、且会间歇返回 200 + `text/html` 拦截页。
- `String.toLoadableImageUrl()`：https 统一降级 http（Manifest 已开 `usesCleartextTraffic`）。
- `rememberRetryableImageRequest()`：`onError` 换新 key 重试，最多 4 次；缓存键与 URL 保持一致，首页成功加载后详情页可直接命中内存缓存。
- 日志：`MB-IMG: OK/FAIL url attempt=n`。

> 若海报「不显示」，先查这里，**不要因为黑屏就把详情页 Hero 搬回滚动流**（那会让海报跟手移动）。

### 8.4 全站搜索
- `TvBoxRepository.searchAll(keyword)`：并发请求所有 `searchable` 站点（`ac=videolist&wd=`，各取第一页），带 `callTimeoutMs = 8_000` 封顶；合并后按片名去重、保留总集数最多的一条，名称完全匹配的排最前。
- 返回 `SearchHit(detail, siteKey, siteName)`，列表 key 用 `站点_站内id` —— **各站自增 id 会互相撞**，直接用 `detail.id` 会让 LazyGrid 因重复 key 崩溃。
- 搜索接口本身也返回 `vod_play_url`，所以结果点进详情直接有选集，不需要额外请求详情接口。
- `SearchMemory`（进程内单例）记住上次查询与结果，从详情页返回时列表原样还在、不重查。
- **不要给搜索框加自动聚焦**：进页面就 `requestFocus()` 会出现「输入框已聚焦但输入法没弹」的僵持态（导航转场期间系统会丢掉 IME 的 show 请求），此时用户再点同一个输入框不产生新的焦点事件，表现为「点了没反应」。交给用户点击触发最稳。
- 排查提示：`mInputShown=true` 但屏幕上没有键盘时，先用**另一个 App + 同一个输入法**做对照，别急着判定输入法坏了。

### 8.5 首启自动订阅与测试分发
给他人打包测试时，App 不能是「装上打开一片空白、还要自己找源」的状态。机制如下：

- **源地址不进代码**：`local.properties` 里配 `DEFAULT_SUB_URL=https://...`（该文件本就被 `.gitignore` 忽略），`app/build.gradle.kts` 在配置阶段读它并 `buildConfigField("String", "DEFAULT_SUB_URL", ...)` 注入。别人 clone 仓库编译时该值为空，行为退回原本的空白引导，**不会泄露源地址**。
- **首启自动订阅**：`TvBoxRepository.initFromCache` 里，若 DataStore 没有保存过订阅且 `BuildConfig.DEFAULT_SUB_URL` 非空，就自动 `loadSubscription` 一次（日志 tag `MB-SUB`）。成功后写入 DataStore，之后走正常恢复路径；失败只记日志，**绝不伪造任何数据**。
- **注意 AGP 8 默认关闭 BuildConfig 生成**，必须在 `buildFeatures` 里显式写 `buildConfig = true`，否则 `BuildConfig.DEFAULT_SUB_URL` 编译不过。
- **release 可安装**：`release` 原本没有 signingConfig，产出的是 unsigned APK（装不上）。现在 `signingConfig = signingConfigs.getByName("debug")`，产物可直接发给别人装。**上架前必须换成自己的 keystore**，debug 证书不能发布。
- 打包：`./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`。首次全量约 6 分钟；`gradle.properties` 已开 `org.gradle.caching`，零改动重建约 1 分钟。
- 接收方注意：需要在系统里允许「安装未知来源应用」；若之前装过同包名但不同签名的版本，会提示「应用未安装」，先卸载旧的。
- **与 debug 包签名相同**（都是 debug keystore），两者会互相覆盖，不能共存。

---

## 九、构建、部署与调试

```bash
# 编译（JDK 17）
export JAVA_HOME=/Users/damon/.tools/jdk-17.0.21+9/Contents/Home
cd /Users/damon/Documents/MobileBox && ./gradlew assembleDebug

# 装机 + 冷启
/Users/damon/platform-tools/adb -s 3B1F67EA9BBUHK76 install -r \
  /Users/damon/Documents/MobileBox/app/build/outputs/apk/debug/app-debug.apk
/Users/damon/platform-tools/adb -s 3B1F67EA9BBUHK76 shell am force-stop com.damon.mobilebox
/Users/damon/platform-tools/adb -s 3B1F67EA9BBUHK76 shell am start \
  -n com.damon.mobilebox/.ui.main.MainActivity
```

### 日志埋点

| Tag | 用途 |
| :--- | :--- |
| `MB-TONE` | 海报明暗采样结果（深/浅字判定） |
| `MB-IMG` | 图片加载成功/失败与重试次数 |
| `MB-EPG` | EPG 模板候选、全局源覆盖数、频道命中/无节目单 |
| `MB-LIVE` | 直播自动切换线路：`自动切线路 2/4 (起播超时)`、`全部线路不可用 (...)` |
| `MB-DETAIL` | 详情页当前海报 URL |
| `MB-SUB` | 首启使用内置默认订阅、以及内置订阅加载失败 |

### 视觉问题排查方法（成本远低于反复改代码试错）
`screenrecord` 录屏 → `ffmpeg` 30fps 抽帧 → 分区逐帧差分（`numpy` 均值差）定位到具体帧 → 裁剪放大拼图看变化形态。
本项目「返回抖动」就是靠这个把范围锁到 f0386–f0390 的孤立峰值（其余区域≈0），一次定位成功。

---

## 十、协作约定（当前维护者的硬性要求）

1. **改完只做 `assembleDebug` + `adb install -r`，然后立即交付**。编译是构建必需，保留。
2. **部署后不要自测**：不截图、不录屏、不切页自检、不主动找问题。**测试由用户发起**——只有用户说「你自己测试/帮我测试」或用文字描述不清时，才允许操作设备。（设备常被用户同时使用，盲发 tap/swipe 会误触返回键、滑出多任务。）
3. **Git 门禁**：只有用户明确说「验证通过 / 提交」，才 commit、push、勾 ROADMAP、写 CHANGELOG。
4. **`/docs/` 与 `/CHANGELOG.md` 是私密内部文档**（`.gitignore` 第 23–24 行），只在本地维护，**绝不 `git add -f` 强推**。提交时用 `git add` 明确列出代码文件，不要 `git add .`。同理 `/.workbuddy/`（本地工作记忆）也已加入 `.gitignore`，不要入库。
5. 用户偏好：不喜欢「多余装饰层」。任何背景、渐变、暗底、悬浮按钮都要先问清是否存在，不要叠加补丁。

---

## 十一、已知问题与下一步

**已知问题**
1. **设置页「MPV 高性能解码内核」开关是纯 UI 占位**（`AnimatedMainScreen.kt` 里 `var isMpvKernel by remember { mutableStateOf(true) }`）：不持久化、不接线播放器、不重启保持，文案却会显示「已回退为 IJKPlayer 内核」。实际播放链一直是 Media3 ExoPlayer。要么在 Step 3.1 时把内核切换真正做出来，要么先把开关改回只读的「当前内核：ExoPlayer」。
2. 中间调海报（条带内明暗混杂）字色只能取对比度更好的一侧，个别字仍会偏糊。
3. 演职人员、剧照、预告片仍是测试数据占位，等刮削接口。
4. 播放器**没有任何触摸手势**（无亮度/音量/Seek/双击暂停，见 ROADMAP Step 3.2），全屏页只靠底部控制条操作。

> 详情页 B 层顶部安全区已于 2026-11-07 修复并验收（Step 2.3），见 4.3。

**下一步（按 ROADMAP）**
- **阶段二已全部关闭**（Step 2.1 / 2.2 / 2.3 / 2.4 均验收通过）
- Step 3.1 双核解码（先把上面第 1 条占位开关处理掉，再谈真内核）
- Step 3.2 全屏触控手势 → Step 3.3 弹幕/PiP → Step 3.4 画质增强
