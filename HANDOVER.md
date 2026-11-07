# MobileBox 交接与开发维护文档

> **文档版本**：v3.16（全量追平版 · WebDAV 同步 · 播放行为开关 · 统一弹窗容器 · 直播选台修正 · 解码内核只读化）
> **更新时间**：2026-11-07 19:01
> **分支**：`main` ｜ **远端**：`https://github.com/353154/MobileBox.git`
> **当前规模**：46 个 Kotlin 文件，约 14.2k 行（实测 14171）
> **已推送**：`origin/main = 382c13f`，本地与远端一致
> **真机验证**：`CPH2723`（Android 17 / arm64-v8a，设备号 `3B1F67EA9BBUHK76`）

本文档面向「接手继续开发的人或 Agent」。

> **【开工前必做 —— 用户 2026-11-07 明确要求】** 每个接手的 Agent 在**动手改任何代码之前**，必须先向用户**书面汇报**三件事：
> 1. **你读到的当前进度**——读了哪些文档（本文件、`.workbuddy/memory/MEMORY.md`、当日日志、`docs/REFACTOR_ROADMAP.md`），代码现状与你的理解是否一致；
> 2. **遗留问题清单**——第十二章「已知问题」里哪些还没解决、哪些是未验收状态、工作区是否有未提交改动（`git status`）；
> 3. **你打算做什么、不动什么**——让用户有机会纠正方向，再开工。
>
> 汇报完等用户确认再动手。不要读完文档直接开干。

**第四章（详情页）与第五章（转场/3D 定案）是踩坑最多的部分，动这两块之前必须先读完**，里面每一条负面清单都是真实回归过的。

---

## 一、项目速览

基于 **Jetpack Compose + Material 3 + Media3 (ExoPlayer)** 的原生竖屏影视客户端，数据侧直连 TVBox 订阅源（JSON 配置 → 站点规则 → 列表/详情/播放地址）。

| 维度 | 现状 |
| :--- | :--- |
| UI | 单 Activity + Compose + Navigation，五大 Tab（首页/分类/直播/设置/我的）由 `HorizontalPager` 承载；搜索页与详情页是独立路由 |
| 主题 | 默认 / 动态取色 / 自定义 HSV 色盘，深浅双模式，`DataStore` 持久化。深色 `background #0D1117` / `surface #161B22`，浅色 `background #F2F2F7` / `surface #FFFFFF`（`AppTheme.kt` 为准，文档旧版写的 `#0E121A` 是错的） |
| 播放 | **Media3 ExoPlayer 单内核**。点播已在详情页内做**原地播放**（不跳独立播放页，见 10.4）；直播 / 横屏仍用 `PlayerDetailScreen` 全屏层。全屏手势（亮度/音量/Seek/双击暂停）+ 画中画 PiP 见第十章。设置页「播放内核与视频增强」卡片里有**画中画小窗**与**后台播放**两个真开关（`PlaybackSettings`，见 8.10）；「解码内核」为**只读状态行**（固定 ExoPlayer，双核未接入），见第十二章已知问题 1 |
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
| `ui/detail/MovieDetailScreen.kt` | **详情页（A/B 层级 + 整屏遮罩 + 交错入场 + 原地播放状态机 + 播放源自动接力）** | 全项目最敏感的文件，改之前读第四章与 10.4 / 10.5 |
| `ui/search/SearchScreen.kt` | 全站搜索页（搜索框 + 2 列结果网格） | **刻意不自动聚焦**，原因见 8.4 |
| `ui/components/StandardPullToRefreshBox.kt` | 自研下拉刷新（内容跟手位移） | 不要换成 M3 `PullToRefreshBox` |
| `ui/components/ImageUrlCompat.kt` | 图片 URL 降级 + 失败重试 | 采集站 CDN 只支持 http |
| `ui/components/LiveStreamPreview.kt` | 直播静音预拉流 + 实时模糊 | TextureView（SurfaceView 无法被 Compose 模糊） |
| `ui/components/BingWallpaper.kt` | 5 张 Bing 壁纸缓存 + 轮播 + Ken Burns + `AmbientBackdrop`（海报模糊 / 壁纸回落） | 首帧回调后淡出；氛围底只覆盖视频下方，见 10.4 |
| `ui/components/NavGlyphIcon.kt` | 5 枚自绘底栏图标（线框/实心双态） | 线宽统一靠这里 |
| `ui/components/HorizontalScrollFadingEdge.kt` | 横向滚动双向渐隐 | 按可滚方向动态 |
| `ui/player/MobileVideoPlayer.kt` | **点播/直播播放层**：ExoPlayer 封装 + 直播多线路自动切换状态机 + 全屏手势 + 手势 HUD + 加载态 / 失败上报 | 切换相关状态不能 `remember(streamUrl)`，见 3.1；手势性能结构见 10.1；新增 4 个受控参数与判重坑见 10.5 |
| `ui/player/PipController.kt` | 画中画状态与参数（playerActive / 画面比例 / visibleRoute 校验） | 见 10.2 |
| `ui/player/PlayerDetailScreen.kt` · `PlayerModels.kt` | 播放页外壳与数据模型（**点播已不走这里**，只剩直播 / 横屏） | 控制按钮用 M3 原生；竖屏 `statusBarsPadding`，PiP 时强制 `fillMaxSize`；`VideoRoute.displayName()` 是播放源名称唯一出口 |
| `data/tvbox/TvBoxParser.kt` | 订阅 JSON / M3U / XMLTV 解析 | EPG 三种格式都在这里 |
| `data/tvbox/TvBoxRepository.kt` | **唯一数据源**（列表/详情/EPG/全站搜索/选中站点） | `cacheInitialized` 保证 `initFromCache` 幂等，见 7.2；首启自动订阅见 8.5 |
| `data/watch/WatchStore.kt` | 追剧收藏 / 播放历史（**独立** `watch_prefs` DataStore + 共享 StateFlow） | 见 8.6；不要复用订阅或主题的 DataStore；`importBackup()` 是 WebDAV 合并的唯一写入口 |
| `data/watch/PlaybackSettings.kt` | 播放行为开关（画中画 / 后台播放，**独立** `playback_prefs` + `StateFlow` + 同步快照） | 见 8.10；MainActivity 前后台状态也走这里 |
| `data/watch/WebDavConfig.kt` · `WebDavSync.kt` | WebDAV 配置（`webdav_prefs`）与同步内核（GET/PUT/MKCOL + Basic Auth + 并集合并） | 见 8.13；应用密码明文存本机 |
| `ui/components/StandardDialog.kt` | **全应用唯一弹窗容器**（`surface` + 6dp tonalElevation + 20dp 圆角） | 新增弹窗必须走 `StandardDialog` / `StandardDialogSurface`，别再写裸 `AlertDialog`，见 8.14 |
| `ui/components/WebDavSyncDialog.kt` | 云端同步弹窗（未配置展开表单 / 已配置只显摘要卡 + 三个同步动作） | 见 8.13 |
| `ui/profile/ProfileScreen.kt` | 「我的」页统计 / 收藏 / 历史，全部由真实播放记录推导 | `stats` 是可空覆盖入口，正常业务不传；时长口径见 8.7 / 8.11 |
| `ui/profile/WatchListScreen.kt` | 历史 / 追剧清单 / 在看共用的列表页（`WatchListType` 枚举 + `watchlist/{type}` 路由） | 只存最小展示字段，进详情前按片名现取线路，见 8.7 / 8.9 |

---

## 三、五大页面现状

