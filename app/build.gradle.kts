import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 分发用的默认订阅源不写进代码（避免把源地址提交到公开仓库），
// 从 local.properties 读取注入 BuildConfig。未配置时为空 = 保持「无内置源」行为。
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val defaultSubUrl: String = localProps.getProperty("DEFAULT_SUB_URL", "").trim()

android {
    namespace = "com.damon.mobilebox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.damon.mobilebox"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 首启若用户没有配置过订阅，就用它自动订阅一次（为空则保持空白引导）
        buildConfigField("String", "DEFAULT_SUB_URL", "\"$defaultSubUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 用 debug 密钥签名：产物可直接安装到别人的手机上做测试分发。
            // 正式上架前必须换成自己的 keystore（见 HANDOVER 第九章）。
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.navigation.compose)

    // DataStore 偏好存储
    implementation(libs.androidx.datastore.preferences)

    // Jetpack Compose 套件
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation)

    // 图片异步加载 Coil
    implementation(libs.coil.compose)

    // 视频播放器 AndroidX Media3 (ExoPlayer)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    // 网络请求与 JSON (TVBox 协议支持)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Hilt 核心注入库与 KSP 编译器
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
