package com.damon.mobilebox.data.tmdb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 一次 HTTP 调用的结果。区分「网络不可达」与「key 无效」，两者的降级策略不同。 */
internal sealed class HttpOutcome {
    data class Success(val body: String) : HttpOutcome()
    data class Failure(val code: Int, val detail: String) : HttpOutcome() {
        /** 401/403：key 本身的问题，换路线也没用。 */
        val isAuthError: Boolean get() = code == 401 || code == 403
        /** 0 以下：IO 异常 / 超时；429 / 5xx：服务端限流或故障。都只影响当前路线。 */
        val isRetryableElsewhere: Boolean get() = code < 0 || code == 429 || code >= 500
    }
}

/**
 * TMDB 网络层。只负责「按指定路线发一次请求」，降级编排在 [TmdbRepository]。
 *
 * 两条路线：
 * - DIRECT：直连官方 API，DNS 由 [TmdbDns] 接管（国内只被投毒、链路本身通畅），
 *   实测无代理也能 0.7 秒返回。**主路**。
 * - PROXY：内置 Cloudflare Worker。App 带 `Authorization: Bearer`，Worker 原样透传。
 *   注意 `workers.dev` 在国内是 SNI 阻断，只有挂代理才通，所以它是**后备**而不是主路。
 *
 * 凭据固定取自 [TmdbConfig.ACCESS_TOKEN]，App 内没有配置入口。
 */
internal object TmdbClient {
    /**
     * 部署 Cloudflare Worker 后填这里，形如 https://tmdb.example.workers.dev（结尾不要斜杠）。
     * 留空 = 只剩直连这一条路。
     */
    const val PROXY_BASE = "https://mobilebox-tmdb.y17321053161.workers.dev"

    /** Worker 侧的客户端标记，需与 worker.js 里的 CLIENT_TOKEN 一致；不匹配一律 403。 */
    const val CLIENT_TOKEN = "mobilebox-tmdb-2026"

    private const val DIRECT_BASE = "https://api.themoviedb.org"

    private val http = OkHttpClient.Builder()
        .dns(TmdbDns)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    enum class Route { PROXY, DIRECT }

    fun baseUrl(route: Route): String = when (route) {
        Route.PROXY -> PROXY_BASE
        Route.DIRECT -> DIRECT_BASE
    }

    fun isRouteConfigured(route: Route): Boolean = baseUrl(route).isNotBlank()

    /** v4 Read Access Token 是 JWT（eyJ 开头），走 Authorization；v3 API Key 走 query。 */
    fun isBearerToken(key: String): Boolean = key.startsWith("eyJ")

    suspend fun get(
        route: Route,
        path: String,
        params: Map<String, String>,
        token: String?
    ): HttpOutcome = withContext(Dispatchers.IO) {
        val base = baseUrl(route)
        if (base.isBlank()) return@withContext HttpOutcome.Failure(-2, "route not configured")

        val key = token?.trim().orEmpty()
        val builder = (base.trimEnd('/') + path).toHttpUrlOrNull()?.newBuilder()
            ?: return@withContext HttpOutcome.Failure(-2, "invalid url: $base$path")
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        // v3 key 必须挂在 query 上；v4 JWT 走 Authorization。内置凭据是 JWT，这里保留两条分支
        // 只是为了将来换回 v3 Key 时不用改调用方。
        if (key.isNotEmpty() && !isBearerToken(key)) builder.addQueryParameter("api_key", key)

        val request = Request.Builder()
            .url(builder.build())
            .header("Accept", "application/json")
            .header("X-MB-Client", CLIENT_TOKEN)
            .apply { if (key.isNotEmpty() && isBearerToken(key)) header("Authorization", "Bearer $key") }
            .get()
            .build()

        var outcome = execute(request, route, path, key)
        // 内置 A 记录可能因 Amazon 轮换而整体失效。此时禁用内置表，
        // 立刻用系统解析再试一次 —— 只重试一次，避免把 7 秒超时翻倍。
        if (outcome is HttpOutcome.Failure && outcome.code < 0 &&
            route == Route.DIRECT && TmdbDns.isBuiltinActive()
        ) {
            TmdbDns.markBuiltinFailed()
            android.util.Log.w("MB-TMDB", "builtin dns failed, retry with system resolver")
            outcome = execute(request, route, path, key)
        }
        outcome
    }

    private fun execute(
        request: Request,
        route: Route,
        path: String,
        key: String
    ): HttpOutcome {
        return runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                android.util.Log.d(
                    "MB-TMDB",
                    "http $route${if (key.isNotEmpty()) "(token)" else "(anon)"} $path -> ${response.code} ${body.length}B"
                )
                if (response.isSuccessful) HttpOutcome.Success(body)
                else HttpOutcome.Failure(response.code, body.take(160))
            }
        }.getOrElse {
            android.util.Log.w("MB-TMDB", "http $route $path failed: ${it.javaClass.simpleName}: ${it.message}")
            HttpOutcome.Failure(-1, it.message ?: it.javaClass.simpleName)
        }
    }
}