- **首页**：双列 2:3 海报网格；下拉刷新内容跟手；程序化加载不显示顶部圈；3D 陀螺仪视差。
- **搜索**：首页顶栏放大镜进入；并发查所有站点、按片名跨站去重；点结果直进详情（搜索结果自带选集，无需再查详情接口）。
- **分类**：两级 FilterChip（分类/排序）+ 双层缓存（内存 + `TvBoxRepository`），返回 0ms 恢复。
- **直播**：分组 chips → 频道列表；右侧详情区静音预拉流并实时高斯模糊，加载期 Bing 壁纸轮播；**点播放按钮才开始播放**；EPG 有数据才显示，没有就整行不出现。同一频道配置了多条线路时**自动切换**（见 3.1）。
- **设置**：主题、色盘、3D 开关、播放行为（画中画小窗 / 后台播放）、云端同步（WebDAV）、订阅源管理。开关状态一律走 `StateFlow`（见 7.4 / 8.10）；左侧图标统一走 `SettingLeadingIcon`（24dp 占位 / 22dp 绘制）。生物识别隐私锁占位项已于 2026-11-07 删除（见 8.15）。
- **我的**：「全部观看」总时长 + 电影 / 剧集 / 直播三类分类时长（见 8.11）、历史观看 / 追剧清单 / 在看剧集三段同构卡片（见 8.9 / 8.11）。
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
- 实现：`Modifier.entranceMotion(entranceClock, order, rise)` 在 `graphicsLayer` 里读 lambda → **绘制阶段取时钟，动画每帧只重绘不重组**。
- **打断条件绝不能用 `scrollState.isScrollInProgress`**：页面刚组合时它会瞬间为 `true`，`snapTo(终值)` 会把整段动画一帧跳过（表现为文字 3 帧全亮，极易误判成「动画没接上」）。必须用 `snapshotFlow { scrollState.value }.first { it > 0 }`。
- **显示结果绝不能只依赖入场协程是否跑完**（2026-11-07 16:26 修复，用户三张截图反馈「有些详情页下半部分字变白、显示到分类就没了，有些完全正常」）：`entrance.value` 被并发打断 / 后台冻结时停在中间值，后半段组（order 大）算出的 alpha 极低 —— 深色字低 alpha 叠白遮罩看着像「字变白了」，alpha≈0 的组连色块都看不见、像「内容缺失」，**实际内容早已组合出来，不是数据问题也不是字色逻辑错**。定案：`entranceClock = { if (scrollState.value > 0) EntranceTotalMs else entrance.value }`，用户一滚过就在绘制层落定终值，与动画状态彻底解耦；8 处 `entranceMotion` 一律传这个 lambda，**不要改回 `{ entrance.value }`**。
- 「滚动打断」必须独立成 `LaunchedEffect`，**不要放回主动画 effect 内的 `launch` 子协程**：主 `animateTo` 结束 / 被取消时会连带取消子协程，打断反而不生效（本次故障的可疑根因之一）。屏外 5 组的 `offscreenReady` 除「入场跑完 + 滚动立即补齐」外，还要有一条 `delay(200+600+400)` 的**绝对时间兜底**，防止动画卡住时内容永久不组合。
- `EntranceTotalMs = 600f` 正好让最后一组（order 7，start 280）跑到 alpha=1；调参数时别让末组落不到 1。

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

### 8.6 观影记录本地持久化（`WatchStore`，2026-11-07 新增，用户真机验收通过）
- 独立 DataStore `watch_prefs`（`data/watch/WatchStore.kt`），**刻意不复用**订阅用的 `tvbox_subscription_prefs` 与主题用的 `settings`：订阅是可丢弃缓存，`TvBoxRepository` 清订阅时会 `prefs.remove(...)`，观影记录不能跟着一起没。依赖早已在 `libs.versions.toml`（DataStore Preferences 1.1.1 + Gson 2.10.1），本轮**没有新增任何依赖**。
- `MobileBoxApp.onCreate` 调一次 `WatchStore.init(this)`，由 `ds.data.collect` 驱动两个共享 `StateFlow`（`favorites` / `history`）。**不要在各页面各自 `collectAsState(initial=...)` 读冷流** —— 会先渲染一帧空列表，切 Tab 时收藏数自己跳一下（`ThemeManager` 踩过同款坑，见 7.1）。
- 收藏 `FavoriteItem(videoId / title / posterUrl / addedAt)` 只存展示所需最小字段，详情仍从订阅源现取；`toggleFavorite` 按 `videoId` 幂等。两个详情页（`MovieDetailScreen` 与遗留 `PlayerDetailScreen`）共用同一份表，退出页面 / 切 Tab / 重启都保留。
- 播放历史 `WatchRecord` 只在 `MobileVideoPlayer.onFirstFrameRendered` **真正出画面时**落地：加载失败与自动换源期间不记。**一条记录 = 一个「影片 + 集数」**，不是一次播放会话（16:38 用户反馈同一集播 4 次出现 4 行，已改为稳定 id 归并，详见 8.8）。`MovieDetailScreen` 用普通类 `PlaySession` + `DisposableEffect(playTarget)` 结算 `watchedMs` 与播放位置（换集 / 换源 / 退出播放 / 页面销毁都会 dispose），计时用 `SystemClock.elapsedRealtime`。**同一会话内首帧回调可能多次触发**，`recordId == null` 是唯一幂等门；历史上限 50 条。
- `watchedMs` 是会话墙上时间，含暂停 / 后台，是**上界近似值**，不是解码器精确播放时长。`ProfileScreen.buildViewingStats(history)` 据此推导统计卡片的「时长 / 部 / 集」、近 7 天曲线与 24h 柱状图；`ViewingStats` 默认值一律 0，删掉了原来的 `(2..5).random()` 和写死的 12.5 小时 / 14 部 / 3 部。minSdk 24，用 `Calendar` 而不是 `java.time`，不引入 desugaring。**首版时长口径是「小时 · 1 位小数」，已按用户反馈改为分钟级，见 8.7**。
- **遗留播放页 `PlayerDetailScreen`（直播 / 横屏）只接了收藏，没接播放历史**：点播现行入口是详情页原地播放（见 10.4），避免把直播频道混进影片历史。
- WebDAV 同步（Step 5.2）已按本条派生落地，见 8.13；列表页与统计精度见 8.7，分类统计见 8.11。`WatchStore.importBackup()` 是同步合并时唯一允许绕过 `recordPlayback` 的写入口（列表整体替换，不累加）。

