package com.damon.mobilebox.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.data.tvbox.TvBoxSite
import com.damon.mobilebox.data.tvbox.TvBoxWarehouse
import kotlinx.coroutines.launch

@Composable
fun TvBoxSubscriptionDialog(
    onDismiss: () -> Unit,
    onShowToast: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val savedUrl by TvBoxRepository.savedSubscriptionUrl.collectAsState()
    val history by TvBoxRepository.subscriptionHistory.collectAsState()
    val currentWarehouses by TvBoxRepository.currentWarehouses.collectAsState()
    val selectedWarehouse by TvBoxRepository.selectedWarehouse.collectAsState()
    val currentSites by TvBoxRepository.currentSites.collectAsState()
    val selectedSite by TvBoxRepository.selectedSite.collectAsState()
    val liveChannels by TvBoxRepository.liveChannels.collectAsState()
    val isLoading by TvBoxRepository.isLoading.collectAsState()

    var inputUrl by remember(savedUrl) { mutableStateOf(savedUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "TVBox 订阅与源配置",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. 订阅输入区
                item {
                    Text(
                        text = "输入 TVBox 订阅地址 (支持单仓、多仓 urls、Base64 与 AES)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        placeholder = { Text("http:// 或 https:// 订阅链接", fontSize = 13.sp) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (inputUrl.isNotBlank()) {
                                    IconButton(onClick = { inputUrl = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "清空", modifier = Modifier.size(16.dp))
                                    }
                                } else {
                                    IconButton(onClick = {
                                        val text = clipboardManager.getText()?.text
                                        if (!text.isNullOrBlank()) {
                                            inputUrl = text.trim()
                                        }
                                    }) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = "粘贴", modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = {
                            if (inputUrl.isBlank()) {
                                onShowToast("请输入有效的订阅链接")
                                return@Button
                            }
                            coroutineScope.launch {
                                val result = TvBoxRepository.loadSubscription(context, inputUrl)
                                if (result.isSuccess) {
                                    val count = result.getOrNull()?.size ?: 0
                                    val isMulti = TvBoxRepository.currentWarehouses.value.isNotEmpty()
                                    val msg = if (isMulti) {
                                        "已识别多仓聚合，加载 ${TvBoxRepository.currentWarehouses.value.size} 条线路，当前线路含 $count 个站点"
                                    } else {
                                        "成功同步单仓配置，共解析 $count 个站源"
                                    }
                                    onShowToast(msg)
                                } else {
                                    onShowToast("订阅解析失败: ${result.exceptionOrNull()?.message}")
                                }
                            }
                        },
                        enabled = !isLoading && inputUrl.isNotBlank(),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在解析配置...")
                        } else {
                            Text("一键拉取并同步")
                        }
                    }
                }

                // 2. 历史订阅记录选择
                if (history.isNotEmpty()) {
                    item {
                        Column {
                            Text(
                                text = "历史使用过的订阅源：",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(history) { histUrl ->
                                    val isCurrent = histUrl == savedUrl
                                    AssistChip(
                                        onClick = {
                                            inputUrl = histUrl
                                            if (histUrl != savedUrl) {
                                                coroutineScope.launch {
                                                    TvBoxRepository.loadSubscription(context, histUrl)
                                                }
                                            }
                                        },
                                        label = {
                                            Text(
                                                text = histUrl.take(24) + "...",
                                                fontSize = 11.sp,
                                                maxLines = 1
                                            )
                                        },
                                        leadingIcon = if (isCurrent) {
                                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                        } else null,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. 多仓子线路选择 (若当前订阅为多仓)
                if (currentWarehouses.isNotEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.AltRoute,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "多仓聚合线路切换 (${currentWarehouses.size} 条)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(currentWarehouses) { warehouse ->
                                        val isSelected = warehouse.url == selectedWarehouse?.url
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                coroutineScope.launch {
                                                    TvBoxRepository.selectWarehouse(context, warehouse)
                                                    onShowToast("已切换至仓源线路: ${warehouse.name}")
                                                }
                                            },
                                            label = {
                                                Text(
                                                    text = warehouse.name,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                )
                                            },
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. 直播频道同步状态指示
                if (liveChannels.isNotEmpty()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LiveTv,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "已自动联动同步 ${liveChannels.size} 个电视直播频道 (可前往直播页观看)",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                item {
                    Divider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                }

                // 5. 当前可用影视站点列表
                item {
                    Text(
                        text = "可用影视站点列表 (${currentSites.size} 个，点击切换)",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (currentSites.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂无订阅站点，请在上方输入链接后点击拉取同步",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    items(currentSites) { site ->
                        val isSelected = site.key == selectedSite?.key
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    coroutineScope.launch {
                                        TvBoxRepository.selectSite(site)
                                        onShowToast("已切换至站点：${site.name}")
                                        onDismiss()
                                    }
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = site.name,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (site.type == 0 || site.type == 1) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        }
                                    ) {
                                        Text(
                                            text = site.typeLabel,
                                            fontSize = 10.sp,
                                            color = if (site.type == 0 || site.type == 1) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onTertiaryContainer
                                            },
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = site.api,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    TvBoxRepository.clearSubscription(context)
                                    inputUrl = ""
                                    onShowToast("已清空所有已保存的订阅源数据")
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "清空所有已存订阅源与缓存",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = MaterialTheme.colorScheme.primary)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}
