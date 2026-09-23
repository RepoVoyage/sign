package com.repovoyage.sign.video

import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaCodec.BufferInfo
import java.nio.ByteBuffer

/**
 * MediaCodec 无 Surface 解码（ARCHITECTURE.md §2.2.2 检查表 / §2.2.3 只解码）：
 * configure 不传显示 Surface；输出经 getOutputImage 读平面并复制为应用拥有的副本
 * （releaseOutputBuffer 前完成读取，§2.2.4），不采样的输出立即释放。
 *
 * 本类是硬件编解码薄封装，行为靠真机验证；投喂门控（只从随机访问帧起喂）由接线层
 * 用 [DecodeSyncGate] 承担，本类不做代次判断、只记录最近投喂帧的代次供输出携带。
 * 非法码流/解码器错误以异常抛出，由接线层走清空重同步路径。
 */
class SurfacelessH264Decoder(
    private val onFrame: (DecodedFrame) -> Unit,
) {

    private var codec: MediaCodec? = null
    private var generation: Long = -1

    /** @param width height 声明尺寸（configure 提示值）；实际以 SPS/输出 format 为准 */
    fun start(width: Int, height: Int, csd: Pair<ByteArray, ByteArray>) {
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd.first))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(csd.second))
        c.configure(format, null, null, 0)
        c.start()
        codec = c
    }

    /**
     * 投喂一个编码帧（阻塞等待可用 input buffer 至多 timeoutMs）。
     * @return false = 超时未取得 input buffer（该帧未入队，由接线层决定重同步）
     */
    fun feed(frame: EncodedFrame, timeoutMs: Long = 100): Boolean {
        val c = codec ?: return false
        val index = c.dequeueInputBuffer(timeoutMs * 1000)
        if (index < 0) return false
        val buffer = c.getInputBuffer(index) ?: return false
        buffer.clear()
        if (buffer.remaining() < frame.data.size) {
            // 帧超 buffer 上限：异常码流，走重同步
            c.queueInputBuffer(index, 0, 0, frame.ptsUs, 0)
            throw IllegalStateException("frame ${frame.data.size}B exceeds input buffer")
        }
        buffer.put(frame.data)
        c.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, 0)
        generation = frame.streamGeneration
        return true
    }

    /** 取出已解码输出（复制后立即释放 codec buffer）；无输出时安静返回 */
    fun drain(timeoutUs: Long = 10_000) {
        val c = codec ?: return
        val info = BufferInfo()
        var timeout = timeoutUs
        while (true) {
            when (val index = c.dequeueOutputBuffer(info, timeout)) {
                MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> timeout = 0
                else -> {
                    if (index < 0) return
                    try {
                        val image = c.getOutputImage(index)
                        if (image == null || image.format != android.graphics.ImageFormat.YUV_420_888) {
                            // 读不出 Image 的输出对本项目无用（§2.2.3 不引入显示渲染绕过）
                            throw IllegalStateException("unreadable output format")
                        }
                        onFrame(copyOf(image, info))
                    } finally {
                        c.releaseOutputBuffer(index, false)
                    }
                    timeout = 0
                }
            }
        }
    }

    /** 解码链重同步：冲刷已排队数据（调用方须同步 DecodeSyncGate.reset 与请求关键帧） */
    fun flush() {
        codec?.flush()
    }

    fun stop() {
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
    }

    /** 复制 Image 平面为应用持有的字节（保留 rowStride 布局）；须在 releaseOutputBuffer 前调用 */
    private fun copyOf(image: Image, info: BufferInfo): DecodedFrame {
        val planes = image.planes.map { p ->
            val buffer = p.buffer
            val copy = ByteArray(buffer.remaining())
            buffer.get(copy)
            FramePlane(ByteBuffer.wrap(copy), p.rowStride, p.pixelStride)
        }
        return DecodedFrame(
            planes = planes,
            width = image.width,
            height = image.height,
            crop = Rect(image.cropRect),
            pixelFormat = PixelLayout.YUV_420_888,
            ptsUs = info.presentationTimeUs,
            streamGeneration = generation,
        )
    }
}
