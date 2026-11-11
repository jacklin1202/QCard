package com.wangzan101.mika.cap.phone.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.Display
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * 把主屏(display 0)当作任务用屏(ldplayer-adapt 分支的主屏模式):不建虚拟屏,
 * 画面经无障碍截主屏得到,App 就开在用户看得见的主屏上。给雷电这类只有一个窗口、
 * 虚拟屏画画餵食不稳的模拟器用。
 *
 * 取帧节流:无障碍截图有系统级最小间隔(截太密会被拒),轮询间隔留到 [CAPTURE_INTERVAL_MS];
 * 截失败时沿用上一帧、不推进帧时刻(新鲜度保护照旧会拦下旧帧,等下一张成功再放行)。
 * 防截屏窗口(Secure)照虚拟屏的老样子交一张黑帧:读屏那边认黑帧为「画面不可用」,不会把旧画面当新的。
 * 截到的画面已是逻辑方向(无障碍截图按当前显示方向出图),不再做虚拟屏那套旋转还原。
 */
class MainDisplay(
    private val a11y: ScreenAccessibility,
    initialWidth: Int,
    initialHeight: Int,
    private val suspended: () -> Boolean = { false },
) : DisplayHandle {
    override val displayId: Int = Display.DEFAULT_DISPLAY

    // 主屏会旋转(横竖切换):宽高不冻结,以实际截到的帧为准、每帧在 publish 里校准;
    // 读屏那边(AndroidScreen)是现读宽高的,校准完就自动跟上,坐标不会错位。
    @Volatile private var curWidth = initialWidth
    @Volatile private var curHeight = initialHeight
    override val width: Int get() = curWidth
    override val height: Int get() = curHeight

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val frameLock = Any()
    private var fullFrame: Bitmap? = null
    @Volatile private var frameAtNanos = 0L
    @Volatile private var released = false

    private val _frames = MutableStateFlow<Bitmap?>(null)
    override val frames: StateFlow<Bitmap?> = _frames.asStateFlow()

    init {
        scope.launch {
            while (!released) {
                if (suspended()) {
                    // 暂停中(接管、被盖住暂停、等回答):不截主屏,手里的帧也一并清掉,
                    // 不留旧画面给恢复后误读;帧时刻归零,恢复后要等一张新帧才放行
                    synchronized(frameLock) {
                        fullFrame?.recycle()
                        fullFrame = null
                        frameAtNanos = 0L
                    }
                    if (_frames.value != null) _frames.value = null
                    delay(CAPTURE_INTERVAL_MS)
                    continue
                }
                captureOnce()
                delay(CAPTURE_INTERVAL_MS)
            }
        }
    }

    private suspend fun captureOnce() {
        val shot = try {
            a11y.takeMainScreenshot()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // 取消不能吞:release 关 scope 时要能立刻停,不拖收尾
        } catch (t: Throwable) {
            return // 截不到就留旧帧,下一轮再试
        }
        if (released) {
            (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap?.recycle()
            return
        }
        when (shot) {
            is ScreenAccessibility.MainShot.Ok -> publish(shot.bitmap)
            ScreenAccessibility.MainShot.Secure -> publish(blackFrame())
            is ScreenAccessibility.MainShot.Failed -> Unit // 留旧帧,不推进帧时刻
        }
    }

    private fun publish(bmp: Bitmap) {
        synchronized(frameLock) {
            if (released) {
                bmp.recycle()
                return
            }
            if (bmp.width != curWidth || bmp.height != curHeight) {
                curWidth = bmp.width
                curHeight = bmp.height
            }
            val old = fullFrame
            fullFrame = bmp
            frameAtNanos = System.nanoTime()
            _frames.value = previewOf(bmp)
            old?.recycle()
        }
    }

    private fun blackFrame(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /** 预览小图:短边缩到 [PREVIEW_SHORT_SIDE] 以内,和虚拟屏预览同一规格。 */
    private fun previewOf(bmp: Bitmap): Bitmap {
        val shortSide = minOf(bmp.width, bmp.height)
        if (shortSide <= PREVIEW_SHORT_SIDE) return bmp.copy(Bitmap.Config.ARGB_8888, false)
        val scale = PREVIEW_SHORT_SIDE.toFloat() / shortSide
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            scale(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
            drawBitmap(bmp, 0f, 0f, paint)
        }
        return out
    }

    override fun latestFramePng(): ByteArray? {
        val bmp = synchronized(frameLock) { fullFrame?.copy(Bitmap.Config.ARGB_8888, false) } ?: return null
        return try {
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    override fun latestFrameBitmap(): Bitmap? =
        synchronized(frameLock) { fullFrame?.copy(Bitmap.Config.ARGB_8888, false) }

    override fun lastFrameAt(): Long? = frameAtNanos.takeIf { !released && it > 0 }

    override fun release() {
        if (released) return
        released = true
        scope.cancel()
        synchronized(frameLock) {
            fullFrame?.recycle()
            fullFrame = null
        }
        _frames.value = null
    }

    private companion object {
        const val PREVIEW_SHORT_SIDE = 360
        /** 无障碍截图的系统最小间隔在 1 秒量级,轮询留一点余量。 */
        const val CAPTURE_INTERVAL_MS = 1_100L
    }
}
