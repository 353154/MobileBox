package com.damon.mobilebox.data.watch

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val BACKUP_VERSION = 1

/** 云端 backup.json 的内容。只同步收藏与历史，待看/在看都是派生视图，不单独存。 */
data class WebDavBackup(
    val version: Int = BACKUP_VERSION,
    val exportedAt: Long = 0L,
    val favorites: List<FavoriteItem> = emptyList(),
    val history: List<WatchRecord> = emptyList()
)

data class SyncResult(
    val success: Boolean,
    val message: String,
    /** 云端还没有备份文件：双向同步时直接改为上传本机数据。 */
    val remoteMissing: Boolean = false
)

/**
 * 标准 WebDAV 同步（OkHttp + Basic Auth），不引入第三方 WebDAV 库。
 *
 * - 上传：PUT <baseUrl>/<remoteDir>/backup.json；父目录不存在时补一次 MKCOL 再重试。
 * - 下载：GET 同一路径，404 视为「云端还没有备份」。
 * - 双向：先拉云端 → 与本机合并 → 写回本机 → 再把合并结果推回云端，
 *   两端最终是同一份数据。合并用「影片 + 集数」id 取并集，不丢任何一边。
 *
 * 合并口径（与 WatchStore.normalizeHistory 的「一集一条」保持一致）：
 * 收藏按 videoId 并集、addedAt 取较新；历史同一 id 的 watchedMs / durationMs 取较大值
 * （避免两端各自累加后重复翻倍），positionMs 与元信息取 startedAt 较新的那份。
 */
object WebDavSync {
    private const val FILE_NAME = "backup.json"
    private const val FAVORITE_LIMIT = 200
    private const val HISTORY_LIMIT = 50

    private val gson = Gson()
    private val backupType = object : TypeToken<WebDavBackup>() {}.type
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .build()

    private fun config() = WebDavConfig.snapshot

    private fun fileUrl(): String {
        val c = config()
        val base = c.baseUrl.trim().trimEnd('/')
        val dir = c.remoteDir.trim().trim('/').replace(Regex("\\s+"), "-")
        return if (dir.isEmpty()) "$base/$FILE_NAME" else "$base/$dir/$FILE_NAME"
    }

    private fun dirUrl(): String = fileUrl().substringBeforeLast('/') + "/"

