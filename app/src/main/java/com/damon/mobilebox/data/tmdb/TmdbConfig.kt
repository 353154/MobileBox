package com.damon.mobilebox.data.tmdb

/**
 * TMDB 访问凭据（个人自用版）。
 *
 * 这里直接编了项目自己的 v4 Read Access Token，因此**应用内不再暴露任何来源选择**：
 * 打开详情页就刮削，没有开关、没有输入框、没有 DataStore。
 *
 * 两个必须知道的约束：
 * - 该 token 是 JWT，只读（api_read）权限，泄漏的后果是别人白嫖额度，不涉及账号；
 * - 一旦要公开分发，必须先把这里的字面量清空，改成让服务器签名下发，否则等于把额度送人。
 */
internal object TmdbConfig {

    /** 内置 TMDB v4 Read Access Token（JWT，`eyJ` 开头 → 走 Authorization: Bearer）。 */
    const val ACCESS_TOKEN =
        "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiI5NWVhZDdlYTc3YjkzNTc0NzQxZDhjYjllY2FiMDc2OCIsIm5iZiI6MTc5NDA1MTMyMy4xNDQsInN1YiI6IjZhZWYwY2ZiNzRkNmU1NWQ1ZTMyMGMwZiIsInNjb3BlcyI6WyJhcGlfcmVhZCJdLCJ2ZXJzaW9uIjoxfQ.1tYKIOESB8LhumoGQmVu-phXsiIaGzni9vP2UPHNGuI"

    /** 凭据是否可用（留空即整体关闭刮削，方便临时停用）。 */
    val isConfigured: Boolean get() = ACCESS_TOKEN.isNotBlank()

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
