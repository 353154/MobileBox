package com.damon.mobilebox.ui.profile

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.data.watch.FavoriteItem
import com.damon.mobilebox.data.watch.WatchRecord
import com.damon.mobilebox.data.watch.WatchStore
import com.damon.mobilebox.ui.components.StandardDialog
import com.damon.mobilebox.ui.components.rememberRetryableImageRequest
import com.damon.mobilebox.ui.components.toLoadableImageUrl
import com.damon.mobilebox.ui.player.VideoDetailData
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「我的」页三个入口共用一个列表页，靠这个枚举区分数据源与标题。 */
enum class WatchListType(val title: String) {
    HISTORY("观看历史"),
    FAVORITES("追剧清单"),
    WATCHING("在看剧集")
}

private data class WatchListRow(
    val key: String,
    val videoId: String,
    val title: String,
    val posterUrl: String,
    val detailLine: String,
    val metaLine: String,
    /** 有值才在卡片底部画进度条；收藏 / 待看没有观看进度。 */
    val progressRatio: Float? = null,
    /** 进度条右侧文字，例如「看到 30:12 / 45:00」。 */
    val progressText: String = "",
    /** 只有观看历史行携带原始记录：点进去要自动起播到那一集并续播。 */
    val resumeRecord: WatchRecord? = null
)

private fun formatStamp(millis: Long): String =
    if (millis <= 0L) "" else SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()).format(Date(millis))

/** 播放位置：不到 1 小时用 mm:ss，超过用 h:mm:ss。 */
fun formatMediaTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1000L
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val secs = seconds % 60L
    return if (hours > 0L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, secs)
    }
}

private fun progressTextOf(record: WatchRecord): String {
    if (record.positionMs <= 0L) {
        // 旧版记录只记了时长、没记 positionMs，物理上无从续播。
        // 与其让用户点进去发现「从头发车、以为续播坏了」，不如在列表里说清楚：
        // 本集从开头播，播完这一次就会补上进度，下次即可正常续播。
        return if (record.watchedMs > 0L && !record.isLive) "无播放进度 · 从头播放" else ""
    }
    val position = formatMediaTime(record.positionMs)
    return if (record.durationMs > 0L) {
        "看到 $position / ${formatMediaTime(record.durationMs)}"
    } else {
        "看到 $position"
    }
}

private fun progressRatioOf(record: WatchRecord): Float? =
    if (record.positionMs > 0L && record.durationMs > 0L) {
        (record.positionMs.toFloat() / record.durationMs.toFloat()).coerceIn(0f, 1f)
    } else null

private fun joinNonBlank(vararg parts: String): String =
    parts.filter { it.isNotBlank() }.joinToString(" · ")

