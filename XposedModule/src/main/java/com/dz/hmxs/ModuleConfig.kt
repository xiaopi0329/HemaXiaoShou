package com.dz.hmxs

import android.content.SharedPreferences

/**
 * 模块配置。
 *
 * 设置页已移除，模块没有 UI、没有可写配置入口。
 * [bindRemotePreferences] 读取的是 LSPosed 暴露的远程偏好 `hippo_xposed_config`；
 * 在没有写入方的情况下，[readBoolean] 恒返回各开关的默认值 `true`，即全部功能默认开启。
 *
 * 保留这层间接的目的：
 *  1. 若将来接入别的配置渠道（如 LSPosed 模块设置页 / 外部文件），只需补写入方，hook 侧零改动；
 *  2. 万一该偏好被第三方按 key 写入，仍能生效。
 *
 * 注意：LSPosed 给出的远程偏好是**只读**的（写入抛 UnsupportedOperationException），
 * 因此本类只提供读取，不提供写入。
 */
object ModuleConfig {
    internal const val PREFS_NAME = "hippo_xposed_config"

    private const val KEY_BLOCK_ADS = "block_ads"
    private const val KEY_BLOCK_READER_ADS = "block_reader_ads"
    private const val KEY_BLOCK_VIDEO_UNLOCK_ADS = "block_video_unlock_ads"
    private const val KEY_BLOCK_TEEN_MODE_DIALOG = "block_teen_mode_dialog"

    private var prefs: SharedPreferences? = null

    fun bindRemotePreferences(remote: SharedPreferences) {
        prefs = remote
    }

    private fun readBoolean(key: String, default: Boolean): Boolean =
        prefs?.getBoolean(key, default) ?: default

    /** 总开关：开屏 / 信息流 / 视频前贴片广告 */
    val blockAds: Boolean
        get() = readBoolean(KEY_BLOCK_ADS, true)

    /** 阅读页广告 */
    val blockReaderAds: Boolean
        get() = readBoolean(KEY_BLOCK_READER_ADS, true)

    /** 剧集解锁激励广告与插屏 */
    val blockVideoUnlockAds: Boolean
        get() = readBoolean(KEY_BLOCK_VIDEO_UNLOCK_ADS, true)

    /** 青少年模式弹窗 */
    val blockTeenModeDialog: Boolean
        get() = readBoolean(KEY_BLOCK_TEEN_MODE_DIALOG, true)
}
