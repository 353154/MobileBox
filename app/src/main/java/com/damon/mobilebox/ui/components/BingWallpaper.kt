package com.damon.mobilebox.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bing 每日壁纸组：直播预览加载期的「动态」兜底背景。
 *
 * 设计取舍：不做真正的视频/帧动画，而是 5 张静态图交叉淡入 + 极缓慢 Ken Burns 缩放，
 * 观感是活的，但开销只有几张 Bitmap 的绘制；视频首帧解码后轮播即停止并淡出。
 *
 * 缓存策略：5 个独立槽位，每张必须下载 + 解码成功才替换对应槽位；
 * 某张失败不影响其它张，也不会清空已有缓存 —— 永远至少有一张图可显示，不会黑屏。
 */
object BingWallpaperStore {
    const val COUNT = 5
    private const val PREFIX = "bing_wallpaper_"
    private const val LEGACY = "bing_wallpaper.jpg"
    private val APIS = listOf(
        "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=5&mkt=zh-CN",
        "https://cn.bing.com/HPImageArchive.aspx?format=js&idx=0&n=5&mkt=zh-CN"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun slot(context: Context, index: Int) = File(context.filesDir, "$PREFIX$index.jpg")

    /** 降采样解码：5 张 1920x1080 全尺寸会占约 40MB，缩到 960x540 足够 16:9 预览区 */
    private fun decodeOptions() = BitmapFactory.Options().apply { inSampleSize = 2 }

    /** 兼容升级前的单张缓存，避免首次升级后出现空窗 */
    private fun migrateLegacy(context: Context) {
        val old = File(context.filesDir, LEGACY)
        val dst = slot(context, 0)
        if (old.exists() && !dst.exists()) {
            runCatching { old.copyTo(dst, overwrite = false) }
        }
    }

    /** 读取本地缓存的所有壁纸；无缓存返回空列表（调用方保留深色底，不会黑屏） */
    fun cached(context: Context): List<Bitmap> {
        migrateLegacy(context)
        return (0 until COUNT).mapNotNull { i ->
            val f = slot(context, i)
            if (!f.exists() || f.length() < 1024) return@mapNotNull null
            runCatching { BitmapFactory.decodeFile(f.absolutePath, decodeOptions()) }.getOrNull()
        }
    }

    /** 拉取最近 5 张：逐槽位替换，只有成功的那张会更新 */
    suspend fun refresh(context: Context): List<Bitmap> = withContext(Dispatchers.IO) {
        val urls = fetchImageUrls()
        if (urls.isEmpty()) return@withContext cached(context)
        urls.take(COUNT).forEachIndexed { i, url ->
            runCatching {
                val bytes = download(url)
                if (bytes.size < 1024) return@runCatching
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions())
                    ?: return@runCatching
                val tmp = File(context.filesDir, "$PREFIX$i.tmp")
                tmp.writeBytes(bytes)
                val dst = slot(context, i)
                if (dst.exists()) dst.delete()
                if (!tmp.renameTo(dst)) tmp.delete()
            }
        }
        cached(context)
    }

    /** 客户端启动时后台预热缓存 */
    suspend fun prefetch(context: Context) {
        refresh(context)
    }

    private fun fetchImageUrls(): List<String> {
        for (api in APIS) {
            val result = runCatching {
                val images = JSONObject(fetch(api)).optJSONArray("images")
                if (images == null) {
                    emptyList()
                } else {
                    (0 until images.length()).mapNotNull { i ->
                        val path = images.getJSONObject(i).optString("url")
                        when {
                            path.isBlank() -> null
                            path.startsWith("http", ignoreCase = true) -> path
                            else -> "https://www.bing.com$path"
                        }
                    }
                }
            }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun fetch(url: String): String =
        client.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; MobileBox)")
                .build()
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Bing 元数据失败: ${resp.code}")
            resp.body?.string() ?: throw Exception("Bing 元数据为空")
        }

    private fun download(url: String): ByteArray =
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Bing 图片下载失败: ${resp.code}")
            resp.body?.bytes() ?: throw Exception("Bing 图片内容为空")
        }
}

/**
 * 动态壁纸层：本地缓存立即显示，5 张交叉淡入轮播 + 缓慢缩放；
 * active=false（直播首帧已到）时停止轮播，由调用方同步淡出整层。
 */
@Composable
fun BingWallpaperBackdrop(
    modifier: Modifier = Modifier,
    active: Boolean = true
) {
    val context = LocalContext.current
    var images by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { BingWallpaperStore.cached(context) }
        if (cached.isNotEmpty()) images = cached
        val fresh = withContext(Dispatchers.IO) { BingWallpaperStore.refresh(context) }
        if (fresh.isNotEmpty()) images = fresh
    }

    // 轮播：加载窗口通常只有几秒，2.8s 一切 + 700ms 交叉淡入，进页面就能看见变化
    LaunchedEffect(active, images.size) {
        if (!active || images.size < 2) return@LaunchedEffect
        while (true) {
            delay(2800L)
            index = (index + 1) % images.size
        }
    }

    val transition = rememberInfiniteTransition(label = "kenBurns")
    val zoom by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(7000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "kenBurnsZoom"
    )

    Box(modifier = modifier.background(Color(0xFF0B1220))) {
        Crossfade(
            targetState = index,
            animationSpec = tween(700),
            label = "wallpaperCrossfade"
        ) { idx ->
            val bmp = images.getOrNull(idx) ?: images.firstOrNull()
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 放大到裁剪区外，避免大半径模糊让边缘 alpha 衰减、露出底色
                            scaleX = 1.12f * zoom
                            scaleY = 1.12f * zoom
                        }
                        .blur(6.dp)
                )
            }
        }
        // 毛玻璃浓度：让预览层更"实"，不再被模糊洗成发灰的半透明
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1220).copy(alpha = 0.18f))
        )
    }
}