### 8.7 观影列表页与分钟级统计（2026-11-07 16:19 新增，用户真机验收通过）
- **精度问题的根因不是没记上，是单位被四舍五入吃掉**：首版 `hoursOf()` 把毫秒换成小时后 `round1()`（0.1 小时 = 6 分钟），看几分钟显示 `0.0 小时`，曲线数组也被 round 成 0、`StatBarChart` 的 `value > 0f` 不成立所以柱子不画。现统一改为**分钟**：`ViewingStats` 的时长字段存毫秒（`last7DaysMillis / weekMillis / todayMillis`），卡片用 `formatWatchDuration(millis)` 显示 `X 分钟`（不足 1 分钟显示 `X 秒`）；曲线数组以分钟浮点存储且**不再 round1**，看几分钟也有可见柱高。三张卡片字号分别 28 / 24 / 24sp 并加 `maxLines = 1`，防止中文单位撑破左栏。
- `ui/profile/WatchListScreen.kt` 用 `WatchListType`（`HISTORY / FAVORITES / WATCHING`，2026-11-07 18:37 由四种并为三种，`PENDING` 已删，见 8.9）区分数据源，共用一个列表页：**历史按影片 + 集数一条**（2026-11-07 16:51 改，原来按会话一条，见 8.8）、追剧清单 = 收藏表全量按加入时间、在看按 `videoId` 聚合取最近一条。每行显示海报（复用 `toLoadableImageUrl` + `rememberRetryableImageRequest`）、片名、集数 / 播放源、时间戳、观看时长与**播放进度条**，右上角显示条数，空态有专属文案。
- `ProfileScreen` 三个入口（历史卡片、追剧清单卡片、在看剧集卡片）统一走 `onOpenList(WatchListType)` 回调，经 `AnimatedMainScreen.onNavigateToWatchList` 上抛，由 `AppNavigation` 的 `watchlist/{type}` 路由打开列表页。**CTA 胶囊原先只是装饰（`EmptyStateCta` 没有 `onClick`），18:37 起与卡片同源可点**；`ProfileScreen` 不再需要 `onShowToast`。
- **跳详情必须现取线路**：Store 只存最小展示字段，`resolveDetail()` 先查 `selectedDetail / realVodList / categoryVodList`（要求 `routes.isNotEmpty()`），没命中再 `TvBoxRepository.searchAll(title)` 取片名**精确同名**的第一条，8s 超时；命中后由路由层写回 `selectedDetail` 再 navigate。**绝不能用 `VideoDetailData.createFromMovie` 兜底** —— 那份带的是官方测试流，点开会放错片；查找失败只弹「未找到该影片的可用播放源」，不硬跳。
- 已知取舍：列表页在 NavHost 里是独立路由，从列表点进详情再返回会回到列表页；但继续返回到 `home` 时 `HorizontalPager` 会重组回第 0 页（既有行为，其它 Tab 进详情也一样），本轮未改主界面 Pager 状态。

### 8.8 观影记录去重、播放进度与「我的」页排版修复（2026-11-07 16:51 新增，用户真机验收通过，提交 `c6f2797`）
- **用户 16:38 两张截图 + 四点反馈**：同一剧集同一集重复记成多条；拖动到 30 分记录里看不到位置（「应该记录看到了哪里」而不是「看了多久」）；「我的收藏 / 在看剧集」字体被裁、要换排版；顺带让 Agent 自查其它问题。
- **去重**：`WatchStore.recordPlayback` 的 id 由 `videoId_episodeId_时间戳` 改为稳定的 `videoId_episodeId`，同集反复播放只把这一条提到列表头、刷新元信息，`watchedMs` 累计而非覆盖。`decodeHistory` 新增 `normalizeHistory`：每次读盘按同一 key 归并旧数据（时长求和、元信息与位置取最近一次），**老记录无需手动清理即自动收敛**，写回列表永远「一集一条」。
- **播放进度**：`MobileVideoPlayer` 新增 `onProgressChanged(positionMs, durationMs)`，在既有 500ms 轮询里、且 `hasFirstFrame && position > 0 && duration > 0` 时上报；`MovieDetailScreen.PlaySession` 用普通字段接住（**不进 State、不写盘**），`DisposableEffect.onDispose` 调 `finishSession(id, 墙上时长, position, duration)` 一次落盘。`WatchRecord` 新增 `positionMs / durationMs`，Gson 缺字段按 0 处理，兼容旧包数据。拖动进度条 / 手势 seek 会在下一次轮询覆盖位置。
- **列表展示**：`WatchListScreen` 每行底部加「进度条 + `看到 30:12 / 45:00`」（`formatMediaTime`，超 1 小时用 `h:mm:ss`），行高由固定 96dp 改 `wrapContentHeight`、海报固定 76dp，避免加一行后裁字；「在看剧集」由「共 N 次」改为「共 N 集」（按 `episodeId` 去重）；历史行把「观看 X」降级为「累计 X」。
- **「我的」页排版与图表**：① 两张计数卡片改 `WatchCountCard`（图标 + 数字 + 标签横排、`heightIn(min=84dp)` 自适应），替换原来 72dp 硬塞 26sp + 12sp 导致字体放大被裁；② 历史观看卡片改两行，第二行显示「看到 30:12 / 45:00 · 累计 X」；③ `StatBarChart` 零值只画浅色圆点，不再无条件画满高槽轨（原来 7 天里 6 天没数据也显示 6 根等高柱）；④ 「稳步上升」徽章由最近两天真实数据推导（暂无记录 / 开始记录 / 稳步上升 / 略有回落 / 保持稳定）；⑤ `formatWatchDuration` 超 1 小时拆成「X 小时 Y 分钟」，三张统计卡片字号降到 24 / 22 / 22sp 并加 `softWrap=false + overflow=Ellipsis`。
- **本轮明确未做（等用户拍板）**：点历史条目**续播**（要动播放器起播 seek 与自动换源链路）；两张统计卡片「最近 7 天 / 本周观看」目前共用同一份 `weekMillis` 与 `last7DaysData`，数字与曲线重复，改口径属产品决策；清空 / 删除单条历史；从列表进详情返回 `home` 落回 Pager 第 0 页（既有行为）。

### 8.9 「追剧清单」合并与追剧入口上移（2026-11-07 18:48 用户真机验收通过，提交 `d3f6379`）
- **起因**：「我的」页的「待看片单」胶囊写着「去追剧」却是死链，且点进去只有「收藏了但没看过」的派生列表；用户提出「把收藏接进这个列表、删掉我的收藏按钮」。
- **合并**：`WatchListType.PENDING` 与 `WatchStore.clearPending()` 一并删除，`FAVORITES` 改标题「追剧清单」；列表行 `detailLine` 取原 `FAVORITES` 的「已有观看记录 / 收藏后尚未播放」（信息量大于 `PENDING` 的单一文案），删除单条仍落 `removeFavorite`，清空改 `clearFavorites`（**语义从「只删未看」变强为整表清空，确认文案已同步**）。
- **数据层零改动**：待看本来就是 `favorites` 的内存派生视图，没有自己的存储，也没有迁移；WebDAV 只同步 `favorites`，不受影响。
- **「我的」页三段同构**：新增 `ProfileSectionCard` 骨架（标题 + 36dp 圆形图标 + 主副两行 + CTA 胶囊），「历史观看 / 追剧清单 / 在看剧集」共用，间距与字号完全一致。追剧清单主行「N 部影片」、副行「M 部还没开始看」；在看剧集主行取最近一条**点播**记录（直播不计入）「片名 · 第 N 集」，副行「看到 mm:ss / 总时长 · 共 N 集」。原 `WatchCountCard` 双计数卡与待看卡片行已删。
- **追剧按钮上移**：从底部操作行（原 `DetailAction` 第 8 组、要滚两屏）移到「立即播放」右侧 —— `Row { Button(weight(1f), 48dp) + 12dp + FavoriteToggle(48dp 圆形) }`。容器沿用播放按钮的固定浅灰 `Color(0xFFE4E7EE)`（**不跟主题取色**，否则深色模式浅底浅字），已追剧换实心 `Bookmark` + `primary`。
- **播放态补偿**：`立即播放` 那一行只存在于浏览态，播放态改在「选集」标题右侧放 32dp 裸 `IconButton`（`SectionTitle` 新增可选 `trailing` 槽位），不新增行高、不打断「播放器 → 选集 → 播放源」的顺序（10.4 的用户要求）。两处共用同一个 `toggleFavorite` lambda 与 toast 文案。
- **未动**：`PlayerDetailScreen`（直播 / 横屏）底部操作行仍是原来的 4 项，按用户「那个不改了」保持原样。

