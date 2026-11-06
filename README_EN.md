# MobileBox 📱

<p align="center">
  <a href="README.md">简体中文</a> | <strong>English</strong>
</p>

> A modern, high-performance Android streaming media client. Built from scratch following **Clean Architecture**, powered by **Jetpack Compose** and **Material Design 3**.

---

## ✨ Features

- **Modern Responsive UI**: Built with Jetpack Compose & Material 3, supporting dynamic palettes, hardware-accelerated aurora glow backgrounds, and instant theme color switching.
- **Single-Handed Mobile Interaction**: Seamless 4-tab bottom navigation, anti-misfire double-tier category filters, and physical displacement pull-to-refresh with spring rebound.
- **Standard 2:3 Golden Ratio Posters**: Unified vertical poster layout integrated with hardware gyroscope-driven 3D parallax tilt, depth translation, and dynamic specular sheen reflections.
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
