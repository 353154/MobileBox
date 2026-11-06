# MobileBox: Clean Architecture 架构设计全景说明书

> **重构源项目 (Upstream)**：[https://github.com/sayxiaoyan/Box](https://github.com/sayxiaoyan/Box) (TVBox 电视端工程)  
> **目标工程 (Target)**：[https://github.com/353154/MobileBox](https://github.com/353154/MobileBox) (原生 Android 手机端独立重写应用)  
> **重构核心目标**：针对个人安卓手机（当前调试设备：`CPH2723`, Android 17）彻底剥离遥控器与电视限制，复用其成熟的 Spider 爬虫与数据源解析逻辑，重写为遵循 Clean Architecture 与纯触控体验的现代移动端应用。  
> **文档状态**：已冻结基线，处于分阶段执行中  
> **文档目标**：为本项目从 TVBox（电视版）重构为原生 Android 移动端（手机/折叠屏）提供顶层设计依据，保证后续任何开发者或 Agent 能够无缝接手。

---

## 0. 重构渊源与项目溯源 (Source Project Lineage)

| 属性 | 源项目 (Source TV Project) | 本重构项目 (MobileBox App) |
| :--- | :--- | :--- |
| **开源地址** | `https://github.com/sayxiaoyan/Box` | `https://github.com/353154/MobileBox` |
| **目标平台** | Android TV / 机顶盒 / 智能投影仪 | Android 手机 / 折叠屏 (原生移动设备) |
| **交互形态** | 遥控器 D-Pad 方向键、按键焦点框 | 全电容触控、边缘滑动手势、刘海挖孔屏沉浸避让 |
| **重构方式** | **并非简单 Fork** | **全新建立干净工程，复用核心逻辑，彻底重写 UI/架构** |
| **当前调试机型** | 智能电视 | 用户个人安卓手机：OPPO/一加 `CPH2723` (Android 17) |

---

## 1. 架构总览与模块拓扑 (Clean Architecture)

本项目遵循 Google 推荐的 **Clean Architecture (整洁架构)** 规范，采用单向依赖流与关注点分离（UI -> Domain <- Data），按照功能多模块（Multi-Module）架构组织：

```mermaid
graph TD
    subgraph App_Layer ["app 模块 (Application Shell)"]
        App[MobileBoxApp / Navigation / DI Setup]
    end

    subgraph Feature_Layers ["feature 业务表现层 (Jetpack Compose / MVI)"]
        FeatureVod[feature:vod (点播首页/海报流)]
        FeaturePlayer[feature:player (双核播放器/手势/画中画)]
        FeatureLive[feature:live (电视直播/频道EPG)]
        FeatureSearch[feature:search (聚合搜索/多源流)]
        FeatureSetting[feature:setting (配置中心/WebDAV/安全)]
    end

    subgraph Core_Layers ["core 核心基础层"]
        CoreUI[core:ui (Design System / 手势 / 动效)]
        CoreModel[core:model (全局 Domain Entity / 数据模型)]
        CoreData[core:data (Repository 接口与实现 / 缓存策略)]
        CoreDatabase[core:database (Room 本地数据库 / 历史与收藏)]
        CoreNetwork[core:network (OkHttp / Retrofit / Spider爬虫引擎)]
        CorePlayer[core:player-engine (IJKPlayer / MPV 双核抽象层)]
        CoreCommon[core:common (Dispatchers / 工具扩展 / 加密)]
    end

    App --> FeatureVod
    App --> FeaturePlayer
    App --> FeatureLive
    App --> FeatureSearch
    App --> FeatureSetting

    FeatureVod --> CoreUI
    FeatureVod --> CoreData
    FeatureVod --> CoreModel

    FeaturePlayer --> CoreUI
    FeaturePlayer --> CorePlayer
    FeaturePlayer --> CoreData

    FeatureLive --> CoreUI
    FeatureLive --> CoreData

    FeatureSearch --> CoreData
    FeatureSetting --> CoreData

    CoreData --> CoreDatabase
    CoreData --> CoreNetwork
    CoreData --> CoreModel
```

---

## 2. 依赖管理规范 (Gradle Version Catalog)

项目完全采用 Gradle 8.x + **Kotlin DSL (`build.gradle.kts`)**，所有库依赖、插件版本统一收敛至 `gradle/libs.versions.toml`。严禁在子模块中硬编码版本号或使用旧式 `ext` 变量。

### 核心技术栈基准

| 组件 | 技术选型 | 说明 |
| :--- | :--- | :--- |
| **语言** | Kotlin 1.9+ / Java 17 | 协程异步、严格空安全 |
| **UI 框架** | Jetpack Compose (BOM 2024.x) | 声明式响应式 UI，全面支持动态手势与动画 |
| **依赖注入** | Google Hilt 2.50+ | 模块间解耦、ViewModel 自动化注入 |
| **异步流** | Coroutines 1.8+ & Flow | 替代 RxJava / Handler / AsyncTask |
| **播放器内核**| IJKPlayer + MPV Lib | C/C++ 双解码器统一在 `core:player-engine` 封装门面 |
| **本地持久化**| Room 2.6+ | SQLite ORM，支持 Flow 响应式观察数据流 |
| **系统 API** | Android 14+ (API 34) 适配 | 最低兼容 API 24 (Android 7.0) |

---

## 3. TV 到 Mobile 核心差异与红线规则

所有参与重构的 Agent 必须严格恪守以下**重构红线**：
1. **禁止引入任何 Leanback 依赖**：绝对不可出现 `androidx.leanback` 相关的类与布局。
2. **禁止依赖焦点导航**：严禁在布局中写死 `android:focusable="true"`，禁用任何 D-pad 物理按键事件分发；所有交互基于点击、拖拽、长按、滑动边缘手势。
3. **禁止写死横屏**：主程序和列表页默认竖屏，仅在播放器全屏状态或用户开启自动旋转时触发横屏模式。
4. **废弃 API 零容忍**：诸如 `startActivityForResult`、旧式软键盘监听、旧版 Window 全屏沉浸等已被弃用的 API 必须使用 `ActivityResultContracts` 与 `WindowInsetsControllerCompat`。

---

## 4. Agent 协作与持续交付铁律 (Git & Validation Standard)

所有接手本项目的 Agent 必须严格遵守以下**流水线闭环规范**：
- **操作完全指南**：具体工作目录、命令 Cheatsheet、交接文档编辑 SOP 与 CHANGELOG 标准模板详见 [docs/HANDOVER_GUIDE.md](HANDOVER_GUIDE.md)。
- **小步验证原则**：每一个功能小步（Step X.X）编写完成后，必须首先完成本地编译并在连接的真机（通过 ADB）上安装拉起，抓取真机截图或日志确认运行无误。
- **真机验证后强制推仓**：**每次修改经真机验证通过后，必须立即将改动提交并同步推送至 GitHub 远程主仓库 (`https://github.com/353154/MobileBox.git` 的 `main` 分支)**，绝不允许在本地积压未推送代码。
- **审计日志同步追加**：每次推送前，必须在 `CHANGELOG.md` 中记录本次小步的核心操作、改动文件、真机验证状态与提交 Hash。


