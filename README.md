# MobileBox 📱

<p align="center">
  <strong>English</strong> | <a href="README_ZH.md">简体中文</a>
</p>

> A modern, high-performance Android streaming media client. Built from scratch following **Clean Architecture**, powered by **Jetpack Compose** and **Material Design 3**.

---

## ✨ Features

- **Modern Responsive UI**: Built with Jetpack Compose & Material 3, supporting dynamic palettes, hardware-accelerated aurora glow backgrounds, and instant theme color switching.
- **Single-Handed Mobile Interaction**: Seamless 5-tab bottom navigation, anti-misfire double-tier category filters, and physical displacement pull-to-refresh with spring rebound.
- **Immersive Detail Page**: Shared-element poster flight (enter and exit from the same card), fixed Layer-A hero with a Layer-B content sheet that scrolls over it, full-screen cross-fade veil driven by scroll, staggered entrance for each content group, and automatic light/dark text color picked from the poster's own luminance.
- **Standard 2:3 Golden Ratio Posters**: Unified vertical poster layout integrated with hardware gyroscope-driven 3D parallax tilt, depth translation, and dynamic specular sheen reflections.
- **Live TV with EPG**: Muted pre-buffered preview with real-time blur, rotating Bing wallpapers while loading, and current/next programme info (hidden entirely when the source has no EPG).
- **Direct-Play Multi-Source Engine**: Fat-client direct sniffing architecture with dynamic multi-source subscription switching and pure-text rule parsing.
- **Modern Build & Dependency Injection**: Pure Kotlin DSL, Gradle Version Catalog (`libs.versions.toml`), and modular Hilt DI architecture.

---

## 🏗️ Architecture Overview

- **Presentation Layer**: Jetpack Compose, Material Design 3, Navigation, Coil
- **Domain Layer**: Kotlin Coroutines & Flow, Use Cases, Clean Architecture
- **Data Layer**: Jetpack DataStore Preferences, Hilt DI, Retrofit / OkHttp
- **Media Engine**: Touch-gesture-driven mobile player core with dual-kernel decoding support

---

## 📄 License & Disclaimer

This project is created under the spirit of open-source, intended strictly for personal research and mobile software architecture study.
