package com.damon.mobilebox.data.tmdb

import com.damon.mobilebox.BuildConfig

/**
 * TMDB 访问凭据（个人自用版）。
 *
 * **公开仓库里看到的这一版**：[PLACEHOLDER_TOKEN] 只是一串待填写字符，不是真实凭据。
 * 请不要把真实 token 写进本文件 —— 仓库是 public 的，写进来就会被推到 GitHub。
 *
 * **本地自用构建**：把真实 v4 Read Access Token 填到 `local.properties` 的 `TMDB_TOKEN=`
 * （该文件已被 `.gitignore` 忽略）。`app/build.gradle.kts` 会在编译期把它注入
 * `BuildConfig.TMDB_TOKEN`，也就是把值**直接硬编码进 APK**（编译期常量，自用可接受）。
 * 结论：GitHub 上永远是占位符，本地 APK 里是真实 key，两条路径互不污染。
 *
 * 三条约束：
 * - 没配 `TMDB_TOKEN` 时回落占位符，`isConfigured = false`，刮削整体关闭，不会拿假 token 发请求。
 * - token 编进 APK 后反编译可提取；若要对外分发，必须改走 `tools/tmdb-proxy/worker.js`，
 *   把 token 放 Worker 环境变量，而不是下发到客户端。
 * - 早前编进源码的那枚 token 已进过公开仓库历史（用户决定不重写历史），
 *   **必须由维护者到 TMDB 后台撤销 / 轮换**；代码里清空不会让旧串失效。
 */
internal object TmdbConfig {

    /**
     * ⚠️ 待填写占位符：提交到 GitHub 的就是这一串，**禁止替换成真实 token**。
     * 真实值只放在本地 `local.properties`，由构建脚本编译期注入 [BuildConfig.TMDB_TOKEN]。
     */
    const val PLACEHOLDER_TOKEN = "YOUR_TMDB_V4_READ_ACCESS_TOKEN_HERE"

    /** 本地编译期注入优先；未配置时回落占位符（见 [isConfigured]）。 */
    val ACCESS_TOKEN: String = BuildConfig.TMDB_TOKEN.ifBlank { PLACEHOLDER_TOKEN }

    /** 占位符等同于「没配置」，避免拿一串假 token 去打 TMDB 拿到 401。 */
    val isConfigured: Boolean get() = ACCESS_TOKEN.isNotBlank() && ACCESS_TOKEN != PLACEHOLDER_TOKEN

    /**
     * 运行时失效标记，不落盘：401 说明 token 被 TMDB 撤销或轮换，
     * 本次进程内不再重试，避免每部片子都白等一次 7 秒。
     */
    @Volatile
    var tokenInvalid: Boolean = false
        private set

    fun markInvalid() {
        tokenInvalid = true
    }

    fun clearInvalid() {
        tokenInvalid = false
    }
}