### 8.10 播放行为开关：画中画 / 后台播放（2026-11-07 17:33 新增，用户真机验收通过）
- 新增 `data/watch/PlaybackSettings.kt`：独立 `playback_prefs` DataStore（**不复用**主题的 `settings`，避免清主题数据时连坐），同时暴露两种读取口 —— `prefsFlow: StateFlow`（设置页 `collectAsState()`，切 Tab 不回默认值，见 7.1）与 `@Volatile` 同步快照（`MainActivity.onUserLeaveHint`、播放器生命周期回调不在 Compose 作用域里，必须立刻读到最新值）。`MobileBoxApp.onCreate` 调一次 `init(this)`。
- **画中画小窗**（默认开）：`PipController.tryEnterPip()` 先校验 `PlaybackSettings.pipEnabled`，关掉后按 Home 只是普通退后台（日志打 `skip: pip disabled`）。
- **后台播放**（默认关）：`MainActivity.onStart/onStop` → `PlaybackSettings.onAppForeground/onAppBackground()` 维护 `appInBackground`（Compose state），`MobileVideoPlayer` 用 `LaunchedEffect(backgroundPlayEnabled, appInBackground, isInPip)` 决定暂停 / 续播：切后台且开关关闭 → 记 `resumeOnReturn = isPlaying` 再 `pause()`，回前台只在本来在播时 `play()`；开关开启 → **完全不干预 ExoPlayer**，并 `setWakeMode(C.WAKE_MODE_NETWORK)` 抬起网络唤醒锁（Manifest 已加 `WAKE_LOCK`），锁屏可继续出声。
- **PiP 必须优先短路**：少数 ROM 进小窗也会走 `onStop`，判断顺序是「先看 `isInPip`（小窗内一律不暂停、该恢复就恢复）→ 再看 `appInBackground`」，否则小窗会被自己的后台逻辑暂停。
- 组合行为：都关＝按 Home 暂停、回来续播；只开后台＝纯音频继续；只开画中画＝小窗播放；都开＝优先小窗（系统托管 PiP，不参与暂停判断）。
- **能力边界**：后台播放没有前台服务，长时间锁屏仍可能被系统回收进程。

### 8.11 「全部观看」统计与分类时长（2026-11-07 16:59 需求 + 17:1x 修复，用户真机验收通过）
- 第一张统计卡片由「最近 7 天」改为**「全部观看」**：不按时间窗过滤，显示累计总时长 + **电影 / 剧集 / 直播三类各自时长**，去掉右侧柱状图；第二 / 第三张（本周观看 / 今天）的曲线保持原样。
- 分类口径：`ViewingStats` 新增 `movieMillis / seriesMillis / liveMillis`，按历史记录的内容类型归集；直播记录由 `LiveScreenContent` 写入（`isLive = true`），所以**直播时长从这一版才开始累计**，老数据里没有。
- **「电影 1 秒」是格式化的假象，不是统计算错**（17:1x 用户截图反馈）：`formatWatchDuration` 里有 `seconds.coerceAtLeast(1L)`，把 0 毫秒强行显示成「1 秒」，于是一条剧集记录下面凭空多出「电影 1 秒 / 直播 1 秒」。修法两条：① 0 毫秒如实显示「0 秒」，只有真 >0 但不足 1 秒才显示「1 秒」；② 「全部观看」只列出**有观看时长**的分类，全空时显示「暂无观看记录」。
- 三个分类行加了显式 `lineHeight`，190dp 卡片内三行不再被裁（此前第三行「直播」会被切掉）。
- 四类列表（历史 / 收藏 / 待看 / 在看）同期补了**单条删除与整表清空**（顶部按钮 + 二次确认 `StandardDialog`），历史行点击走**续播**（带 `resumeRecord` 回详情页自动起播对应集与位置），其余类型点击进播放详情页。待看已于 18:37 合并进「追剧清单」，见 8.9。

### 8.12 订阅弹框：剔除直播源 + 排版重构（2026-11-07 17:1x 装机，用户真机验收通过）
- **直播源混进「可用影视站点」**：订阅里有一项「专属直播频道（实测…）`CMS-XML`」，它的 `api` 指向 `live.txt` / `live.m3u`，没有点播分类与搜索接口 —— 切过去首页全空，跨站聚合还会白等一次 8s 超时。新增 `TvBoxSite.isLiveOnlySite`（名称含「直播 / 电视频道 / 电视台」，或 api 以 `.m3u/.m3u8` 结尾、含 `/live.txt`、`live.m3u`），在**订阅解析**与**缓存恢复**两处过滤，弹框列表与计数只用点播站。
- **直播功能不受影响**：频道来自同一份配置的 `lives` 字段，独立解析（实测 54 个频道），与 `sites` 过滤无关。
- **排版**：原来是 `AlertDialog` 的 text 槽放 `LazyColumn`，没有高度上限 —— 站点一多整框撑满屏幕，列表末尾被裁、「完成」按钮孤零零压在空白下面。改为自定义 `Dialog` + `StandardDialogSurface`：固定 **88% 屏高**，标题与底部按钮常驻，中部 `LazyColumn` 滚动。

### 8.13 WebDAV 云端同步（Step 5.2，2026-11-07 18:30 用户真机验收通过，提交 `65ee4b5`）
- 纯客户端直连，标准 WebDAV `GET / PUT / MKCOL` + Basic Auth，复用已存在的 OkHttp 4.12 / Gson，**没有新增任何依赖**。配置存独立 `webdav_prefs`（`WebDavConfig.kt`，含 `clear()`），`MobileBoxApp` 初始化。
- 产物是 `{服务地址}/{目录}/backup.json`，只装 `favorites` 与 `history`（待看 / 在看是派生视图，不单独存）。三个动作：**上传备份**（本机 → 云端）、**下载恢复**（云端 + 本机合并）、**双向同步**（拉取合并 → 写本机 → 推回云端；云端无备份时自动降级为上传）。
- **合并规则**：按 `videoId_episodeId` 取并集；`watchedMs / durationMs` 取较大值（避免两端重复累加翻倍）；播放进度与元信息取较新那条；上限沿用 50 条历史 / 200 条收藏。写回本机走 `WatchStore.importBackup()`（列表整体替换，**不经过 `recordPlayback` 的累加逻辑**）。
- **上传前必须先 MKCOL**：早期版本只在 PUT 返回 409/404 时才补建目录，某些服务（含当初的临时测试服务）在目录不存在时直接拒，且不消费请求体 → keep-alive 连接里残留的 body 会污染后续请求。现在无条件先 `MKCOL`（已存在返回 405，忽略）再 `PUT`，并对每个响应消费 body。
- **配置态两分**：已配置时打开弹窗只显示轻量摘要卡（地址 / 账号 / 目录 / 完整 `backup.json` 路径 + 「已配置」徽章）+ 三个同步按钮 + 「删除配置 / 新建配置」；未配置或点新建才展开四项表单。删除只清本机配置，**云端文件不动**。
- **已知取舍**：① **删除不跨设备同步** —— 合并是纯并集，A 端删掉的记录会被云端那份带回来；要支持需墓碑标记并改 `WatchStore` 的删除方法。② 应用密码明文存本机 `webdav_prefs`。③ 全部手动触发，无后台自动同步、无前台服务。
- 坚果云可用：地址 `https://dav.jianguoyun.com/dav/`，账号填邮箱，密码必须用网页端「第三方应用管理」生成的**应用密码**（填登录密码必 401）；单文件 500M、免费版每 30 分钟 600 次请求，`backup.json` 只几十 KB，一次同步最多 3 个请求。

