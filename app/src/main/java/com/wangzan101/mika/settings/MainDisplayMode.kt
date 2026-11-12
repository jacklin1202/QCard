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

    private var mainTasks = 0

    /** 主屏任务开工登记:回传助手该有的「可服务主屏」状态。平行任务里只要还有主屏任务在跑就是 true,不会被后开的一般任务关掉。 */
    @Synchronized fun mainTaskStarted(): Boolean { mainTasks++; servedWanted = true; return true }

    /** 主屏任务收工注销:回传助手该有的状态;最后一个主屏任务收工才回到 false。 */
    @Synchronized fun mainTaskEnded(): Boolean { if (mainTasks > 0) mainTasks--; val want = mainTasks > 0; servedWanted = want; return want }

    fun mainTaskStartedAndSync(sync: (Boolean) -> Unit) { synchronized(pushLock) { sync(mainTaskStarted()) } }
    fun mainTaskEndedAndSync(sync: (Boolean) -> Unit) { synchronized(pushLock) { sync(mainTaskEnded()) } }

    fun set(on: Boolean) {
        if (on == _enabled.value) return
        prefs.edit().putBoolean(KEY, on).apply()
        _enabled.value = on
    }

    companion object {
        private const val PREFS = "main_display_mode"
        private const val KEY = "enabled"

        /** 产品里的那份(由 [com.wangzan101.mika.cap.phone.PhoneServices] 建)读写的偏好文件。 */
        /** 此刻助手该不该把主屏当作可服务的屏(有主屏任务在跑);助手交接时读它同步。进程重启回 false。 */
        @Volatile var servedWanted: Boolean = false

        private val pushLock = Any()
        fun syncServedWanted(sync: (Boolean) -> Unit) { synchronized(pushLock) { sync(servedWanted) } }

        /** 当前开关值(没有实例在手的场合用,如助手交接时同步给助手)。 */
        fun readEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY, false)

        fun prefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}

/**
 * 这一轮任务要不要提供内置浏览器工具:系统没装 WebView 时不提供;
 * 主屏模式(雷电适配)开着时也不提供——任务只用主屏上的真 App,不走后台网页;
 * 但条件检查(定时/渠道触发的背景判定,只读)除外——调用方传入的开关值已扣掉主屏模式,
 * 照原设计用内置浏览器,不然带网页条件的触发在主屏模式下会没工具可用。
 */
fun webToolsAvailable(webInstalled: Boolean, mainDisplayEnabled: Boolean): Boolean =
    webInstalled && !mainDisplayEnabled
