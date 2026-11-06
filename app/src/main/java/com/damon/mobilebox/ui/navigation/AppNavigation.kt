package com.damon.mobilebox.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.ui.detail.MovieDetailScreen
import com.damon.mobilebox.ui.main.AnimatedMainScreen
import com.damon.mobilebox.ui.theme.ThemeManager
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 根导航层共享元素转场架构 (Shared Element Transition Navigation)
 * 使用 SharedTransitionLayout 包裹 NavHost，实现列表页海报与详情页大封面之间的无缝飞跃放大与返回收缩动效
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun AppNavigation(themeManager: ThemeManager) {
    val navController = rememberNavController()

    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = "home",
            enterTransition = { fadeIn(animationSpec = tween(380, easing = FastOutSlowInEasing)) },
            exitTransition = { fadeOut(animationSpec = tween(380, easing = FastOutSlowInEasing)) },
            popEnterTransition = { fadeIn(animationSpec = tween(380, easing = FastOutSlowInEasing)) },
            popExitTransition = { fadeOut(animationSpec = tween(380, easing = FastOutSlowInEasing)) }
        ) {
            composable("home") {
                AnimatedMainScreen(
                    themeManager = themeManager,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    onNavigateToDetail = { movie ->
                        val encodedTitle = try { URLEncoder.encode(movie.title, "UTF-8") } catch (_: Exception) { "" }
                        val encodedPoster = try { URLEncoder.encode(movie.posterUrl, "UTF-8") } catch (_: Exception) { "" }
                        navController.navigate("detail/${movie.id.trim()}?title=$encodedTitle&poster=$encodedPoster")
                    }
                )
            }
            composable(
                route = "detail/{movieId}?title={title}&poster={poster}",
                arguments = listOf(
                    navArgument("movieId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("poster") { type = NavType.StringType; defaultValue = "" }
                )
            ) { backStackEntry ->
                val movieId = backStackEntry.arguments?.getString("movieId")?.trim().orEmpty()
                val rawTitle = backStackEntry.arguments?.getString("title").orEmpty()
                val rawPoster = backStackEntry.arguments?.getString("poster").orEmpty()
                val routeTitle = try { URLDecoder.decode(rawTitle, "UTF-8") } catch (_: Exception) { rawTitle }
                val routePoster = try { URLDecoder.decode(rawPoster, "UTF-8") } catch (_: Exception) { rawPoster }

                val selectedDetail = TvBoxRepository.selectedDetail
                val effectiveDetail = selectedDetail?.takeIf { it.id.trim() == movieId }
                    ?: TvBoxRepository.realVodList.value.find { it.id.trim() == movieId }
                    ?: TvBoxRepository.categoryVodList.value.find { it.id.trim() == movieId }

                MovieDetailScreen(
                    movieId = movieId,
                    initialTitle = routeTitle.ifBlank { effectiveDetail?.title.orEmpty() },
                    posterUrl = routePoster.ifBlank { effectiveDetail?.posterUrl.orEmpty() },
                    videoData = effectiveDetail,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
