package com.damon.mobilebox.ui.search

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.damon.mobilebox.data.tvbox.SearchHit
import com.damon.mobilebox.data.tvbox.TvBoxRepository
import com.damon.mobilebox.ui.components.StandardPosterCard
import kotlinx.coroutines.launch

/**
 * 全站搜索。
 *
 * 每次搜索并发请求所有 `searchable` 站点（各取第一页），合并后按片名去重、名称完全匹配优先。
 * 只依赖 `TvBoxParser.fetchMacCmsVodList(keyword=...)`，不引入新的接口形态。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onMovieClick: (SearchHit) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    var keyword by rememberSaveable { mutableStateOf(SearchMemory.keyword) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(SearchMemory.searched) }
    var results by remember { mutableStateOf(SearchMemory.results) }

    fun submit(word: String = keyword) {
        val w = word.trim()
        if (w.isEmpty() || loading) return
        keyboard?.hide()
        scope.launch {
            loading = true
            searched = true
            results = TvBoxRepository.searchAll(w)
            loading = false
            // 记住这次搜索：从详情页返回时列表还在，不用重新查一遍
            SearchMemory.keyword = w
            SearchMemory.searched = true
            SearchMemory.results = results
        }
    }

    // 刻意不自动聚焦输入框。
    // 之前进页面就 requestFocus，会出现「输入框已聚焦、但输入法没弹」的僵持态：
    // 此时用户再点同一个输入框不会产生新的焦点事件，输入法请求也就不会重新发起，
    // 表现为「点了没反应」。交给用户点击来触发，焦点从无到有的那一刻系统一定会拉起输入法。

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier
                    .weight(1f)
                    // 软键盘的「搜索」键、以及外接键盘/电视盒子的回车都直接触发
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            (event.key == Key.Enter || event.key == Key.NumPadEnter)
                        ) {
                            submit()
                            true
                        } else {
                            false
                        }
                    },
                singleLine = true,
                placeholder = { Text("搜索影片、剧集", fontSize = 14.sp) },
                shape = RoundedCornerShape(24.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (keyword.isNotEmpty()) {
                        IconButton(onClick = { keyword = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "清空",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(onClick = { submit() }, enabled = keyword.isNotBlank() && !loading) {
                Text("搜索", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        when {
            loading -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("正在搜索全部站点…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            results.isNotEmpty() -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items = results, key = { it.key }, contentType = { "search_poster_card" }) { hit ->
                    StandardPosterCard(
                        title = hit.detail.title,
                        subtitle = hit.detail.subtitle,
                        score = hit.detail.rating,
                        posterUrl = hit.detail.posterUrl,
                        movieId = hit.detail.id,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        onClick = { onMovieClick(hit) }
                    )
                }
            }

            searched -> EmptyHint("没有找到「$keyword」", "换个片名或只输入两个字试试")

            else -> EmptyHint(
                title = "搜索全部订阅站点",
                subtitle = "输入片名，点击「搜索」后同时查询所有采集源"
            )
        }
    }
}

@Composable
private fun EmptyHint(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(20.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(18.dp)
                    .size(30.dp)
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = subtitle,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 会话内搜索记忆：搜索结果体积大、`VideoDetailData` 也不可序列化，
 * 用 `rememberSaveable` 存不住；而每次从详情页返回都重查一遍要等好几秒。
 * 这里用一个进程内单例兜住「上一次查询 + 结果」，返回时列表原样还在。
 */
private object SearchMemory {
    var keyword: String = ""
    var searched: Boolean = false
    var results: List<SearchHit> = emptyList()
}
