package com.repovoyage.sign.usb

import com.repovoyage.sign.video.FramePlane

/**
 * YUV_420_888 → 紧凑 I420 像素转换（API.md §2.2 training 输出端 / §9.3 FRAME）。
 *
 * 输入为 DecodedFrame 实测平面布局（rowStride 对齐 padding、色度 pixelStride 可为 2
 * 的半平面交错），不假定 NV12；输出按 crop 裁剪成 Y + U + V 三个紧凑平面，
 * 无 stride/padding。色度取样点按 4:2:0 取 crop 坐标的一半（向下取整）。
 */
object I420 {

    fun compact(
        planes: List<FramePlane>,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
    ): ByteArray {
        val w = cropRight - cropLeft
        val h = cropBottom - cropTop
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val out = ByteArray(w * h + 2 * cw * ch)
        copyPlane(planes[0], cropLeft, cropTop, w, h, out, 0)
        copyPlane(planes[1], cropLeft / 2, cropTop / 2, cw, ch, out, w * h)
        copyPlane(planes[2], cropLeft / 2, cropTop / 2, cw, ch, out, w * h + cw * ch)
        return out
    }

    /** 从平面按 rowStride/pixelStride 取 crop 区域逐像素拷入 out（紧凑无 stride） */
    private fun copyPlane(
        p: FramePlane,
        srcCol: Int,
        srcRow: Int,
        cols: Int,
        rows: Int,
        out: ByteArray,
        outOff: Int,
    ) {
        for (row in 0 until rows) {
            var src = p.rowStride * (srcRow + row) + srcCol * p.pixelStride
            var dst = outOff + row * cols
            repeat(cols) {
                out[dst++] = p.buffer.get(src)
                src += p.pixelStride
            }
        }
    }
}
