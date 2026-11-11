package com.wangzan101.mika.cap.phone.display

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * 一块任务用屏的把手:Agent 虚拟屏([AgentDisplay])和主屏([MainDisplay])共用这一面。
 * 读屏、认字、画面新鲜度只认这一面,不问画面从哪来、在哪块屏上。
 */
interface DisplayHandle {
    val displayId: Int
    /** 应用逻辑坐标下的宽(与这块屏的旋转一致)。 */
    val width: Int
    val height: Int

    /** 实时画面:小图预览,还没有画面时为 null。 */
    val frames: StateFlow<Bitmap?>

    /** 最新一帧的整幅 PNG;还没收到画面时返回 null。 */
    fun latestFramePng(): ByteArray?

    /** 最新一帧的原始尺寸副本(OCR 用),调用方负责 recycle;还没收到画面时返回 null。 */
    fun latestFrameBitmap(): Bitmap?

    /** 最新一帧的时刻,与启动时刻同一时钟域(System.nanoTime);还没收到画面时返回 null。 */
    fun lastFrameAt(): Long?

    /** 放掉这块屏(主屏:只停取帧,不动系统里的屏本身)。 */
    fun release()
}
