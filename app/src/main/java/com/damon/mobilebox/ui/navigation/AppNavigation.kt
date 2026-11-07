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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.damon.mobilebox.ui.person.PersonTimelineScreen
import com.damon.mobilebox.ui.player.PipController
import com.damon.mobilebox.ui.profile.WatchListScreen
import com.damon.mobilebox.ui.profile.WatchListType
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

    // 主界面当前 Tab 存放在 NavHost **之外**：进详情 / 观影列表时 home 这个 destination
    // 会被移出组合，只靠 Pager 自身的 rememberSaveable 恢复会丢，返回后落回第 0 页。
    // 放在这里（AppNavigation 不随导航销毁）就能稳定回到原 Tab。
    var mainPage by rememberSaveable { mutableIntStateOf(0) }

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
                    initialPage = mainPage,
                    onPageChanged = { mainPage = it },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    onNavigateToDetail = { movie ->
                        val encodedTitle = try { URLEncoder.encode(movie.title, "UTF-8") } catch (_: Exception) { "" }
                        val encodedPoster = try { URLEncoder.encode(movie.posterUrl, "UTF-8") } catch (_: Exception) { "" }
                        navController.navigate("detail/${movie.id.trim()}?title=$encodedTitle&poster=$encodedPoster")
                    },
                    // 首页 Hero 轮播：内容来自 TMDB，没有采集站 id，所以走独立路由。
                    // 详情页拿到 type + tmdbId 自己去取详情，并回订阅源搜可播条目。
                    onNavigateToTmdbDetail = { meta ->
                        val encodedTitle = try { URLEncoder.encode(meta.title, "UTF-8") } catch (_: Exception) { "" }
                        val encodedPoster = try { URLEncoder.encode(meta.posterUrl, "UTF-8") } catch (_: Exception) { "" }
                        navController.navigate(
                            "detail/tmdb/${meta.mediaType}/${meta.tmdbId}?title=$encodedTitle&poster=$encodedPoster"
                        )
                    },
                    onNavigateToSearch = { navController.navigate("search") },
                    onNavigateToWatchList = { type ->
                        navController.navigate("watchlist/${type.name}")
                    }
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
                route = "detail/{movieId}?title={title}&poster={poster}&resume={resume}&ep={ep}",
                arguments = listOf(
                    navArgument("movieId") { type = NavType.StringType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                    // 观看历史续播：resume = 上次播放位置(ms)，ep = 上次那一集。
                    // 首页 / 搜索等普通入口不带这两个参数，走默认值 0 = 浏览态。
                    navArgument("resume") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("ep") { type = NavType.IntType; defaultValue = 0 }
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

                val resumePositionMs = backStackEntry.arguments?.getLong("resume") ?: 0L
                val resumeEpisodeId = backStackEntry.arguments?.getInt("ep") ?: 0
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
                    resumePositionMs = resumePositionMs,
                    resumeEpisodeId = resumeEpisodeId,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    onBack = { navController.popBackStack() },
                    onPersonClick = { personId, personName ->
                        val encodedName = try { URLEncoder.encode(personName, "UTF-8") } catch (_: Exception) { "" }
                        navController.navigate("person/$personId?name=$encodedName")
                    },
                    onOpenRelated = { work ->
                        // 相关作品也是 TMDB 片，复用轮播那条详情路由。
                        val encodedTitle = try { URLEncoder.encode(work.title, "UTF-8") } catch (_: Exception) { "" }
                        val encodedPoster = try { URLEncoder.encode(work.posterUrl, "UTF-8") } catch (_: Exception) { "" }
                        navController.navigate(
                            "detail/tmdb/${work.mediaType}/${work.tmdbId}?title=$encodedTitle&poster=$encodedPoster"
                        )
                    }
                )
            }
            composable(
                route = "detail/tmdb/{type}/{tmdbId}?title={title}&poster={poster}",
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("tmdbId") { type = NavType.IntType },
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("poster") { type = NavType.StringType; defaultValue = "" }
                )
            ) { backStackEntry ->
                val type = backStackEntry.arguments?.getString("type").orEmpty()
                val tmdbId = backStackEntry.arguments?.getInt("tmdbId") ?: 0
                val rawTitle = backStackEntry.arguments?.getString("title").orEmpty()
                val rawPoster = backStackEntry.arguments?.getString("poster").orEmpty()
                val routeTitle = try { URLDecoder.decode(rawTitle, "UTF-8") } catch (_: Exception) { rawTitle }
                val routePoster = try { URLDecoder.decode(rawPoster, "UTF-8") } catch (_: Exception) { rawPoster }
                // 轮播卡片本身没有 sharedBounds（不在海报网格里），这里的 key 只是让
                // 详情页在栈上期间不误伤首页海报的 3D 状态；不会命中任何卡片，等于无操作。
                val tmdbKey = "tmdb:$type:$tmdbId"
                DisposableEffect(tmdbKey) {
                    SharedPosterTransition.activeKey.value = "poster_$tmdbKey"
                    onDispose { SharedPosterTransition.activeKey.value = null }
                }

                MovieDetailScreen(
                    movieId = tmdbKey,
                    initialTitle = routeTitle,
                    posterUrl = routePoster,
                    videoData = null,
                    tmdbType = type,
                    tmdbId = tmdbId,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    onBack = { navController.popBackStack() },
                    onPersonClick = { personId, personName ->
                        val encodedName = try { URLEncoder.encode(personName, "UTF-8") } catch (_: Exception) { "" }
                        navController.navigate("person/$personId?name=$encodedName")
                    },
                    onOpenRelated = { work ->
                        // 相关作品也是 TMDB 片，复用轮播那条详情路由。
                        val encodedTitle = try { URLEncoder.encode(work.title, "UTF-8") } catch (_: Exception) { "" }
                        val encodedPoster = try { URLEncoder.encode(work.posterUrl, "UTF-8") } catch (_: Exception) { "" }
                        navController.navigate(
                            "detail/tmdb/${work.mediaType}/${work.tmdbId}?title=$encodedTitle&poster=$encodedPoster"
                        )
                    }
                )
            }
            // 演员作品时间轴：详情页点头像、确认 TMDB 有资料后才走到这里。
            composable(
                route = "person/{personId}?name={name}",
                arguments = listOf(
                    navArgument("personId") { type = NavType.IntType },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" }
                )
            ) { backStackEntry ->
                val personId = backStackEntry.arguments?.getInt("personId") ?: 0
                val rawName = backStackEntry.arguments?.getString("name").orEmpty()
                val personName = try { URLDecoder.decode(rawName, "UTF-8") } catch (_: Exception) { rawName }
                PersonTimelineScreen(
                    personId = personId,
                    initialName = personName,
                    onBack = { navController.popBackStack() },
                    onOpenWork = { work ->
                        // 年表里的条目都是 TMDB 片，直接复用首页轮播那条详情路由，
                        // 详情页会自己按 type + id 补元数据并回订阅源搜可播线路。
                        val encodedTitle = try { URLEncoder.encode(work.title, "UTF-8") } catch (_: Exception) { "" }
                        val encodedPoster = try { URLEncoder.encode(work.posterUrl, "UTF-8") } catch (_: Exception) { "" }
                        navController.navigate(
                            "detail/tmdb/${work.mediaType}/${work.tmdbId}?title=$encodedTitle&poster=$encodedPoster"
                        )
                    }
                )
            }
            composable(
                route = "watchlist/{type}",
                arguments = listOf(navArgument("type") { type = NavType.StringType })
            ) { backStackEntry ->
                val listType = runCatching {
                    WatchListType.valueOf(backStackEntry.arguments?.getString("type").orEmpty())
                }.getOrDefault(WatchListType.HISTORY)
                WatchListScreen(
                    type = listType,
                    onBack = { navController.popBackStack() },
                    onOpenDetail = { detail, resumeRecord ->
                        // 列表里只存最小展示字段，进详情前先把真实线路挂到 selectedDetail，
                        // 详情页的 finalData 才会拿到可播放的 routes。
                        TvBoxRepository.selectedDetail = detail
                        val encodedTitle = try { URLEncoder.encode(detail.title, "UTF-8") } catch (_: Exception) { "" }
                        val encodedPoster = try { URLEncoder.encode(detail.posterUrl, "UTF-8") } catch (_: Exception) { "" }
                        // 只有观看历史传 record：自动起播到那一集，并从上次位置续播。
                        // 其余三个列表 record = null，进详情页保持浏览态。
                        val resumeMs = resumeRecord?.positionMs?.coerceAtLeast(0L) ?: 0L
                        val episodeId = resumeRecord?.episodeId ?: 0
                        navController.navigate(
                            "detail/${detail.id.trim()}?title=$encodedTitle&poster=$encodedPoster" +
                                "&resume=$resumeMs&ep=$episodeId"
                        )
                    }
                )
            }
        }
    }
}
