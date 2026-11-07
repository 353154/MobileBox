package com.damon.mobilebox.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 海报明暗采样结果：决定「直接压在海报上的文字」该用浅色还是深色。
 *
 * 详情页首屏的片名/元信息按设计直接叠在 A 层海报上，不铺任何暗底（用户明确要求，
 * 任何黑阶都会切出水平硬边）。因此遇到浅色海报时白字会整片糊掉。
 * 这里对海报做一次极小尺寸解码（约 72×108），只在文字实际覆盖的条带内统计亮度，
 * 再用 WCAG 对比度决定字色，不需要在页面上加任何遮罩层。
 *
 * 亮度采用相对亮度（sRGB 线性化），对比度公式与 WCAG 2.1 一致。
 */
data class PosterTone(
    val analyzed: Boolean = false,
    /** 文字条带亮度 20 分位：深色字最怕这里暗 */
    val textBandLow: Float = 0f,
    /** 文字条带亮度 80 分位：白字最怕这里亮 */
    val textBandHigh: Float = 0f,
    /** 顶部条带（状态栏/返回键）亮度 80 分位 */
    val topBandHigh: Float = 0f
) {
    /** 白字（Y=1.0）与条带最亮处的对比度 */
    private val whiteContrast: Float get() = 1.05f / (textBandHigh + 0.05f)

    /** 近黑字（#111418，Y≈0.007）与条带最暗处的对比度 */
    private val darkContrast: Float get() = (textBandLow + 0.05f) / 0.057f

    /**
     * 片名区是否改用深色文字。
     * 优先保证 3:1 的可读下限；两极都不达标时（花哨海报）取对比度更好的一侧。
     */
    val prefersDarkText: Boolean
        get() {
            if (!analyzed) return false
            if (whiteContrast >= 3f) return false
            if (darkContrast >= 3f) return true
            return darkContrast > whiteContrast
        }

    /** 状态栏图标是否要切成深色（顶部偏亮时白图标看不见） */
    val prefersDarkSystemBar: Boolean
        get() = analyzed && 1.05f / (topBandHigh + 0.05f) < 3f

    override fun toString(): String =
        "Tone(darkText=$prefersDarkText, darkBar=$prefersDarkSystemBar, " +
            "textBand=${"%.3f".format(textBandLow)}..${"%.3f".format(textBandHigh)}, " +
            "top=${"%.3f".format(topBandHigh)})"
}

/** 采样尺寸：够统计亮度分布，解码耗时在毫秒级 */
private const val SampleWidth = 72
private const val SampleHeight = 108

/** 同一 URL 的采样结果缓存：重新进详情页不会先白后黑地闪一下 */
private object PosterToneCache {
    private val map = object : LinkedHashMap<String, PosterTone>(24, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PosterTone>?) =
            size > 64
    }

    fun get(key: String): PosterTone? = synchronized(map) { map[key] }

    fun put(key: String, tone: PosterTone) {
        synchronized(map) { map[key] = tone }
    }
}

/**
 * 采样海报亮度。
 *
 * @param bandBottomFraction 文字条带下沿在图片高度上的占比（0..1）。详情页海报以
 *        ContentScale.Crop 铺满整屏、且裁掉的是左右，所以图片纵向比例与屏幕纵向比例一致，
 *        直接把「片名所在的屏幕位置」当作图片位置即可。
 * @param bandHeightFraction 条带高度占比，覆盖片名 + 元信息两行。
 */
@Composable
fun rememberPosterTone(
    url: String,
    bandBottomFraction: Float,
    bandHeightFraction: Float = 0.22f
): PosterTone {
    val context = LocalContext.current
    val bottom = bandBottomFraction.coerceIn(0.1f, 1f)
    val height = bandHeightFraction.coerceIn(0.05f, 0.6f)
    // 缓存键带上条带位置：不同屏幕高度下条带不一样，不能混用
    val key = remember(url, bottom, height) { "$url#${(bottom * 100).toInt()}_${(height * 100).toInt()}" }

    var tone by remember(key) {
        mutableStateOf(PosterToneCache.get(key) ?: PosterTone())
    }
    LaunchedEffect(key) {
        if (url.isBlank() || tone.analyzed) return@LaunchedEffect
        val result = analyzePosterTone(context, url, bottom, height)
        if (result != null) {
            PosterToneCache.put(key, result)
            tone = result
        }
    }
    return tone
}

