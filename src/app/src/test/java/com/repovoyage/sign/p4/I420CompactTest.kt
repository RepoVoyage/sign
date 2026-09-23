package com.repovoyage.sign.p4

import com.repovoyage.sign.usb.I420
import com.repovoyage.sign.video.FramePlane
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer

/**
 * P4 像素转换验收（API.md §2.2 training 输出端 / §9.3 FRAME）：
 * DecodedFrame 的 YUV_420_888 平面（实测布局：rowStride 对齐 padding、
 * 半平面交错 UV、pixelStride=2）→ 紧凑 I420（Y+U+V 逐平面紧凑，无 stride），
 * 按 crop 裁剪，不假定 NV12。
 */
class I420CompactTest {

    private fun plane(bytes: ByteArray, rowStride: Int, pixelStride: Int) =
        FramePlane(ByteBuffer.wrap(bytes), rowStride, pixelStride)

    /** 4×4 测试图：Y 1..16，U 17..20，V 21..24（行主序） */
    private val y = ByteArray(16) { (it + 1).toByte() }
    private val u = ByteArray(4) { (17 + it).toByte() }
    private val v = ByteArray(4) { (21 + it).toByte() }

    @Test
    fun `紧凑平面布局直接拷贝`() {
        val out = I420.compact(
            listOf(plane(y, 4, 1), plane(u, 2, 1), plane(v, 2, 1)),
            cropLeft = 0, cropTop = 0, cropRight = 4, cropBottom = 4,
        )
        assertArrayEquals(y + u + v, out)
    }

    @Test
    fun `rowStride 对齐 padding 被跳过`() {
        // Y：4 列 + 2 padding/行（99 填充）；U/V：2 列 + 2 padding/行
        val yPad = ByteArray(24).also {
            for (row in 0 until 4) for (col in 0 until 4) it[row * 6 + col] = y[row * 4 + col]
        }
        val uPad = ByteArray(8).also {
            it[0] = u[0]; it[1] = u[1]; it[4] = u[2]; it[5] = u[3]
        }
        val vPad = ByteArray(8).also {
            it[0] = v[0]; it[1] = v[1]; it[4] = v[2]; it[5] = v[3]
        }
        val out = I420.compact(
            listOf(plane(yPad, 6, 1), plane(uPad, 4, 1), plane(vPad, 4, 1)),
            cropLeft = 0, cropTop = 0, cropRight = 4, cropBottom = 4,
        )
        assertArrayEquals(y + u + v, out)
    }

    @Test
    fun `半平面交错 UV 正确分离`() {
        // 交错 UV：U 在偶 offset、V 在奇 offset（plane2 自 V 起）
        val uv = ByteArray(8).also { arr ->
            for (i in 0 until 4) { arr[2 * i] = u[i]; arr[2 * i + 1] = v[i] }
        }
        val planeU = plane(uv, 4, 2)
        val planeV = plane(uv.copyOfRange(1, 8), 4, 2)
        val out = I420.compact(
            listOf(plane(y, 4, 1), planeU, planeV),
            cropLeft = 0, cropTop = 0, cropRight = 4, cropBottom = 4,
        )
        assertArrayEquals(y + u + v, out)
    }

    @Test
    fun `按 crop 裁剪 Y 与色度`() {
        // crop (2,2)-(4,4)：Y 取右下 2×2，色度取 (1,1) 起的 1×1 列
        val out = I420.compact(
            listOf(plane(y, 4, 1), plane(u, 2, 1), plane(v, 2, 1)),
            cropLeft = 2, cropTop = 2, cropRight = 4, cropBottom = 4,
        )
        val expectY = byteArrayOf(11, 12, 15, 16)      // 行主序 4×4 的右下角
        val expectU = byteArrayOf(20)                  // 色度 2×2 右下 1×1
        val expectV = byteArrayOf(24)
        assertArrayEquals(expectY + expectU + expectV, out)
    }
}
