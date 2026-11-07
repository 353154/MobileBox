package com.damon.mobilebox.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil.request.ImageRequest

/**
 * 采集站图片 CDN 的 HTTPS 端口经常不可用（SSL 握手直接失败），只有 HTTP 明文可用。
 * Manifest 已声明 usesCleartextTraffic=true，这里把图片请求的 https 统一降级为 http。
 * 若后续订阅源改为只支持 https，只要把此函数改为直接返回原字符串即可恢复。
 */
fun String.toLoadableImageUrl(): String =
    if (startsWith("https://", ignoreCase = true)) {
        "http://" + substring("https://".length)
    } else {
        this
    }

/**
 * 图片 CDN 会间歇性返回 HTTP 200 + text/html 拦截页，Coil 解码失败导致海报空白。
 * 这里在 onError 时用新的 cache key 重新发起请求，最多重试 3 次。
 */
@Composable
fun rememberRetryableImageRequest(context: Context, url: String, maxRetry: Int = 4): ImageRequest {
    var attempt by remember(url) { mutableIntStateOf(0) }
    return remember(url, attempt) {
        ImageRequest.Builder(context)
            .data(url)
            .crossfade(false)
            // 缓存键保持与 URL 一致：Coil 不会缓存失败结果，但首页重试成功后
            // 详情页用同一 URL 能直接命中内存缓存，不必再向不稳定 CDN 发起新请求。
            .listener(
                onSuccess = { _, _ -> Log.d("MB-IMG", "OK $url attempt=$attempt") },
                onError = { _, result ->
                    Log.d("MB-IMG", "FAIL $url attempt=$attempt ${result.throwable.javaClass.simpleName}: ${result.throwable.message}")
                    if (attempt < maxRetry) attempt++
                }
            )
            .build()
    }
}
