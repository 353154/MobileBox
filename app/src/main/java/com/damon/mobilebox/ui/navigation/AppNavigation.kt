package com.damon.mobilebox.ui.navigation

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.ui.components.SharedPosterTransition
import com.damon.mobilebox.ui.detail.MovieDetailScreen
import com.damon.mobilebox.ui.main.AnimatedMainScreen
import com.damon.mobilebox.ui.player.PipController
import com.damon.mobilebox.ui.search.SearchScreen
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

    // 上报当前可见路由给画中画控制器：转场期间播放器实例还在，但用户眼前可能已经是别的页面
    val currentEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentEntry) {
        PipController.visibleRoute = currentEntry?.destination?.route
    }

    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = "home",
            // 与 sharedBounds 的 boundsTransform 同为 280ms：NavHost 的 AnimatedVisibility
            // transition 提供进度，海报才能从原卡片 bounds 展开到全屏、返回时逆向收缩。
            // 若设为 EnterTransition.None，转场瞬间完成，共享元素动画会被吃掉。
            enterTransition = { fadeIn(animationSpec = tween(280, easing = FastOutSlowInEasing)) },
            exitTransition = { fadeOut(animationSpec = tween(280, easing = FastOutSlowInEasing)) },
            popEnterTransition = { fadeIn(animationSpec = tween(280, easing = FastOutSlowInEasing)) },
            popExitTransition = { fadeOut(animationSpec = tween(280, easing = FastOutSlowInEasing)) }
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
                    },
                    onNavigateToSearch = { navController.navigate("search") }
                )
            }
            composable("search") {
                SearchScreen(
                    onBack = { navController.popBackStack() },
                    onMovieClick = { hit ->
                        TvBoxRepository.selectedDetail = hit.detail
                        val encodedTitle = try { URLEncoder.encode(hit.detail.title, "UTF-8") } catch (_: Exception) { "" }
                        val encodedPoster = try { URLEncoder.encode(hit.detail.posterUrl, "UTF-8") } catch (_: Exception) { "" }
                        navController.navigate("detail/${hit.detail.id.trim()}?title=$encodedTitle&poster=$encodedPoster")
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable
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

                // 由导航生命周期显式维护「当前共享元素是哪家」：详情页在栈上的整个期间
                // （含 280ms 返回动画），目标卡片收敛为纯海报，与详情页 A 层一致。
                // NavHost 会在 pop 动画结束后才 dispose 本 composable，那时 Overlay 已消失，
                // 正是让卡片平滑恢复 3D 的时机。不要用 isTransitionActive 判断（全局且不可追踪）。
                DisposableEffect(movieId) {
                    SharedPosterTransition.activeKey.value = "poster_$movieId"
                    onDispose { SharedPosterTransition.activeKey.value = null }
                }

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