### 8.14 全应用统一弹窗容器 `StandardDialog`（2026-11-07 18:30 装机，用户真机验收通过，提交 `65ee4b5`）
- **问题**：用户反馈「关于我们弹窗没取色，和云端同步弹窗颜色不一样」。根因是三种容器并存：`AlertDialog` 纯 `surface` 无 tonal elevation（看着是「死」底色，28dp 圆角）、订阅弹框 `surface + 2dp`（24dp 圆角）、同步弹窗 `surface + 6dp`（20dp 圆角）。
- **定案**：`ui/components/StandardDialog.kt` 定义唯一基准 —— `MaterialTheme.colorScheme.surface` + **固定 6dp tonalElevation** + **20dp 圆角** + 20dp 内边距 + 12dp 元素间距。这个组合会跟随浅色 / 深色、动态取色、自定义主题色一起变。导出两个：`StandardDialog`（常规弹窗）与 `StandardDialogSurface`（三段式 + 滚动内部的容器外观，内部布局不动）。
- 6 处全部接入：关于我们、主题选择、主题色色盘、观影列表清空确认、TVBox 订阅配置、WebDAV 同步。色盘弹窗**关掉外层滚动**，避免拖动取色与列表滚动抢手势。
- 约定：**新增弹窗必须走这两个组件**，不要再写裸 `AlertDialog`，否则又会退回到三套颜色。

### 8.15 设置页清理：删生物识别锁占位 + 图标统一（2026-11-07 17:51 提交 `cd06307`，当日验收通过）
- **删除「生物识别隐私锁 (Biometric)」整块**（Row + 分隔线 + `isBiometricEnabled` state + 相关 toast）：用户明确「没什么意思，直接删除这个设置」。删除前它只是占位 —— 纯 `remember`、不持久化、无 `androidx.biometric` 依赖、无 `USE_BIOMETRIC` 权限，也**没有任何「成人 / 私密分类」可锁**。分组标题随之由「安全与系统关于」改为「系统关于」。
- **图标统一**：原来左侧 7 个图标代码里都写 `24.dp`，真正不统一的是「关于 MobileBox」走 `SettingRowItem`（22dp 且灰色），右侧箭头还有 `20/18dp` 混用。新增 `SettingLeadingIcon`（固定 24dp 容器 + 22dp 绘制 + 统一主题色），8 行全部走同一个组件；箭头统一 18dp，主题色圆球 20 → 18dp。
- 顺带删掉删除后变成未使用的 `onShowToast` 参数，编译警告归零（只剩图标 `AutoMirrored` 弃用警告）。


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
| `MB-PIP` | 画中画：`onUserLeaveHint playerActive=?`、`enterPictureInPictureMode -> bool, ratio=WxH`、`onPipModeChanged(bool)`、`skip: ...` |
| `MB-XSITE` | 详情页跨站线路聚合：`+N 条线路 / M 个站点` |
| `MB-PLAY` | 播放器失败原始原因（`播放源失败，交外层自动换源`）、`setWakeMode 失败`（后台播放开关接线时） |

> WebDAV 同步**没有 logcat 埋点**：错误原因（401/403/404/超时/证书）直接以中文文案回显在同步弹窗里，排查时看弹窗提示 + 云端 `backup.json` 是否存在。

### 视觉问题排查方法（成本远低于反复改代码试错）
`screenrecord` 录屏 → `ffmpeg` 30fps 抽帧 → 分区逐帧差分（`numpy` 均值差）定位到具体帧 → 裁剪放大拼图看变化形态。
本项目「返回抖动」就是靠这个把范围锁到 f0386–f0390 的孤立峰值（其余区域≈0），一次定位成功。

### adb 界面自动化测试手法（用户授权自测时用）
- 控件坐标用 `adb shell uiautomator dump /sdcard/ui.xml` + 解析 `bounds` 中心，**不要按截图比例换算**（Vision 模型返回的是缩放坐标，实测两次都点偏，dump 一次精确）。
- 设备 1216x2640 / density 476，截图与屏幕 1:1。
- **发现用户正在用手机（弹窗、侧边栏、内容异常）立即停手**，不要继续发 tap/swipe。

---

## 十、播放器：手势、画中画与详情页原地播放（2026-11-07 新增）

### 10.1 全屏手势（Step 3.2）
- 左半屏上下滑 → 屏幕亮度（改 `window.attributes.screenBrightness`，clamp 0.02~1；起点为 -1 跟随系统时先读 `Settings.System.SCREEN_BRIGHTNESS`，否则第一次下滑从 0 跳）。
- 右半屏上下滑 → 媒体音量（`AudioManager.STREAM_MUSIC`，与物理音量键同步）。
- 横滑 → 进度预览，**松手才 `seekTo`**（拖动中反复 seek 会疯狂起播）；灵敏度 = 滑满整屏 ≈ 时长一半，`coerceAtLeast(30s)` 兜底短片；**直播/时长未知不响应横滑**。
- 双击 → 暂停/继续。
- 方向判定：`detectDragGestures` 里自累积 `accX/accY`，超 `touchSlop` 才锁定垂直/水平，避免「想调亮度先把画面拖歪」。
- **性能关键（别再糊回去）**：提示内容与显隐拆两个 state —— `hudContent: MutableState<GestureHint?>` 只在 `GestureHud` 内部读取（`content.value ?: return`），`hintVisible: Boolean` 只在开始/结束变。拖动中每帧只重组胶囊；若合成一个 state，每帧重组这个 1000 行的播放器 composable。拖动会话放普通类 `GestureSession`（非 Compose state）同理。
- 手势开始即 `areControlsVisible = false`（否则 HUD 与中央大播放按钮重叠）；另加 watchdog：`hintVisible && !dragging && 1.2s 无更新` 强制收起——两个 `pointerInput` 的收尾回调偶发丢失会让 HUD 永久挂住。
- **已知取舍**：加 `onDoubleTap` 后单击切控制栏有约 300ms 等待（Compose `detectTapGestures` 等双击判定超时）。若用户嫌钝，改手写 tap 检测（单击立即响应，双击撤销上一次切换），代价是双击瞬间控制栏闪一下。

### 10.2 画中画 PiP（Step 3.3 前半）
- 单 Activity 架构：进 PiP = 整个窗口缩小。详情页播放器层本就 `fillMaxSize + zIndex(10f)` 盖在最上，PiP 时隐藏选集面板、强制播放器 `fillMaxSize`，小窗里自然只剩画面。
- `PipController`（object）：`isInPip`（Compose state）/ `playerActive`（`MobileVideoPlayer` 的 `DisposableEffect` 维护，播放器销毁立即置 false）/ `visibleRoute`（`AppNavigation` 用 `currentBackStackEntryAsState` 上报）。
- **进 PiP 硬校验**：`playerActive && visibleRoute.startsWith("detail")`。路由校验是为了挡住 NavHost 280ms 退出转场期 `playerActive` 残留导致的「首页被缩进小窗」（用户截图实锤过这个现象，正常路径复现不出来，只有转场窗口能解释）。
- 画面比例来自 `onVideoSizeChanged`（乘 `pixelWidthHeightRatio`），`Rational` 必须钳制 0.42~2.39，超范围直接抛异常。
- `MainActivity.onUserLeaveHint` 里 `tryEnterPip()`；Manifest 已加 `supportsPictureInPicture` + `resizeableActivity`。
- PiP 中强关控制条/换台抽屉/手势提示，`onTap/onDoubleTap/onDrag` 全部 `isInPip` 短路，触摸交系统（放大/关闭）。
- **已知风险**：Manifest `screenOrientation="portrait"` 锁方向，部分 ROM 可能拒绝进 PiP（实测 ColorOS 17 正常）。若其它机型进不去：进 PiP 前临时 `requestedOrientation = UNSPECIFIED`、退出还原（会有一次方向跳变，慎用）。
- **弹幕（Step 3.3 后半）未做**：TVBox 采集站不提供弹幕数据，要做先定数据源。