private suspend fun analyzePosterTone(
    context: Context,
    url: String,
    bandBottom: Float,
    bandHeight: Float
): PosterTone? = withContext(Dispatchers.IO) {
    val request = ImageRequest.Builder(context)
        .data(url)
        .size(SampleWidth, SampleHeight)
        // 硬件位图读不了像素，必须走软件位图；尺寸极小，代价可忽略
        .allowHardware(false)
        .build()
    val bitmap = runCatching {
        (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toSoftwareBitmap()
    }.getOrNull() ?: return@withContext null

    val tone = computeTone(bitmap, bandBottom, bandHeight)
    Log.d("MB-TONE", "url=$url $tone")
    tone
}

private fun Drawable.toSoftwareBitmap(): Bitmap {
    if (this is BitmapDrawable && bitmap != null && bitmap.config != Bitmap.Config.HARDWARE) {
        return bitmap
    }
    val width = intrinsicWidth.coerceAtLeast(1)
    val height = intrinsicHeight.coerceAtLeast(1)
    val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(target)
    setBounds(0, 0, width, height)
    draw(canvas)
    return target
}

private fun computeTone(bitmap: Bitmap, bandBottom: Float, bandHeight: Float): PosterTone {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return PosterTone()

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    val luminance = FloatArray(pixels.size) { i ->
        val color = pixels[i]
        relativeLuminance(
            ((color shr 16) and 0xFF) / 255f,
            ((color shr 8) and 0xFF) / 255f,
            (color and 0xFF) / 255f
        )
    }

    val textBand = bandPercentiles(luminance, width, height, bandBottom - bandHeight, bandBottom)
    val topBand = bandPercentiles(luminance, width, height, 0f, 0.16f)
    return PosterTone(
        analyzed = true,
        textBandLow = textBand.first,
        textBandHigh = textBand.second,
        topBandHigh = topBand.second
    )
}

/** 横向留白 10%：海报左右会被裁掉，只统计大概率可见的中间区域 */
private fun bandPercentiles(
    luminance: FloatArray,
    width: Int,
    height: Int,
    yFrom: Float,
    yTo: Float
): Pair<Float, Float> {
    val y0 = (yFrom.coerceIn(0f, 1f) * height).toInt().coerceIn(0, height - 1)
    val y1 = (yTo.coerceIn(0f, 1f) * height).toInt().coerceIn(y0 + 1, height)
    val x0 = (width * 0.10f).toInt().coerceIn(0, width - 1)
    val x1 = (width * 0.90f).toInt().coerceIn(x0 + 1, width)

    val bins = IntArray(32)
    var count = 0
    for (y in y0 until y1) {
        val row = y * width
        for (x in x0 until x1) {
            bins[(luminance[row + x] * 31f).toInt().coerceIn(0, 31)]++
            count++
        }
    }
    if (count == 0) return 0f to 0f

    fun percentile(p: Float): Float {
        val target = count * p
        var accumulated = 0
        for (i in bins.indices) {
            accumulated += bins[i]
            if (accumulated >= target) return (i + 0.5f) / 32f
        }
        return 1f
    }
    return percentile(0.2f) to percentile(0.8f)
}

private fun relativeLuminance(r: Float, g: Float, b: Float): Float {
    fun linear(channel: Float): Float =
        if (channel <= 0.03928f) channel / 12.92f
        else {
            val v = (channel + 0.055f) / 1.055f
            v * v * v
        }
    return 0.2126f * linear(r) + 0.7152f * linear(g) + 0.0722f * linear(b)
}