/** 只做「Store → 展示行」的映射，不写回任何数据。 */
private fun buildRows(
    type: WatchListType,
    favorites: List<FavoriteItem>,
    history: List<WatchRecord>
): List<WatchListRow> {
    // 直播频道不参与「待看 / 在看」的影片派生，只在历史里留下自己的记录。
    val watchedIds = history.filterNot { it.isLive }.map { it.videoId }.toSet()
    return when (type) {
        WatchListType.HISTORY -> history.map { r ->
            WatchListRow(
                key = r.id,
                videoId = r.videoId,
                title = r.title.ifBlank { "未知影片" },
                posterUrl = r.posterUrl,
                detailLine = if (r.isLive) {
                    joinNonBlank("电视直播", r.sourceName)
                } else {
                    joinNonBlank(r.episodeName, r.sourceName)
                        .ifBlank { if (r.isSeries) "剧集" else "影片" }
                },
                metaLine = joinNonBlank(
                    formatStamp(r.startedAt),
                    if (r.watchedMs > 0L) "累计 ${formatWatchDuration(r.watchedMs)}" else ""
                ).ifBlank { "刚开播" },
                progressRatio = progressRatioOf(r),
                progressText = progressTextOf(r),
                resumeRecord = r
            )
        }

        WatchListType.FAVORITES -> favorites.map { f ->
            WatchListRow(
                key = "fav_${f.videoId}",
                videoId = f.videoId,
                title = f.title.ifBlank { "未知影片" },
                posterUrl = f.posterUrl,
                detailLine = if (f.videoId in watchedIds) "已有观看记录" else "收藏后尚未播放",
                metaLine = joinNonBlank("加入于", formatStamp(f.addedAt))
            )
        }

        WatchListType.WATCHING -> history.filterNot { it.isLive }.groupBy { it.videoId }.values
            .sortedByDescending { group -> group.maxOf { it.startedAt } }
            .map { records ->
                val latest = records.maxByOrNull { it.startedAt } ?: records.first()
                WatchListRow(
                    key = "watching_${latest.videoId}",
                    videoId = latest.videoId,
                    title = latest.title.ifBlank { "未知影片" },
                    posterUrl = latest.posterUrl,
                    detailLine = joinNonBlank(
                        "最近观看 ${latest.episodeName.ifBlank { "影片" }}",
                        "共 ${records.distinctBy { it.episodeId }.size} 集"
                    ),
                    metaLine = joinNonBlank(
                        formatStamp(latest.startedAt),
                        "累计 ${formatWatchDuration(records.sumOf { it.watchedMs })}"
                    ),
                    progressRatio = progressRatioOf(latest),
                    progressText = progressTextOf(latest)
                )
            }
    }
}

/** 三个列表的删除语义不同：派生列表必须落到它真正的存储表上。 */
private fun removeRow(type: WatchListType, row: WatchListRow) {
    when (type) {
        WatchListType.HISTORY -> WatchStore.removeHistoryRecord(row.resumeRecord?.id ?: row.key)
        // 追剧清单 = 收藏表本身，删掉一条就是取消这一部的追剧
        WatchListType.FAVORITES -> WatchStore.removeFavorite(row.videoId)
        // 在看 = 历史按影片聚合，删掉一部就是清掉它全部点播记录
        WatchListType.WATCHING -> WatchStore.removeWatching(row.videoId)
    }
}

private fun clearRows(type: WatchListType) {
    when (type) {
        WatchListType.HISTORY -> WatchStore.clearHistory()
        WatchListType.FAVORITES -> WatchStore.clearFavorites()
        WatchListType.WATCHING -> WatchStore.clearWatching()
    }
}

/** 派生列表的清空会连带影响另一张表，确认文案必须说清楚。 */
private fun clearConfirmMessage(type: WatchListType, count: Int): String = when (type) {
    WatchListType.HISTORY -> "将清空全部 $count 条观看记录（含直播记录），不可恢复。"
    WatchListType.FAVORITES -> "将清空全部 $count 部追剧影片（含已开播的），不可恢复。"
    WatchListType.WATCHING -> "将清空 $count 部影片的全部观看记录；直播记录与追剧清单会保留。"
}

/**
 * 历史/收藏只存展示所需的最小字段，详情与线路仍从订阅源现取。
 * 优先命中内存缓存（首页/分类/上次详情），没有再按片名做一次全站精确同名搜索。
 * 绝不用 VideoDetailData.createFromMovie 兜底 —— 那份带的是官方测试流，点开会放错片。
 */
private suspend fun resolveDetail(row: WatchListRow): VideoDetailData? {
    val id = row.videoId.trim()
    TvBoxRepository.selectedDetail
        ?.takeIf { it.id.trim() == id && it.routes.isNotEmpty() }
        ?.let { return it }
    TvBoxRepository.realVodList.value
        .firstOrNull { it.id.trim() == id && it.routes.isNotEmpty() }
        ?.let { return it }
    TvBoxRepository.categoryVodList.value
        .firstOrNull { it.id.trim() == id && it.routes.isNotEmpty() }
        ?.let { return it }
    if (row.title.isBlank()) return null
    return TvBoxRepository.searchAll(row.title)
        .firstOrNull { it.detail.title.trim() == row.title.trim() && it.detail.routes.isNotEmpty() }
        ?.detail
}