### 10.3 详情页跨站线路聚合
- `TvBoxRepository.searchCrossSiteRoutes(keyword, excludeSiteKey)`：并发查所有 `searchable` 站（6s 超时），**只留片名精确同名**的条目（模糊命中会把「第二季」「剧场版」混进线路列表）。
- 详情页 `mergedData` state + `LaunchedEffect(finalData)` 异步聚合，**不阻塞首屏**：先显示当前站线路，查到再追加 chips；只标 `siteName` + `routeId` 前缀站 key，**不再改 `routeName`**，下游统一用 `displayName()` 展示。
- **切线路必须重置集数**，但 `selectedEpisodeId` **不能用 `selectedRouteIndex` 作 remember key**：自动换源回调发生在组合之外，带 key 会在下一次重组把刚写入的「同一集」覆盖回第一集（见 10.5）。改为无 key + 在 `onSelect` / `startPlayback` / `onSourceFailed` 三处显式重置。
- 线路 chips 已改 `LazyRow`（原来是普通 `Row`，超屏宽的 chip 直接被裁掉）。
- 日志 `MB-XSITE: +N 条线路 / M 个站点`；查不到同名就静默跳过。
- 注意：线路名 FEIFAN/FFM3U8 等是上游 `vod_play_from` 标识，**不代表站点**。
- **线路 chip 统一带来源站点名（2026-11-07，已验收）**：`VideoRoute.siteName` 由数据层 `parseMacCmsVodToDetail(vod, site.name)` 填入，5 个生成入口（首页/分类/跨站搜索/跨站聚合/分类独立拉取）全覆盖；UI 统一走 `PlayerModels.displayName()` = `站名·线路名 标签`（站名截掉括号内营销后缀）。此前只有跨站聚合手工把站名拼进 `routeName`，当前站的 FEIFAN/FFM3U8 没有前缀，用户看不出属于哪个配置源。跨站聚合现在只标 `siteName` + 保留 `routeId` 的站 key 前缀，不再改 `routeName`。详情页 `RouteChips` 与 `PlayerDetailScreen` 播放源列表同步生效；占位/旧数据无 `siteName` 时兜底用当前激活站点名。

- **单线路影片也显示播放源（2026-11-07 已验收）**：播放态（`:724`）与浏览态（`:826`）两处「播放源」区块原本都套着 `mergedData.routes.size > 1`，影片只有一条线路时整块（连 `SectionTitle`）被隐藏；选集块没有这个条件，所以表现成用户看到的「播放后只剩选集」。之前「绝命杀手第二季」有 2 条线路所以一直没暴露。两处统一放宽为 `mergedData.routes.isNotEmpty()`，顺序仍是「选集 → 播放源」；配合 `siteName` / `displayName()`，单线路也能看出当前是哪个站点、哪条源。**注意：独立播放页 `PlayerDetailScreen.kt:226`（直播 / 横屏使用）仍保留 `size > 1`，用户 2026-11-07 15:56 明确「那个不改了」，别顺手统一。**

### 10.4 详情页原地播放（2026-11-07 用户验收通过）
用户称「锚点重置与空间自适应原地播放」。点播放不再弹独立全屏播放页，改为详情页内三相位连续过渡，**这是点播的现行入口，`PlayerDetailScreen` 只在直播 / 横屏使用**。

- **几何由两个 `Animatable` 驱动，动画全程零重组**：`morph` 0→1 时 A 层用 `Modifier.layout` 把高度从父约束 `constraints.maxHeight` 收到 `屏宽×9/16` 并下移一个状态栏高度；B 层首块用**同一份 morph** 从 `heroHeight - statusBar` 收到视频高。两条高度必须同源，否则片名与视频下沿错开。所有进度只在 `layout` / `draw` / `graphicsLayer` 的 lambda 里读 —— 详情页一次重组上百 ms，用 `animateDpAsState` 必卡。
- **归位与收缩并行同长**：`scrollState.animateScrollTo(0, tween(320))` 与 `morph.animateTo(1f, tween(320))` 同时跑，内容从当前 offset 单调移动到贴住视频下沿，方向由当前偏移决定。串行两段（先回顶部再补位）会经过「刚进详情页」这个多余中间态，是用户明确否掉的。注意详情页是 `verticalScroll(ScrollState)` 不是 `LazyColumn`，`animateScrollToItem` 用不了；`animate{}` 的 block 非 suspend，也驱动不了 `scrollTo`。
- **播放器首次挂载必须放在动画结束之后**：`ExoPlayer.Builder` + `PlayerView` 首次 inflate 是 100~300ms 主线程重活，塞进动画窗口会吞掉滚动帧。
- **A/B 层与 SurfaceView 打洞**：播放态 A 层加 `zIndex(1f)`（视频压在内容之上，上滑内容滑进视频后面）；PiP 时 A 层铺满小窗、B 层 alpha=0。`PlayerView` 是 SurfaceView，画框区域内、在 `AndroidView` **之前**绘制的 Compose 内容会被擦掉 —— 所以氛围底必须用 `layout` 裁成「屏高 − 状态栏 − 视频高」并贴底，只覆盖视频下方；画框内要显示的东西（转圈、控制条、错误提示）只能放在播放器内部。想物理合成一张整屏背景就得换 TextureView，已明确排除。
- **氛围底**：`AmbientBackdrop`（`ui/components/BingWallpaper.kt`）由播放器加载态与详情页下方共用：海报 → http 降级 / CDN 拦截页重试 → 彻底失败回落 `BingWallpaperStore` 缓存壁纸 → 放大 1.18 + `blur(18.dp)` + 暗罩。详情页传 `showAmbientBackdrop = false`，画框只用 `playerBackdrop #0A0E17` 纯色，下方氛围底顶部 120dp 用同色不透明起步再渐显海报色，视频下沿同色相接。`Modifier.blur` 仅 Android 12+ 生效，低版本退化为未模糊海报。
- **播放态内容**：详情不删，只是把「选集 → 播放源」提到视频下方最前；唯一去掉的是「立即播放」按钮。播放态字色锁死浅色（浅色主题下整页压暗，继续用近黑字会黑底黑字），状态栏图标强制浅色，单集也显示「正片」chip。选集 / 线路 / 分类 chip 统一 `NeutralChip`（透明底 + 淡描边，选中靠主字色描边与字重区分），不引入主题色。

### 10.5 播放源自动接力与失败终态（2026-11-07 用户验收通过）
- 状态放详情页（只有它持有 `mergedData.routes`）：`attemptAnchor` 本轮起始线路、`attemptCount` 已尝试条数。失败按 `anchor + count` 轮转，集数按 `episodeId → 集名 → 第一集` 兜底，空集线路跳过，**所有线路都试完才出终态**。手动播放（立即播放 / 选集 / 播放源 chip）开启新一轮并清终态。
- 播放器新增 4 个带默认值的参数 `onSourceFailed` / `loadingHint` / `fatalError` / `reloadToken`，直播、横屏、`PlayerDetailScreen` 三条调用链零改动、不回归。

