package com.repovoyage.sign.video

import android.graphics.Rect
import java.nio.ByteBuffer

/** API.md §2.1 — 解码输出；应用拥有副本，codec 缓冲已释放 */
data class DecodedFrame(
    val planes: List<FramePlane>,
    val width: Int,
    val height: Int,
    val crop: Rect,
    val pixelFormat: PixelLayout,        // 实测布局（如 I420 半平面/紧凑），不假定 NV12
    val ptsUs: Long,
    val streamGeneration: Long,
)

/** 单平面的字节与步长；半平面/紧凑与否由消费方按 rowStride/pixelStride 实测判定 */
data class FramePlane(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int,
)

/** 解码输出实际像素布局；取自 Image.getFormat() 实测，其余格式按不兼容处理 */
enum class PixelLayout {
    YUV_420_888,
}
