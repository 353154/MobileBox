# MobileBox 影视流媒体 App 交互与详情页重构交付文档

## 一、 项目背景与重构目标
在原版实现中，影视详情页交互存在生硬切换、海报遮挡、标题闪烁跳出、分类页返回重新加载（状态丢失）以及视觉“毛坯感”等体验问题。
本次重构围绕**“沉浸式影院体验”**与**“商业级丝滑交互”**两大核心维度，全面重构了共享元素转场时序、状态流持久化、图层覆盖关系以及详情页视觉层级。

---

## 二、 核心问题剖析与技术根因

### 1. 标题闪烁 / 滞后弹出的物理根因
- **Overlay 遮挡机制**：Jetpack Compose 的 `Modifier.sharedBounds` 在转场期间（0~380ms）将海报提升并绘制在根窗口顶层的 `Overlay`。
- **布局负向偏移冲突**：此前详情页内容区使用了 `Spacer(heroHeight - 48.dp)`，使主标题上提了 48dp，正好落在了海报底部 48dp 的黑色渐变遮罩范围内。
- **视觉断层**：海报在顶层 Overlay 期间遮盖了标题，而标题下方的标签和播放按钮在海报外提前显现；等转场结束瞬间海报退回常规图层，标题突然显露，造成了明显的“文字跳闪”。

### 2. 分类页面回退重新加载（状态丢失）
- **本地 Transient 状态局限**：原分类页将影片列表存放在本地 `remember { mutableStateOf(...) }` 中，离开该页面时组件销毁，返回重组时重置为 `emptyList()`，导致每次返回都重复发起网络请求并展示转圈 Loading。

---

## 三、 重构架构与核心实现

### 1. 共享元素时序编排与图层解耦
- **零遮挡黄金占位**：将详情页内容区的占位高度精确设定为 `Spacer(modifier = Modifier.height(heroHeight))`。
- **纯黑微光消融**：顶部封面海报底端渐变过渡至 `CinemaBackground`（`#0A0E17`），内容区同样使用该背景色，二者实现 100% 顺滑物理无缝衔接。在转场飞行期间，海报 Overlay 不遮盖标题的任何像素。
- **路由首帧注入标题**：通过导航路由 `detail/{movieId}?title={title}&poster={poster}` 在第 0 帧直接透传片名与海报，杜绝因异步寻址导致的空标题渲染。
- **Staggered 交错动画**：
  - `contentAlpha`：延迟 60ms 柔和启动，在海报展开至 70% 时顺畅淡入。
  - `contentSlideY`：从 +24dp 平滑上浮至 0dp，与海报着陆时刻（380ms）完美同频同步。

### 2. 状态架构升级（0ms 瞬时回退恢复）
- **仓库级二级缓存池**：在 `TvBoxRepository` 中引入 `categoryVodCache`（`ConcurrentHashMap<String, List<VideoDetailData>>`）与全局持久 StateFlow `_categoryVodList`。
- **瞬时恢复**：从详情页返回分类列表时直接读取内存缓存，实现 0ms 闪电恢复，杜绝重复网络请求与状态丢失。
- **现代骨架扫光**：初次加载或切换采集分类时，引入 1200ms 双色平滑线性扫光骨架屏（`CategorySkeletonGrid`），告别简陋居中转圈。

### 3. 详情页商业级视觉重塑
- **黄金高度比例**：海报高度严格约束为屏幕高度的 33%（约 240~270dp），首屏立即可见“立即播放”胶囊与选集横向列表，无需向下滑动。
- **轻量化微晶交互**：彻底移除“追剧、缓存、投屏、分享”厚重刺眼的纯黑实心圆底，改用极简半透微光质感。
- **Tonal 胶囊标签**：取消生硬边框，改用低透明度主题色微光色块。
- **剧集标题智能清洗**：自动剔除剧集名称中冗长混乱的日期前缀（如 `20261030先导片` 自动格式化为 `10-30 先导片`）。
- **沉浸式 Edge-to-Edge**：详情页顶部状态栏自动适配浅色白图标，页面退出时自动还原。

---

## 四、 核心代码修改清单

| 文件路径 | 修改性质 | 核心职责与改动点 |
| :--- | :---: | :--- |
| `app/src/main/java/com/damon/mobilebox/ui/navigation/AppNavigation.kt` | 重构 | 路由支持 `?title={title}&poster={poster}` 参数透传，第 0 帧原生注入影片数据 |
| `app/src/main/java/com/damon/mobilebox/ui/detail/MovieDetailScreen.kt` | 重构 | 解除海报 Overlay 遮挡，重构 Spacer 与渐变，实现 Staggered 转场时序 |
| `app/src/main/java/com/damon/mobilebox/ui/components/StandardPosterCard.kt` | 优化 | 统一 `sharedBounds` 规范，对齐 0.7f 纵横比与圆角裁剪 |
| `app/src/main/java/com/damon/mobilebox/data/tvbox/TvBoxRepository.kt` | 新增/优化 | 引入 `categoryVodCache` 内存缓存与 StateFlow 托管，实现 0ms 返回恢复 |
| `app/src/main/java/com/damon/mobilebox/ui/category/CategoryScreen.kt` | 重构 | 移除本地临时状态，对接全局 Repository，集成现代 Shimmer 骨架屏 |
| `app/src/main/java/com/damon/mobilebox/ui/main/AnimatedMainScreen.kt` | 适配 | 适配 `onNavigateToDetail(movie)` 实体级透传，清理死代码 |

---

## 五、 环境与编译运行指令
- **JDK 路径**：`/Users/damon/.tools/jdk-17.0.21+9/Contents/Home`
- **编译命令**：
  ```bash
  export JAVA_HOME=/Users/damon/.tools/jdk-17.0.21+9/Contents/Home && export PATH=$JAVA_HOME/bin:/Users/damon/Android/sdk/cmdline-tools/latest/bin:/Users/damon/platform-tools:$PATH && cd /Users/damon/Documents/MobileBox && ./gradlew assembleDebug
  ```
- **安装与启动命令**：
  ```bash
  /Users/damon/platform-tools/adb -s 3B1F67EA9BBUHK76 install -r /Users/damon/Documents/MobileBox/app/build/outputs/apk/debug/app-debug.apk && /Users/damon/platform-tools/adb -s 3B1F67EA9BBUHK76 shell am force-stop com.damon.mobilebox && /Users/damon/platform-tools/adb -s 3B1F67EA9BBUHK76 shell am start -n com.damon.mobilebox/.ui.main.MainActivity
  ```