### 10.6 播放失败提示统一（2026-11-07 18:48 用户真机验收通过，提交 `d7c0a59`）
- **问题**：直播页失败时是一张深色卡片（`#161B22` 97% + 55% 黑底），卡片里「重试播放 / 返回详情」两个按钮的文字不渲染，只剩浅蓝底色与白色描边两根空条，用户 18:42 截图反馈「不知道是什么的条条」。
- **定案**：`MobileVideoPlayer` 里原本两套失败 UI（详情页终态 `fatalError` 的裸图标版、直播/横屏 `playbackError` 的卡片版）合并为一套 —— `val playbackErrorText = fatalError ?: playbackError`，只渲染居中的 44dp `ErrorOutline` + 14sp 一行原因，**无卡片、无全屏遮罩、无按钮**。
- **行为变化**：直播失败不再有「返回详情」按钮，退出走系统返回键（`BackHandler` 仍在），换台点下方频道列表；`retryCount` 仍由 SSL 降级重试（`403`）使用，不受影响。
- 遗留（已在 10.8 收尾）：直播失败文案当时仍是通用「播放失败，请更换播放源。」，18:57 已分流为「请切换频道」。
- 尝试期间加载态只有转圈 +「正在尝试 <真实播放源名>」，名称走 `routeDisplayName()`，与播放源 chip 逐字一致；首帧一出自动收起。首帧前整条控制条不显示（用户明确要求不要中央播放键与快进快退）。
- 全部失败后只画一个 `ErrorOutline` 图标 +「播放失败，请更换播放源。」，**无黑框、无卡片、无按钮**；换源点下方 chips，退出用系统返回键。终态一并关闭 PiP 资格、控制条、菊花、加载背景。
- **两个时序坑**：① 同一源重复报错的判重必须用 `activeStreamUrl`（真正送进 `prepare` 的 URL），用外部 `streamUrl` 会在换源重组那一帧连跳两条线路；手动重试同源靠 `LaunchedEffect(streamUrl, reloadToken)` 重置判重。② `selectedEpisodeId` 不能以 `selectedRouteIndex` 作 remember key（原因见 10.3）。
- `reloadToken` 让「点当前高亮 chip 重试同一个源」也能强制重新 prepare —— 此时 `streamUrl` 不变，光靠它不会触发重载。

### 10.7 横屏全屏控制条与「蓝色色块」（2026-11-07 17:1x 装机，用户真机验收通过）
- **用户反馈三点**（横屏全屏截图）：底部时间戳「00:01 / 57:17」贴着屏幕最下沿几乎看不见；右下角全屏切换按钮同样被裁；右上角有一块不知名的蓝色色块。
- **底栏**：横屏沉浸式隐藏系统栏后没有任何 insets，控制条直接压到物理边缘。修法是底栏加 `navigationBarsPadding()`，横屏左右内边距 16 → **32dp**、下内边距 8 → **24dp**；顶栏同样加横屏 32dp / 12dp 避让。
- **蓝色色块**：是控制条右侧那块 `MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)` 的「全屏影院」标签被挖孔 / 边缘裁掉文字后剩下的色块。横屏点播**直接隐藏该标签**（直播仍显示 `LIVE 电视直播` 红色标签）。
- **连带修的底层问题**：详情页横屏播放时，播放层仍按竖屏逻辑收成 16:9 高度，露出底层详情内容半截。现 `isLandscape && playTarget != null` 时播放器层**铺满整窗**，同时 B 层（详情内容）`alpha = 0` 隐藏 —— 与 PiP 的处理同源（`MovieDetailScreen.kt` 里同一个判断条件）。
- 横屏退出仍走 `BackHandler`（先关播放器再退出横屏），与 10.1 的手势互不冲突。

### 10.8 直播选台「一次点亮三行」与直播失败文案（2026-11-07 18:57 装机，18:59 用户真机验收通过，提交 `382c13f`）
- **现象**（用户 18:51 截图）：国际探索分组里 CGTN 法语 / 西班牙语 / 阿拉伯语三个频道**同时**显示为选中（浅紫底 + 蓝色圆形喇叭），顶部只显示一个台名。
- **根因**：`AnimatedMainScreen.kt` 判定当前台用的是 `channel.url == selectedChannel?.url`。而订阅源里这三个频道**主线路 URL 完全相同**（都是 `http://espanol-liveali.cgtn.com/hls/…220802cd.m3u8`），只靠备用线路区分（`0472.org/hls/cgtnx.m3u8`、`cgtna.m3u8`），所以一条 url 命中三行。已用 `adb run-as` 拉 `tvbox_subscription_prefs.preferences_pb` 脱库确认，不是猜的。
- **定案**：当前台判定改为**分组 + 频道名**（`it.name == channel.name && it.group == channel.group`）。`deduplicateGroups` 本来就按「分组名 + 频道名」去重，所以这个组合在列表内唯一；url 不再参与判定。播放器抽屉里的同款判断（`MobileVideoPlayer.kt` 频道列表）原来写的是 `url 相等 || 名字相等`，同样会多点亮，一并改掉。
- **直播失败文案**：`onPlayerError` 里的兜底文案按场景分流 —— 直播「播放失败，请切换频道。」，点播仍是「播放失败，请更换播放源。」。直播页没有播放源 chips，原文案指错了动作（10.6 遗留项收尾）。
- **未改**：「已切到线路 2/2 · 线路报错」这条自动切线路胶囊保持原样（切线路是真实行为，提示有用）；如嫌「线路报错」字样像故障告警，可另议。
- **源数据侧问题（客户端改不了）**：这三个台的主线路都指向 espanol CDN，即便选中法语，起播的第一条流也是西语台，要等自动切到备用线路才正确。属订阅源质量，不在 App 修。


---

## 十一、协作约定（当前维护者的硬性要求）

1. **开工前先汇报**（见文首【开工前必做】）：读到的进度、遗留问题、打算做什么，汇报完等用户确认再动手。
2. **改完只做 `assembleDebug` + `adb install -r`，然后立即交付**。编译是构建必需，保留。
3. **部署后不要自测**：不截图、不录屏、不切页自检、不主动找问题。**测试由用户发起**——只有用户说「你自己测试/帮我测试」或用文字描述不清时，才允许操作设备。（设备常被用户同时使用，盲发 tap/swipe 会误触返回键、滑出多任务。）
4. **Git 门禁**：只有用户明确说「验证通过 / 提交」，才 commit、push、勾 ROADMAP、写 CHANGELOG。
5. **`/docs/` 与 `/CHANGELOG.md` 是私密内部文档**（`.gitignore` 第 23–24 行），只在本地维护，**绝不 `git add -f` 强推**。提交时用 `git add` 明确列出代码文件，不要 `git add .`。同理 `/.workbuddy/`（本地工作记忆）也已加入 `.gitignore`，不要入库。
6. 用户偏好：不喜欢「多余装饰层」。任何背景、渐变、暗底、悬浮按钮都要先问清是否存在，不要叠加补丁。

---

## 十二、已知问题与下一步

