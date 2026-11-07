package com.damon.mobilebox.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.damon.mobilebox.data.watch.SyncResult
import com.damon.mobilebox.data.watch.WebDavConfig
import com.damon.mobilebox.data.watch.WebDavConfigState
import com.damon.mobilebox.data.watch.WebDavSync
import kotlinx.coroutines.launch

/**
 * WebDAV 云端同步。
 *
 * 两种形态：
 * - 已配置：轻量摘要卡（地址 / 账号 / 备份路径）+ 同步操作 + 「删除配置 / 新建配置」，
 *   不再把四个输入框摊开。
 * - 未配置、点「新建配置」或删除后：展开配置表单。
 *
 * 用自定义 Dialog 而不是 AlertDialog：表单态在键盘弹起时会被 AlertDialog 的
 * text 槽裁掉（订阅弹框踩过同款坑）。这里固定最大高度、内容可滚。
 */
@Composable
fun WebDavSyncDialog(onDismiss: () -> Unit) {
    val config by WebDavConfig.stateFlow.collectAsState()

    // 已配置时默认只看摘要；只有明确「新建配置」才展开表单。
    var editing by remember { mutableStateOf(!config.isConfigured) }
    var url by remember(config.baseUrl) { mutableStateOf(config.baseUrl) }
    var user by remember(config.username) { mutableStateOf(config.username) }
    var password by remember(config.password) { mutableStateOf(config.password) }
    var dir by remember(config.remoteDir) { mutableStateOf(config.remoteDir) }

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var statusOk by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun backupPath(base: String, remoteDir: String): String {
        val b = base.trim().trimEnd('/')
        val d = remoteDir.trim().trim('/').replace(Regex("\\s+"), "-")
        return if (d.isEmpty()) "$b/backup.json" else "$b/$d/backup.json"
    }

    fun currentInput() = WebDavConfigState(
        baseUrl = url.trim(),
        username = user.trim(),
        password = password,
        remoteDir = dir.trim().ifBlank { "MobileBox" }
    )

    // 表单态同步前自动保存当前输入，避免「填了但没点保存」导致连到旧地址。
    fun runSync(action: suspend () -> SyncResult) {
        if (busy) return
        if (editing) {
            val state = currentInput()
            if (!state.isConfigured) {
                status = "请先填写服务地址、账号和应用密码"
                statusOk = false
                return
            }
            WebDavConfig.save(state)
        } else if (!config.isConfigured) {
            status = "请先新建配置"
            statusOk = false
            return
        }
        scope.launch {
            busy = true
            status = "正在连接云端…"
            statusOk = true
            val result = try {
                action()
            } catch (e: Exception) {
                SyncResult(false, "同步异常：${e.message?.take(120) ?: "未知错误"}")
            }
            status = result.message
            statusOk = result.success
            busy = false
        }
    }

    fun saveConfig() {
        val state = currentInput()
        if (!state.isConfigured) {
            status = "请先填写服务地址、账号和应用密码"
            statusOk = false
            return
        }
        WebDavConfig.save(state)
        editing = false
        confirmDelete = false
        status = "配置已保存在本机"
        statusOk = true
    }

    fun startNewConfig() {
        url = ""
        user = ""
        password = ""
        dir = "MobileBox"
        status = ""
        confirmDelete = false
        editing = true
    }

    fun cancelEdit() {
        url = config.baseUrl
        user = config.username
        password = config.password
        dir = config.remoteDir
        status = ""
        editing = false
    }

    fun deleteConfig() {
        // 只清本机保存的连接信息，云端 backup.json 不动。
        WebDavConfig.clear()
        url = ""
        user = ""
        password = ""
        dir = "MobileBox"
        confirmDelete = false
        editing = true
        status = "已删除本机配置，云端备份文件保留；可重新填写新建配置"
        statusOk = true
    }

    StandardDialog(onDismissRequest = { if (!busy) onDismiss() }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "WebDAV 云端同步",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (!editing && config.isConfigured) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                "已配置",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                if (editing) {
                    Text(
                        "把观影历史与追剧收藏备份成 backup.json。多数服务需使用「应用密码」而非登录密码。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("服务地址") },
                        placeholder = { Text("https://example.com/dav/", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("账号") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("应用密码") },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = dir,
                        onValueChange = { dir = it },
                        label = { Text("备份目录") },
                        placeholder = { Text("MobileBox", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "备份文件：${backupPath(url, dir)}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = { saveConfig() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("保存配置")
                    }
                } else {
                    // 已配置：只展示摘要，不再摊开四个输入框。
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ConfigSummaryRow("服务地址", config.baseUrl.trim().trimEnd('/'))
                            ConfigSummaryRow("账号", config.username)
                            ConfigSummaryRow("备份目录", config.remoteDir)
                            Text(
                                "备份文件：${backupPath(config.baseUrl, config.remoteDir)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Text(
                    "同步操作",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { runSync { WebDavSync.upload() } },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("上传备份", fontSize = 12.sp) }
                    Button(
                        onClick = { runSync { WebDavSync.download() } },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("下载恢复", fontSize = 12.sp) }
                    Button(
                        onClick = { runSync { WebDavSync.sync() } },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("双向同步", fontSize = 12.sp) }
                }
                Text(
                    "上传＝本机覆盖云端；下载＝拉云端与本机合并；双向＝合并后两端一致。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (status.isNotEmpty()) {
                    Text(
                        status,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (statusOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }

                // 删除配置二次确认：只清本机，不删云端文件。
                if (!editing && confirmDelete) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "确认删除本机配置？云端 backup.json 不会被删除。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Row(modifier = Modifier.align(Alignment.End)) {
                                TextButton(onClick = { confirmDelete = false }, enabled = !busy) {
                                    Text("取消", fontSize = 12.sp)
                                }
                                TextButton(onClick = { deleteConfig() }, enabled = !busy) {
                                    Text("确认删除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (editing) {
                        if (config.isConfigured) {
                            TextButton(onClick = { cancelEdit() }, enabled = !busy) {
                                Text("取消", fontSize = 12.sp)
                            }
                        }
                    } else {
                        TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                            Text("删除配置", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { startNewConfig() }, enabled = !busy) {
                            Text("新建配置", fontSize = 12.sp)
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !busy) {
                        Text("关闭", fontSize = 12.sp)
                    }
                }
            }
}

@Composable
private fun ConfigSummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            value.ifBlank { "—" },
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}
