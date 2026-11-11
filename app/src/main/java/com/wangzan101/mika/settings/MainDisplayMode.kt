package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主屏模式开关(ldplayer-adapt 分支):打开后任务直接在主屏(display 0)上做,不建虚拟屏。
 * 给雷电这类只有一个窗口的模拟器用:虚拟屏在模拟器里画面餵食不稳,主屏是它的一级渲染管线;
 * 代价是任务期间主屏被任务占用,有人在主屏操作时任务自动暂停。默认关,保持上游行为。
 */
class MainDisplayMode(private val prefs: SharedPreferences) {
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun set(on: Boolean) {
        if (on == _enabled.value) return
        prefs.edit().putBoolean(KEY, on).apply()
        _enabled.value = on
    }

    companion object {
        private const val PREFS = "main_display_mode"
        private const val KEY = "enabled"

        /** 产品里的那份(由 [com.wangzan101.mika.cap.phone.PhoneServices] 建)读写的偏好文件。 */
        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