**已知问题**
1. ~~设置页「MPV 高性能解码内核」开关是纯 UI 占位~~ —— **已收尾，18:59 验收通过，提交 `a5d8044`**：假的 `isMpvKernel` 开关改成**只读状态行**（「解码内核 / 固定使用 Media3 ExoPlayer（硬解优先）· 双核 IJK / MPV 未接入」+ 右侧 `ExoPlayer` 徽章），不再有可拨但无效果、且文案谎称「已回退为 IJKPlayer」的控件。真双核（Step 3.1）用户已明确跳过，若将来要做，得先引入 IJK / MPV 依赖与 `Player` 抽象层，再回来把这行换成真开关。
2. 中间调海报（条带内明暗混杂）字色只能取对比度更好的一侧，个别字仍会偏糊。
3. 演职人员、剧照、预告片仍是测试数据占位，等刮削接口。
4. **手势单击有 ~300ms 延迟**（`onDoubleTap` 的 Compose 固有行为，见 10.1）。用户未表态是否接受，嫌钝就改手写 tap 检测。
5. **Step 3.2 手势 / Step 3.3 前半 PiP / 跨站聚合已于 2026-11-07 逐条真机验收通过**（用户 18:2x 明确「验证通过」），ROADMAP 对应条目已勾。原记录的「未实测」作废。
6. 首页部分海报偶发灰底不显示（图片 CDN 问题，查 `MB-IMG`）。
7. 源数据问题（非 App 缺陷，改不了客户端）：FEIFAN 线路对部分剧集报「媒体流编码格式无法识别」，切 FFM3U8 或跨站线路可播；现已由 10.5 的自动接力兜住，无需用户手动切。
8. **「我的」页与追剧的假数据已清除，整批改动 2026-11-07 16:29 用户真机验收通过**（16:05 持久化装机、16:19 列表页与分钟级精度装机、16:26 详情页入场显示兜底装机）：`ProfileScreen.kt` 的 `ViewingStats` 已由 `WatchStore` 真实播放历史推导（默认值全 0，删掉 `(2..5).random()` 与写死的 14 部 / 3 部），两个详情页的 `isFavorite` 已换成持久化收藏；统计时长已改为分钟级，四个入口已能点开列表页。详见 8.6 / 8.7。ROADMAP 对应两条补充迭代已勾选。
   - 残留限制（验收通过但仍是已知取舍）：`watchedMs` 含暂停 / 后台，是上界近似时长；独立播放页（直播 / 横屏）未接播放历史；列表条目跳详情需现取线路（8s 超时，找不到源就提示不跳）；**从列表进详情再返回 `home` 会落回 Pager 第 0 页（首页）**，其它 Tab 进详情也有同款既有行为，主界面 Pager 状态本轮未改。
9. **观影记录去重 / 播放进度 / 「我的」页排版修复已验收并提交 `c6f2797`**（2026-11-07 16:51 装机）：同集重复记录、拖动后看不到播放位置、两张计数卡片字体被裁、柱状图空柱误导、写死「稳步上升」徽章均已修，详见 8.8。
10. **「追剧清单」合并与追剧入口上移已验收并提交 `d3f6379`**（2026-11-07 18:37 装机，18:48 验收通过）：`PENDING` 枚举与 `clearPending()` 已删，待看清空语义变为整表清空；追剧按钮从底部操作行移到「立即播放」右侧（播放态在「选集」标题右侧），窄屏文案与暗场图标均未发现问题。详见 8.9。
11. **播放失败提示已统一并提交 `d7c0a59`**（2026-11-07 18:43 装机，18:48 验收通过）：直播 / 横屏的深色错误卡片与两个文字不显示的按钮已删除，改为与详情页终态一致的「图标 + 一行原因」。详见 10.6。
    - 直播场景文案已按 10.8 改为「播放失败，请切换频道。」（18:57 装机，18:59 验收通过，提交 `382c13f`）。
12. **WebDAV 同步的三条取舍**（详见 8.13）：删除不跨设备同步（纯并集合并，A 端删掉的会被云端带回来，要支持需墓碑标记）；应用密码明文存本机 `webdav_prefs`；纯手动触发，无后台自动同步。**没有前台服务**，同步长时间运行时切后台仍可能被系统中断。
13. **「全部观看」的分类时长口径**（详见 8.11）：直播时长从 2026-11-07 这版才开始累计，更早的历史没有直播数据；「电影 / 剧集」按记录的内容类型归集，源站类型字段缺失时可能被算进「剧集」。
14. **旧历史记录没有 `positionMs`**：`WatchRecord` 字段是后加的，Gson 缺字段按 0 处理 —— 老记录要**重新播一次并拖动 / 退出**才会有「看到 mm:ss」与续播能力，不会自动补。
15. **直播源里多频道共用同一条主线路**（订阅源质量问题，客户端改不了）：CGTN 法语 / 西班牙语 / 阿拉伯语三个台的 `url` 都是 `http://espanol-liveali.cgtn.com/hls/…220802cd.m3u8`，只有 `urls` 里的备用线路不同。所以选中法语台时，自动切线路前面的第一段实际是西语流，要靠自动跳线路纠正。选台高亮的误判已由 10.8 修掉，播放内容本身没法在客户端修。

> 详情页 B 层顶部安全区已于 2026-11-07 修复并验收（Step 2.3），见 4.3。

**下一步（按 ROADMAP）**
- **阶段二已全部关闭**（Step 2.1 / 2.2 / 2.3 / 2.4 均验收通过）
- **详情页原地播放 + 播放源自动接力 + 线路 chip 站点名**：2026-11-07 用户验收通过并已提交，见 10.4 / 10.5。
- **单线路影片也显示播放源**：2026-11-07 用户验收通过，见 10.3；独立播放页（直播 / 横屏）维持原样，用户明确不改。
- **Step 3.2 手势 / Step 3.3 前半 PiP / 跨站聚合**：2026-11-07 用户逐条真机验收通过，ROADMAP 已勾（Step 3.3 只有前半勾，弹幕仍空）。
- 追剧 / 播放历史真持久化 + 观影列表页 / 分钟级统计（见 8.6、8.7 与已知问题 8）：**16:29 验收通过，已提交 `cabf434` 并推送**。
- 观影记录去重 + 播放进度 + 「我的」页排版修复（见 8.8 与已知问题 9）：**验收通过，已提交 `c6f2797` 并推送**。
- 「追剧清单」合并 + 追剧入口上移（见 8.9 与已知问题 10）：**18:48 验收通过，已提交 `d3f6379` 并推送**。
- 播放失败提示统一（见 10.6 与已知问题 11）：**18:48 验收通过，已提交 `d7c0a59` 并推送**。
- **Step 5.2 WebDAV 云端同步**（见 8.13 与已知问题 12）：**18:30 验收通过，已提交 `65ee4b5` 并推送，ROADMAP 已勾**。
- 18:30 同批验收的还有：「全部观看」分类统计（8.11）、订阅弹框剔除直播源 + 排版（8.12）、播放行为开关（8.10）、横屏控制条（10.7）、统一弹窗容器（8.14）。
- **MPV 占位开关收尾（18:56 装机，18:59 验收通过，提交 `a5d8044`）**：设置页「MPV 高性能解码内核」假开关改为只读「解码内核 / 固定使用 Media3 ExoPlayer + ExoPlayer 徽章」，`isMpvKernel` state 已删。真双核（Step 3.1）不做。
- **直播选台高亮修正 + 直播失败文案（18:57 装机，18:59 验收通过，提交 `382c13f`）**：当前台改按「分组 + 频道名」判定，CGTN 三台共用主线路不再同时点亮；直播失败提示改为「请切换频道」。详见 10.8。
- Step 3.1 双核解码：**用户已明确跳过**，不再排期。
- Step 3.3 后半弹幕（先定数据源）→ Step 3.4 画质增强（Anime4K/FSR，渲染链路大改，风险最高）。
- Step 4.1 Spider 调度 / 4.2 去广告 + TMDB 刮削 / 4.3 离线下载：均卡在外部前置（爬虫执行环境、TMDB key 与广告样本、WorkManager），排在 3.x 之后。
