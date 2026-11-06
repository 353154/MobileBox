# MobileBox 影视流媒体 App 完整交接与开发维护文档

> **文档版本**：v2.0 (全页面 UI 规范升级与动效重构交付版)  
> **更新时间**：2026-11-06  
> **分支**：`main`  
> **Git 远端**：`https://github.com/353154/MobileBox.git`

---

## 一、 项目背景与重构概述

本项目为基于 **Jetpack Compose + Material 3 + Media3 (ExoPlayer/MPV)** 的现代化 Android 影视与电视直播流媒体客户端。
近期重点攻克了两大维度的体验与工程难题：
1. **转场动效与架构稳定性**：攻克共享元素转场时序遮挡冲突、标题首帧闪烁与分类页回退重新加载（状态丢失）。
2. **全局五大核心页面 UI 精细化重塑**：解决底部导航栏挤压、全局对比度发虚、文本撑破双列网格、直播容器圆角打架、设置项视线引导线断裂及个人中心指标空洞等问题。

---

## 二、 核心重构成果详解

### 1. 共享元素转场与详情页时序编排 (MovieDetailScreen & AppNavigation)
- **根因诊断**：Compose `Modifier.sharedBounds` 在转场期间（0~380ms）将海报置于根窗口全局 `Overlay` 顶层。此前详情页内容区使用了负向位移 `Spacer(heroHeight - 48.dp)`，使标题恰好落入海报底部黑色渐变遮罩内。转场期间标题被海报 Overlay 完全遮蔽，转场结束时海报退出 Overlay 瞬间标题暴露，形成突兀的“跳出/闪烁”。
- **修复方案**：
  - 详情页占位精确对齐 `Spacer(modifier = Modifier.height(heroHeight))`，海报 Overlay 不遮挡标题任何像素。
  - 路由 `detail/{movieId}?title={title}&poster={poster}` 在第 0 帧直接透传片名与海报，从源头消灭空标题。
  - `contentAlpha`（延迟 60ms）与 `contentSlideY`（24dp 上浮）顺畅交错淡入，与海报着陆同频。
  - 海报高度克制在 33% 屏高（~240-270dp），首屏立即可见“立即播放”与选集，无需下滑。

### 2. 状态持久化与 0ms 回退恢复 (TvBoxRepository & CategoryScreen)
- **根因诊断**：原分类页将数据保存在本地 `remember { mutableStateOf }` 中，页面离开销毁后重组为 `emptyList()`，反复触发网络请求与加载菊花。
- **修复方案**：数据上移至 `TvBoxRepository` 二级缓存池 `categoryVodCache` 与 StateFlow 托管，从详情页返回时 0ms 闪电恢复，无需二次拉取。初次加载采用 1200ms 双色平滑线性扫光骨架屏。

### 3. 全局五大页面 UI 规范与体验升级

#### 3.1 全局与底层组件 (Global & Components)
- **底部导航栏**：
  - 指示器胶囊扩容至 `56.dp × 30.dp`，给予足够的横向呼吸空间，标签文本与胶囊边缘不再贴合。
  - 导航图标全面引入 **Outlined（未选中线框态）** 与 **Filled（选中实心填充态）** 双重感知（首页、分类、直播、设置、我的）。
- **全局色彩对比度**：
  - 浅色模式底色统一修正为纯正浅灰 **`#F2F2F7`**，移除导致卡片发虚的径向渐变雾化。
  - 所有卡片组件配置 `0.5.dp` 对比微边框（`outlineVariant.copy(alpha = 0.35f)`），边界清晰利落。

