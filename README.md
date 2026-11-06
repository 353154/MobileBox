# MobileBox 📱

> 一款基于现代 Android 架构打造的高性能流媒体移动客户端。遵循 **Clean Architecture (整洁架构)** 规范，采用 **Jetpack Compose** 与 **Material Design 3** 构建。

---

## ✨ 核心特性

- **现代响应式 UI**：基于 Jetpack Compose 与 Material Design 3，支持深浅双色主题、纯硬件加速极光渐变背景与动态主题色切换。
- **单手移动交互体验**：底部四栏平滑导航、分类防误触双层过滤、物理阻尼下拉推移与回弹动效。
- **全黄金比例海报**：统一 2:3 标准竖版海报规范，集成硬件陀螺仪驱动的 3D 景深悬浮透视与折射流光反射。
- **多数据源直连秒播**：重客户端直连架构，支持动态订阅源切换与纯文本规则解析。
- **现代化构建与依赖注入**：全面采用 Kotlin DSL、Gradle Version Catalog (`libs.versions.toml`) 与 Hilt 依赖注入。

---

## 🏗️ 架构概览

- **UI 呈现层 (Presentation)**：Jetpack Compose, Material 3, Navigation, Coil
- **业务逻辑层 (Domain)**：Kotlin Coroutines & Flow, UseCases, Clean Architecture
- **数据管理层 (Data)**：DataStore Preferences, Hilt DI, Retrofit / OkHttp
- **多媒体内核 (Player)**：移动端全手势触控内核抽象与双核解码支持

---

## 📄 开源声明

本项目基于开源精神打造，仅供个人技术研究与移动端架构演进交流学习使用。