    private fun authorized(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", Credentials.basic(config().username, config().password))

    private fun localBackup() = WebDavBackup(
        exportedAt = System.currentTimeMillis(),
        favorites = WatchStore.favorites.value,
        history = WatchStore.history.value
    )

    /**
     * 上传前先把目录建好。不能只在 PUT 返回 409 后才补 MKCOL：各家服务（坚果云等）
     * 首次 PUT 到不存在目录时返回的错误码不确定，先建目录能让首次上传直接成功。
     * 目录已存在时服务返回 405，忽略即可；最终成败交给随后的 PUT 判定。
     */
    private fun ensureRemoteDir() {
        if (config().remoteDir.trim().trim('/').isEmpty()) return
        try {
            val request = authorized(dirUrl()).method("MKCOL", null).build()
            client.newCall(request).execute().use { response ->
                // 消费响应体，避免 keep-alive 连接留下未读数据污染下一个请求
                response.body?.string()
            }
        } catch (_: Exception) {
            // 交给随后的 PUT 给出最终提示
        }
    }

    private fun push(backup: WebDavBackup): SyncResult {
        val body = gson.toJson(backup).toRequestBody(jsonMediaType)

        fun attempt(): Int {
            val request = authorized(fileUrl()).put(body).build()
            return client.newCall(request).execute().use { response ->
                response.body?.string()
                response.code
            }
        }

        ensureRemoteDir()
        var code = attempt()
        // 兜底：个别服务仍可能因目录层级缺失拒绝，补建一次再重试。
        if (code == 409 || code == 404) {
            ensureRemoteDir()
            code = attempt()
        }

        return when {
            code in 200..299 -> SyncResult(
                true,
                "已写入云端 backup.json（${backup.favorites.size} 条收藏 / ${backup.history.size} 条历史）"
            )
            code == 401 || code == 403 -> SyncResult(false, "账号或应用密码不对（HTTP $code）")
            else -> SyncResult(false, "上传被拒绝：HTTP $code，检查服务地址与目录权限")
        }
    }

    private fun fetch(): Pair<Int, WebDavBackup?> {
        val request = authorized(fileUrl()).get().build()
        return client.newCall(request).execute().use { response ->
            val code = response.code
            if (code !in 200..299) return@use code to null
            val text = response.body?.string().orEmpty()
            val backup = try {
                gson.fromJson(text, backupType) as? WebDavBackup
            } catch (_: Exception) {
                null
            }
            code to backup
        }
    }

    private fun merge(local: WebDavBackup, remote: WebDavBackup): WebDavBackup {
        val favoriteMap = LinkedHashMap<String, FavoriteItem>()
        (remote.favorites + local.favorites).forEach { item ->
            if (item.videoId.isBlank()) return@forEach
            val old = favoriteMap[item.videoId]
            if (old == null || item.addedAt >= old.addedAt) favoriteMap[item.videoId] = item
        }

        val historyMap = LinkedHashMap<String, WatchRecord>()
        (remote.history + local.history).forEach { record ->
            if (record.videoId.isBlank()) return@forEach
            val key = record.id.ifBlank { "${record.videoId}_${record.episodeId}" }
            val old = historyMap[key]
            if (old == null) {
                historyMap[key] = record.copy(id = key)
                return@forEach
            }
            val newer = if (record.startedAt >= old.startedAt) record else old
            val older = if (record.startedAt >= old.startedAt) old else record
            historyMap[key] = newer.copy(
                id = key,
                watchedMs = maxOf(old.watchedMs, record.watchedMs),
                durationMs = maxOf(old.durationMs, record.durationMs),
                positionMs = if (newer.positionMs > 0L) newer.positionMs else older.positionMs
            )
        }

        return WebDavBackup(
            exportedAt = System.currentTimeMillis(),
            favorites = favoriteMap.values.sortedByDescending { it.addedAt }.take(FAVORITE_LIMIT),
            history = historyMap.values.sortedByDescending { it.startedAt }.take(HISTORY_LIMIT)
        )
    }

    private fun notConfigured() = SyncResult(false, "请先填写服务地址、账号和应用密码并保存")

    private fun friendly(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "找不到服务器，检查服务地址"
        is java.net.SocketTimeoutException -> "连接超时，检查网络或服务地址"
        is javax.net.ssl.SSLException -> "HTTPS 证书校验失败"
        else -> e.message?.take(120) ?: "网络异常"
    }

    /** 本机 → 云端。换机前在旧设备点这个。 */
    suspend fun upload(): SyncResult = withContext(Dispatchers.IO) {
        if (!config().isConfigured) return@withContext notConfigured()
        try {
            push(localBackup())
        } catch (e: Exception) {
            SyncResult(false, "上传失败：${friendly(e)}")
        }
    }

    /** 云端 → 本机（与本机数据合并，不覆盖丢失）。换机后在新设备点这个。 */
    suspend fun download(): SyncResult = withContext(Dispatchers.IO) {
        if (!config().isConfigured) return@withContext notConfigured()
        val (code, remote) = try {
            fetch()
        } catch (e: Exception) {
            return@withContext SyncResult(false, "连接失败：${friendly(e)}")
        }
        when {
            code == 404 -> SyncResult(
                false,
                "云端还没有备份，请先在另一台设备点「上传备份」",
                remoteMissing = true
            )
            code == 401 || code == 403 -> SyncResult(false, "账号或应用密码不对（HTTP $code）")
            code !in 200..299 -> SyncResult(false, "读取备份失败：HTTP $code")
            remote == null -> SyncResult(false, "云端 backup.json 格式无法解析")
            else -> {
                val merged = merge(localBackup(), remote)
                try {
                    WatchStore.importBackup(merged.favorites, merged.history)
                } catch (e: Exception) {
                    return@withContext SyncResult(false, "合并写回失败：${friendly(e)}")
                }
                SyncResult(true, "已拉取并合并：${merged.favorites.size} 条收藏 / ${merged.history.size} 条历史")
            }
        }
    }

    /** 双向：拉取合并 → 写回本机 → 推回云端，两端最终一致。 */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        if (!config().isConfigured) return@withContext notConfigured()
        val (code, remote) = try {
            fetch()
        } catch (e: Exception) {
            return@withContext SyncResult(false, "连接失败：${friendly(e)}")
        }
        when {
            code == 404 -> {
                // 首次同步：云端为空，直接把本机数据铺上去
                try {
                    val up = push(localBackup())
                    if (up.success) SyncResult(true, "云端暂无备份，已上传本机数据：${up.message}") else up
                } catch (e: Exception) {
                    SyncResult(false, "上传失败：${friendly(e)}")
                }
            }
            code == 401 || code == 403 -> SyncResult(false, "账号或应用密码不对（HTTP $code）")
            code !in 200..299 -> SyncResult(false, "读取备份失败：HTTP $code")
            remote == null -> SyncResult(false, "云端 backup.json 格式无法解析")
            else -> {
                val merged = merge(localBackup(), remote)
                try {
                    WatchStore.importBackup(merged.favorites, merged.history)
                } catch (e: Exception) {
                    return@withContext SyncResult(false, "合并写回失败：${friendly(e)}")
                }
                try {
                    val up = push(merged)
                    if (up.success) {
                        SyncResult(true, "双向同步完成：${merged.favorites.size} 条收藏 / ${merged.history.size} 条历史已两端一致")
                    } else {
                        SyncResult(false, "本机已合并云端数据，但回传失败：${up.message}")
                    }
                } catch (e: Exception) {
                    SyncResult(false, "本机已合并云端数据，但回传失败：${friendly(e)}")
                }
            }
        }
    }
}
