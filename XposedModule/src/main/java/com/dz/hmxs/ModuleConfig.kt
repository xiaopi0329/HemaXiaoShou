package com.dz.hmxs

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

object ModuleConfig {
    internal const val PREFS_NAME = "hippo_xposed_config"
    private const val KEY_BLOCK_ADS = "block_ads"
    private const val KEY_BLOCK_READER_ADS = "block_reader_ads"
    private const val KEY_BLOCK_VIDEO_UNLOCK_ADS = "block_video_unlock_ads"

    private var prefs: SharedPreferences? = null
    private var defaults = mapOf(
        KEY_BLOCK_ADS to true,
        KEY_BLOCK_READER_ADS to true,
        KEY_BLOCK_VIDEO_UNLOCK_ADS to true
    )

    fun init(context: Context) {
        val local = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        defaults = mapOf(
            KEY_BLOCK_ADS to local.getBoolean(KEY_BLOCK_ADS, true),
            KEY_BLOCK_READER_ADS to local.getBoolean(KEY_BLOCK_READER_ADS, true),
            KEY_BLOCK_VIDEO_UNLOCK_ADS to local.getBoolean(KEY_BLOCK_VIDEO_UNLOCK_ADS, true)
        )
    }

    fun bindRemotePreferences(remote: SharedPreferences) {
        prefs = remote
        defaults = mapOf(
            KEY_BLOCK_ADS to remote.getBoolean(KEY_BLOCK_ADS, true),
            KEY_BLOCK_READER_ADS to remote.getBoolean(KEY_BLOCK_READER_ADS, true),
            KEY_BLOCK_VIDEO_UNLOCK_ADS to remote.getBoolean(KEY_BLOCK_VIDEO_UNLOCK_ADS, true)
        )
    }

    private fun readBoolean(key: String): Boolean {
        val fallback = defaults[key] ?: true
        return prefs?.getBoolean(key, fallback) ?: fallback
    }

    /**
     * LSPosed 通过 getRemotePreferences 给出的偏好是**只读**的
     * （写会抛 UnsupportedOperationException: Read only implementation），
     * 所以这里必须吞掉异常：否则一旦有人从注入进程写配置，会直接把 onPackageReady 打断。
     */
    private fun writeBoolean(key: String, value: Boolean) {
        try {
            prefs?.edit()?.putBoolean(key, value)?.apply()
        } catch (ignored: Throwable) {
            // 只读实现，忽略
        }
    }

    var blockAds: Boolean
        get() = readBoolean(KEY_BLOCK_ADS)
        set(value) = writeBoolean(KEY_BLOCK_ADS, value)

    var blockReaderAds: Boolean
        get() = readBoolean(KEY_BLOCK_READER_ADS)
        set(value) = writeBoolean(KEY_BLOCK_READER_ADS, value)

    var blockVideoUnlockAds: Boolean
        get() = readBoolean(KEY_BLOCK_VIDEO_UNLOCK_ADS)
        set(value) = writeBoolean(KEY_BLOCK_VIDEO_UNLOCK_ADS, value)
}
