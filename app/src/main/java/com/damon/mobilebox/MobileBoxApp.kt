package com.damon.mobilebox

import android.app.Application
import android.util.Log
import com.damon.mobilebox.data.search.SearchHistoryStore
import com.damon.mobilebox.data.tmdb.TmdbRepository
import com.damon.mobilebox.data.watch.PlaybackSettings
import com.damon.mobilebox.data.watch.WatchStore
import com.damon.mobilebox.data.watch.WebDavConfig
import dagger.hilt.android.HiltAndroidApp

/**
 * MobileBox 全局 Application
 * 作为 Hilt 依赖注入容器的顶层根入口
 */
@HiltAndroidApp
class MobileBoxApp : Application() {
    companion object {
        const val TAG = "MobileBox"
    }

    override fun onCreate() {
        super.onCreate()
        // 追剧收藏 / 播放历史：全应用共享一份 StateFlow，必须在任何页面组合前起好，
        // 否则「我的」页与详情页会各自从空列表起手渲染一帧。
        WatchStore.init(this)
        // 画中画 / 后台播放开关：必须在任何页面组合、任何 onUserLeaveHint 之前起好
        PlaybackSettings.init(this)
        // WebDAV 同步配置：设置页与网络层都要读，同样在页面组合前起好
        WebDavConfig.init(this)
        // 最近搜索：同样要在页面组合前起好，避免搜索页先渲染一帧空历史
        SearchHistoryStore.init(this)
        // TMDB 刮削：本地缓存要在详情页首次请求前就绪（凭据由 local.properties 注入 BuildConfig）
        TmdbRepository.init(this)
        Log.i(TAG, "MobileBoxApp 初始化成功，Hilt 依赖注入容器已就绪")
    }
}
