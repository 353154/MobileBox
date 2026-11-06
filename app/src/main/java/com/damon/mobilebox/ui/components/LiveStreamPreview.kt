package com.damon.mobilebox.ui.components

import android.graphics.SurfaceTexture
import android.view.TextureView
import android.widget.FrameLayout
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * 直播流的静音模糊预览层。
 *
 * 用途：进入直播页 / 切换频道时立即预拉当前频道，把真实画面实时高斯模糊后作为背景，
 * 取代原先那块纯黑容器。声音恒为 0，不视为「播放」；只有点中间大播放按钮才挂载正式播放器。
 *
 * 关键技术点：必须用 TextureView 而不是 PlayerView 默认的 SurfaceView。
 * SurfaceView 绘制在独立窗口，Compose 的 RenderEffect / Modifier.blur 作用不到它，
 * 会出现「模糊层盖不住、清晰画面直接露出来」。TextureView 在 View 层级内绘制，可被模糊。
 * 用户已确认无需为低配机型做降级，因此这里直接做逐帧实时模糊。
 * 注：Modifier.blur 依赖 Android 12 (API 31) 的 RenderEffect，更低版本为 no-op（显示原画面）。
 */
@UnstableApi
@Composable
fun LiveStreamPreview(
    streamUrl: String,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 34.dp,
    onFirstFrame: (() -> Unit)? = null
) {
    val context = LocalContext.current

    val exoPlayer = remember(context) {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; MobileBox) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(25000)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                volume = 0f            // 预览恒静音
                playWhenReady = true   // 需要持续解码才能有实时画面
            }
    }

    LaunchedEffect(streamUrl) {
        if (streamUrl.isBlank()) return@LaunchedEffect
        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .apply {
                if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                }
            }
            .build()
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                onFirstFrame?.invoke()
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            val textureView = TextureView(ctx)
            textureView.apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        exoPlayer.setVideoTextureView(textureView)
                    }

                    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        exoPlayer.setVideoTextureView(null)
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        },
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // 大半径模糊会让边缘 alpha 衰减、透出底色；放大后由父级圆角裁掉虚边
                scaleX = 1.14f
                scaleY = 1.14f
            }
            .blur(blurRadius)
    )
}