#### 3.2 首页 & 分类页 (Home & Category)
- **长文本截断**：[`StandardPosterCard.kt`](file:///Users/damon/Documents/MobileBox/app/src/main/java/com/damon/mobilebox/ui/components/StandardPosterCard.kt) 强制设定 `maxLines = 1`、`overflow = TextOverflow.Ellipsis` 与 `Modifier.fillMaxWidth()`，杜绝长字段撑高单项破坏网格垂直对齐。
- **分类 Filter Chips**：取消未选中的隐形极浅边框，直接采用可点击辨识度高的 `surfaceVariant`（`#E5E5EA`）浅灰底色，选中态使用主题色高亮，完全符合无障碍标准（A11y）。

#### 3.3 电视直播页 (Live TV)
- **圆角统一**：顶部播放器外框从夸张的 `20.dp+` 统一修正为 **`16.dp`**，与下方频道卡片严格对齐。
- **LIVE 胶囊内嵌**：红色 LIVE 胶囊完全移入播放器内部安全区域（`padding(16.dp)`），配合呼吸圆点指示灯，消除切断外框的“贴纸感”。
- **横向滑动渐变暗示 (Fading Edge)**：频道分类滑动栏右侧叠加 `32.dp` 透明至背景色的水平渐变消隐遮罩，明确提示右侧可继续水平滑动。

#### 3.4 设置页 (Settings)
- **视线引导线对齐**：为“MPV 高性能解码内核”补充统一的芯片图标（`Icons.Default.Memory`）与 `Spacer(14.dp)`，彻底消除主副标题左侧对齐断崖。
- **分组标题强化**：标题字重调整为 `FontWeight.Bold`（14.sp），颜色提升至高对比度 `onSurface`，拉大段落间距。

#### 3.5 我的页面 (Profile)
- **数据指标排版**：“我的收藏”与“在看剧集”重构为**24.sp 主题色大字重粗体数据 + 12.sp 辅助说明**的紧凑单列堆叠布局。
- **空状态卡片瘦身**：“历史观看”与“待看片单”空状态卡片高度压缩至 **68.dp**，采用圆形浅色图标底座 + 横向说明文案，消除大面积空洞感。
- **行动号召收敛**：移除卡片内多余冲突的“点击添加”提示，逻辑完全收敛至单一路标。

---

## 三、 修改文件清单索引

| 文件路径 | 修改模块 | 核心改动点 |
| :--- | :--- | :--- |
| `ui/main/AnimatedMainScreen.kt` | 导航栏 / 直播 / 设置 | 底栏双态图标与胶囊扩容、直播 16dp 圆角与 Fading Edge、MPV 图标对齐与分组增强 |
| `ui/components/ModernBackgroundContainer.kt` | 全局背景 | 移除模糊雾化渐变，提供纯正 `#F2F2F7` / `#0E121A` 高对比度基底 |
| `ui/theme/AppTheme.kt` | 主题色板 | 配置标准轻量灰底色与细边框色彩规范 |
| `ui/components/StandardPosterCard.kt` | 基础组件 | 标题/副标题强制单行 Ellipsis 截断，`key = "poster_${movieId.trim()}"` 统一对齐 |
| `ui/category/CategoryScreen.kt` | 分类页面 | 移除临时状态接入持久化 Repository，重构 Filter Chip 浅灰实心无边框底色 |
| `ui/profile/ProfileScreen.kt` | 个人中心 | 空状态瘦身至 68dp、指标主题色大字重聚焦、行动号召与点击区域整合 |
| `ui/navigation/AppNavigation.kt` | 根路由 | 路由原生透传 `title` 与 `poster` 参数，解决首帧片名空占位 |
| `ui/detail/MovieDetailScreen.kt` | 详情页面 | 解除海报全局 Overlay 遮挡，重构 Spacer 与底部消融渐变，交错时序编排 |
| `data/tvbox/TvBoxRepository.kt` | 数据仓库 | 引入 `categoryVodCache` 内存持久池，实现 0ms 回退无感恢复 |

---

## 四、 本地环境与调试指令

- **JDK 17 路径**：`/Users/damon/.tools/jdk-17.0.21+9/Contents/Home`
- **一键打包编译**：
  ```bash
  export JAVA_HOME=/Users/damon/.tools/jdk-17.0.21+9/Contents/Home && export PATH=$JAVA_HOME/bin:/Users/damon/Android/sdk/cmdline-tools/latest/bin:/Users/damon/platform-tools:$PATH && cd /Users/damon/Documents/MobileBox && ./gradlew assembleDebug
  ```
- **一键安装并冷启真机调试**：
  ```bash
  /Users/damon/platform-tools/adb -s 3B1F67EA9BBUHK76 install -r /Users/damon/Documents/MobileBox/app/build/outputs/apk/debug/app-debug.apk && /Users/damon/platform-tools/adb -s 3B1F67EA9BBUHK76 shell am force-stop com.damon.mobilebox && /Users/damon/platform-tools/adb -s 3B1F67EA9BBUHK76 shell am start -n com.damon.mobilebox/.ui.main.MainActivity
  ```
