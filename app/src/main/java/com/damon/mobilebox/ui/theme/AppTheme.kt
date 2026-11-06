package com.damon.mobilebox.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemePreference(val value: Int, val title: String) {
    SYSTEM(0, "跟随系统"),
    LIGHT(1, "浅色模式"),
    DARK(2, "深色模式")
}

class ThemeManager(private val context: Context) {
    companion object {
        private val THEME_KEY = intPreferencesKey("theme_preference")
    }

    val themePreferenceFlow: Flow<ThemePreference> = context.dataStore.data.map { preferences ->
        val themeValue = preferences[THEME_KEY] ?: ThemePreference.SYSTEM.value
        ThemePreference.entries.find { it.value == themeValue } ?: ThemePreference.SYSTEM
    }

    suspend fun saveThemePreference(theme: ThemePreference) {
        context.dataStore.edit { preferences ->
            preferences[THEME_KEY] = theme.value
        }
    }
}

// 静态深色回退色板
private val DarkColors = darkColorScheme(
    background = Color(0xFF111418),
    surface = Color(0xFF1E2228),
    primary = Color(0xFFA8C7FA),
    onSurface = Color(0xFFE2E2E2),
    onSurfaceVariant = Color(0xFF8A939C),
    surfaceVariant = Color(0xFF253545)
)

// 静态浅色回退色板
private val LightColors = lightColorScheme(
    background = Color(0xFFF8F9FA),
    surface = Color(0xFFFFFFFF),
    primary = Color(0xFF0B57D0),
    onSurface = Color(0xFF1F1F1F),
    onSurfaceVariant = Color(0xFF5E5E5E),
    surfaceVariant = Color(0xFFD3E3FD)
)

@Composable
fun TvBoxTheme(
    themeManager: ThemeManager,
    dynamicColor: Boolean = true, // 默认开启 Material You 动态取色
    content: @Composable () -> Unit
) {
    val themePreference by themeManager.themePreferenceFlow.collectAsState(initial = ThemePreference.SYSTEM)
    val isSystemDark = isSystemInDarkTheme()
    
    // 确定当前是否应使用深色模式
    val useDarkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemDark
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val context = LocalContext.current
    
    // 核心取色逻辑：Android 12+ 提取系统壁纸颜色，否则使用静态回退色板
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDarkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            
            // 确保状态栏图标在任何壁纸颜色下都能正确反色可见
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !useDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
