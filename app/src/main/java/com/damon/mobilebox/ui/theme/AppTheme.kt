package com.damon.mobilebox.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemePreference(val value: Int, val title: String) {
    SYSTEM(0, "跟随系统"), LIGHT(1, "浅色模式"), DARK(2, "深色模式")
}

// 主题色模式枚举
enum class ThemeColorMode(val value: Int, val title: String) {
    DEFAULT(0, "默认极客蓝"),
    DYNAMIC(1, "跟随系统壁纸 (Android 12+)"),
    CUSTOM(2, "自定义颜色")
}

data class AppThemeState(
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val colorMode: ThemeColorMode = ThemeColorMode.DEFAULT,
    val customColorInt: Int = 0xFF2F81F7.toInt(), // 默认自定义起始颜色 (高饱和度亮蓝)
    val parallaxEnabled: Boolean = true // 陀螺仪 3D 视差海报开关
)

class ThemeManager(private val context: Context) {
    companion object {
        private val THEME_KEY = intPreferencesKey("theme_preference")
        private val COLOR_MODE_KEY = intPreferencesKey("color_mode")
        private val CUSTOM_COLOR_KEY = intPreferencesKey("custom_color")
        private val PARALLAX_KEY = booleanPreferencesKey("parallax_enabled")
    }

    val appThemeStateFlow: Flow<AppThemeState> = context.dataStore.data.map { pref ->
        AppThemeState(
            themePreference = ThemePreference.entries.find { it.value == (pref[THEME_KEY] ?: 0) } ?: ThemePreference.SYSTEM,
            colorMode = ThemeColorMode.entries.find { it.value == (pref[COLOR_MODE_KEY] ?: 0) } ?: ThemeColorMode.DEFAULT,
            customColorInt = pref[CUSTOM_COLOR_KEY] ?: 0xFF2F81F7.toInt(),
            parallaxEnabled = pref[PARALLAX_KEY] ?: true
        )
    }

    // 保留兼容属性供其他组件直接监听
    val themePreferenceFlow: Flow<ThemePreference> = appThemeStateFlow.map { it.themePreference }

    suspend fun saveThemePreference(theme: ThemePreference) {
        context.dataStore.edit { it[THEME_KEY] = theme.value }
    }

    suspend fun saveColorMode(mode: ThemeColorMode, customColor: Int? = null) {
        context.dataStore.edit { pref ->
            pref[COLOR_MODE_KEY] = mode.value
            customColor?.let { pref[CUSTOM_COLOR_KEY] = it }
        }
    }

    suspend fun saveParallaxEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PARALLAX_KEY] = enabled }
    }
}

private val DarkColors = darkColorScheme(
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    primary = Color(0xFF2F81F7),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF8B949E),
    surfaceVariant = Color(0xFF21262D)
)

private val LightColors = lightColorScheme(
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF),
    primary = Color(0xFF0969DA),
    onSurface = Color(0xFF1F2328),
    onSurfaceVariant = Color(0xFF656D76),
    surfaceVariant = Color(0xFFEAF0F5)
)

@Composable
fun TvBoxTheme(
    themeManager: ThemeManager,
    content: @Composable () -> Unit
) {
    val themeState by themeManager.appThemeStateFlow.collectAsState(initial = AppThemeState())
    val isSystemDark = isSystemInDarkTheme()
    
    val useDarkTheme = when (themeState.themePreference) {
        ThemePreference.SYSTEM -> isSystemDark
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val context = LocalContext.current
    
    // 核心引擎改造：根据用户配置动态决定色板
    val colorScheme = when (themeState.colorMode) {
        ThemeColorMode.DYNAMIC -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (useDarkTheme) DarkColors else LightColors
            }
        }
        ThemeColorMode.CUSTOM -> {
            val userPrimaryColor = Color(themeState.customColorInt)
            // 覆盖 primary，实现全局主题色一键替换
            if (useDarkTheme) DarkColors.copy(primary = userPrimaryColor) 
            else LightColors.copy(primary = userPrimaryColor)
        }
        ThemeColorMode.DEFAULT -> if (useDarkTheme) DarkColors else LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !useDarkTheme
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
