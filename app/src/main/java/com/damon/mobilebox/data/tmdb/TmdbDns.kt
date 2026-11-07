package com.damon.mobilebox.data.tmdb

import android.os.SystemClock
import okhttp3.Dns
import java.net.InetAddress

/**
 * TMDB API 的专用 DNS。
 *
 * 国内的情况不是「域名被墙」，而是**只有解析这一环被投毒**：
 * - 系统解析 `api.themoviedb.org` 会拿到 `69.63.x` / `128.121.x` / `108.160.x` 这类
 *   与 TMDB 无关的地址（Facebook、Dropbox 的网段），连过去必然超时；
 * - 用真实 A 记录 + 原域名（SNI 不变）直连，实测 **HTTP 401 / 0.7 秒**，
 *   TLS 证书校验照常通过 —— 说明链路本身完全通畅。
 *
 * 同一域名下的其它主机名没有被投毒（`image.tmdb.org` 解析到 b-cdn.net，
 * 图片 200 / 1.1 秒），所以这里只对 API 主机名做特殊处理。
 *
 * 内置 IP 失效（Amazon 轮换）时不会永久损坏：连续失败即禁用内置表并退回系统解析，
 * 30 分钟后自动再试一次，无需用户清数据。
 */
internal object TmdbDns : Dns {
    private const val API_HOST = "api.themoviedb.org"

    /** 真实 A 记录，同一 /24 内四个，Amazon 轮换周期较长。 */
    private val BUILTIN = listOf("3.169.231.17", "3.169.231.97", "3.169.231.116", "3.169.231.119")

    private const val RESTORE_MS = 30L * 60 * 1000

    @Volatile
    private var disabledAt = 0L

    /** 内置表当前是否在用。网络层据此决定失败后要不要重试一轮系统解析。 */
    fun isBuiltinActive(): Boolean =
        disabledAt == 0L || SystemClock.elapsedRealtime() - disabledAt > RESTORE_MS

    fun markBuiltinFailed() {
        if (isBuiltinActive()) disabledAt = SystemClock.elapsedRealtime()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname != API_HOST) return Dns.SYSTEM.lookup(hostname)

        if (isBuiltinActive()) {
            val addrs = BUILTIN.mapNotNull(::literal)
            if (addrs.isNotEmpty()) return addrs
        }
        // 内置表不可用：退回系统解析。解析失败（无网）时再把内置表交出去搏一次。
        return runCatching { Dns.SYSTEM.lookup(hostname) }
            .getOrElse { BUILTIN.mapNotNull(::literal) }
    }

    /** 字面量 IP 不会触发 DNS 查询，这里只是转换成 InetAddress。 */
    private fun literal(ip: String): InetAddress? =
        runCatching { InetAddress.getByName(ip) }.getOrNull()
}
