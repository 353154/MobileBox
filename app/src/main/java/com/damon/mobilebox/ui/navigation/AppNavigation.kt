package com.damon.mobilebox.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.ui.detail.MovieDetailScreen
import com.damon.mobilebox.ui.main.AnimatedMainScreen
import com.damon.mobilebox.ui.theme.ThemeManager

/**
 * 根导航层共享元素转场架构 (Shared Element Transition Navigation)
 * 使用 SharedTransitionLayout 包裹 NavHost，实现列表页海报与详情页大背景之间的物理级放大/缩小飞跃动效
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavigation(themeManager: ThemeManager) {
    val navController = rememberNavController()

    // 核心修复1：使用 SharedTransitionLayout 包裹整个导航体系
    SharedTransitionLayout {
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                // 传入 this@SharedTransitionLayout 和 this@composable 作用域
                AnimatedMainScreen(
                    themeManager = themeManager,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    onNavigateToDetail = { movieId ->
                        navController.navigate("detail/$movieId")
                    }
                )
            }
            composable("detail/{movieId}") { backStackEntry ->
                val movieId = backStackEntry.arguments?.getString("movieId") ?: ""
                val cachedPoster = TvBoxRepository.selectedDetail?.takeIf { it.id == movieId }?.posterUrl.orEmpty()
                MovieDetailScreen(
                    movieId = movieId,
                    posterUrl = cachedPoster,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
