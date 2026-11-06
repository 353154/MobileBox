# MobileBox 📱

> 基于 [sayxiaoyan/Box](https://github.com/sayxiaoyan/Box) (TVBox 电视版) 深度重构的原生 **Android 手机端应用 (Mobile App)**。  
> **非简单 Fork**，而是遵循 **Clean Architecture (整洁架构)** 从零独立重写的现代化移动端工程。

---

## 🎯 重构目标与渊源

原开源项目 [sayxiaoyan/Box](https://github.com/sayxiaoyan/Box) 是一款针对智能电视与机顶盒设计的优秀播放器，但其在移动手机端存在严重水土不服：
- 强制固定横屏，页面排版在手机上严重变形；
- 强依赖电视遥控器 D-Pad 焦点按键 (`android:focusable`)，触控滑动粘滞甚至失效；
- 播放器缺乏移动端手势（无法通过滑动屏幕调节亮度、音量与快进）；
- 无手机挖孔刘海屏避让、无系统画中画悬浮窗、无原生生物识别安全锁。

**MobileBox** 旨在**复用原项目经过数年沉淀的稳定 Spider 爬虫解析、多仓源协议与核心流媒体解码逻辑**，同时彻底剥离 TV Leanback 束缚，为个人安卓触屏手机量身打造极致的现代原生移动交互体验。

---

## 🏗️ 架构与组件替换对照

| 重构维度 | 原 TVBox 实现 ([sayxiaoyan/Box](https://github.com/sayxiaoyan/Box)) | MobileBox 移动端重构实现 |
| :--- | :--- | :--- |
| **基础 UI** | `androidx.leanback` / 电视宽屏横向布局 | **Jetpack Compose / Material 3** / 手机底部四栏导航 |
| **交互体系** | 遥控器 D-Pad 物理按键焦点 | **全电容触屏**、滑动边缘手势、双击暂停、长按菜单 |
| **屏幕方向** | 强制横屏 (`landscape`) | **默认竖屏为主**，视频播放触发横竖屏与自动感应 |
| **工程架构** | 单一模块，老旧 Groovy 构建 | **多模块 Clean Architecture**，**Kotlin DSL**，**Hilt** 依赖注入 |
| **依赖管理** | 散落在各文件的硬编码版本字符串 | **Gradle Version Catalog (`libs.versions.toml`)** |
| **多媒体** | 遥控菜单选集调音量 | **全屏双核 (IJK+MPV)** + 左亮右音手势 + **后台自动画中画 (PiP)** |
| **安全机制** | 遥控器输入 4 位数字 PIN 码 | **AndroidX Biometric 硬件指纹 / 3D 面容隐私锁** |

---

## 📚 详细文档导航

- 📐 **架构全景说明书**：[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（含 Clean Architecture 拓扑、红线规则与持续交付铁律）
- 🗺️ **15 个小步路线图与真机验收表**：[docs/REFACTOR_ROADMAP.md](docs/REFACTOR_ROADMAP.md)
- 📜 **版本演进与操作审计日志**：[CHANGELOG.md](CHANGELOG.md)

---

## 📱 调试与真机基线
- **当前联调设备**：OPPO / OnePlus `CPH2723` (Android 17, arm64-v8a)
- **编译工具链**：JDK 17 + Android SDK 34 + Gradle 8.6