@Composable
fun WatchListScreen(
    type: WatchListType,
    onBack: () -> Unit,
    /** 第二个参数是观看历史行的原始记录（续播用），其余三个列表传 null。 */
    onOpenDetail: (VideoDetailData, WatchRecord?) -> Unit
) {
    val favorites by WatchStore.favorites.collectAsState()
    val history by WatchStore.history.collectAsState()
    val rows = remember(type, favorites, history) { buildRows(type, favorites, history) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var resolvingKey by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    if (confirmClear) {
        StandardDialog(onDismissRequest = { confirmClear = false }) {
            Text(
                "清空${type.title}？",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                clearConfirmMessage(type, rows.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
                TextButton(onClick = {
                    clearRows(type)
                    confirmClear = false
                    Toast.makeText(context, "已清空${type.title}", Toast.LENGTH_SHORT).show()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = type.title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "共 ${rows.size} 条",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (rows.isNotEmpty()) {
                TextButton(
                    onClick = { confirmClear = true },
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Text("清空", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        )

        if (rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = when (type) {
                        WatchListType.HISTORY -> "还没有观看记录"
                        WatchListType.FAVORITES -> "还没有追剧的影片"
                        WatchListType.WATCHING -> "还没有在看的影片"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items = rows, key = { it.key }) { row ->
                    WatchListRowItem(
                        row = row,
                        resolving = resolvingKey == row.key,
                        onRemove = {
                            removeRow(type, row)
                            val tip = when (type) {
                                WatchListType.WATCHING -> "已删除该片全部观看记录"
                                WatchListType.FAVORITES -> "已移出追剧清单"
                                else -> "已删除"
                            }
                            Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
                        },
                        onClick = {
                            if (row.resumeRecord?.isLive == true) {
                                // 直播没有影片详情 / 续播位置，别让它白跑一次 8s 全站搜索
                                Toast.makeText(context, "直播频道请到「电视直播」页观看", Toast.LENGTH_SHORT).show()
                            } else if (resolvingKey == null) {
                                resolvingKey = row.key
                                scope.launch {
                                    val detail = runCatching {
                                        withTimeoutOrNull(8000L) { resolveDetail(row) }
                                    }.getOrNull()
                                    resolvingKey = null
                                    if (detail != null) {
                                        onOpenDetail(detail, row.resumeRecord)
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "未找到该影片的可用播放源",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WatchListRowItem(
    row: WatchListRow,
    resolving: Boolean,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    Card(
        onClick = onClick,
        // 高度自适应：带进度条的行比普通行多一行，写死 96dp 会把文字裁掉
        // （系统字体放大时尤其明显）。海报改固定高，不再依赖父 Row 的高度。
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (row.posterUrl.isNotBlank()) {
                AsyncImage(
                    model = rememberRetryableImageRequest(context, row.posterUrl.toLoadableImageUrl()),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(54.dp)
                        .height(76.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .width(54.dp)
                        .height(76.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = row.detailLine,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = row.metaLine,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // 「看到了哪里」：进度条 + 具体时间戳，拖动到 30 分也能直接看到。
                if (row.progressRatio != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(row.progressRatio)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                        if (row.progressText.isNotBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = row.progressText,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                } else if (row.progressText.isNotBlank()) {
                    // 没有进度比例但有说明（旧记录无 positionMs）：显示一行灰字，
                    // 不再画空进度条 —— 空条会让人误以为「进度是 0」。
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = row.progressText,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(2.dp))
            if (resolving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                // 删除按钮是内层可点控件：点击由它消费，不会连带触发整张卡片的进详情。
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(17.dp)
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
