package com.repovoyage.sign.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File

/** Output order is preserved: a flushed clip is delivered before its sentence boundary. */
sealed interface ClipOutput {
    data class Video(val segment: ClipSegment) : ClipOutput
    data object Dropped : ClipOutput
    data object SentenceBoundary : ClipOutput
}

/**
 * 固定窗口切片器（P6 联调，2026-09-23 用户定义：固定时长窗口切分）：
 * 消费取流路径分流的编码帧（[com.repovoyage.sign.camera.SdkCameraSession]
 * encodedFrameTap，prep 之后、解码队列之前），**不转码**直接 MediaMuxer
 * 封装为 MP4 段：段起点必须是随机访问帧（IDR），窗口期满即封段并通过
 * [onKeyFrameNeeded] 请求下一个关键帧——GOP 实测超长，不能干等（边界到
 * IDR 到达之间的帧丢弃，MP4 段必须从随机访问点起才可解码）。
 *
 * 过载/换代处理：入口 Channel 溢出（丢 P 帧会破坏参考链）或流代次切换 →
 * 当前段标记作废并丢弃，等待新随机访问点重开。release() 丢弃进行中的段
 * （未满窗口的残段不产出，避免半词进入识别）。
 */
class ClipSegmenter(
    private val outputDir: File,
    private val width: Int,
    private val height: Int,
    private val windowUs: Long,
    private val onKeyFrameNeeded: () -> Unit,
) {

    private sealed interface Input {
        data class Frame(val value: EncodedFrame) : Input
        data object FlushSentence : Input
    }

    private val frames = Channel<Input>(CHANNEL_CAPACITY)
    private val _segments = Channel<ClipOutput>(Channel.UNLIMITED)

    /** 完成的 MP4 段（消费方负责上传后删除文件） */
    val segments: Flow<ClipOutput> = _segments.receiveAsFlow()

    @Volatile
    private var overflowed = false

    /** 取流协程调用（非阻塞）；溢出 = 当前段已不可用，请求关键帧重同步 */
    fun offer(frame: EncodedFrame) {
        if (frames.trySend(Input.Frame(frame)).isFailure) {
            overflowed = true
            onKeyFrameNeeded()
        }
    }

    /** Cut at the user's sentence boundary after all frames already queued for the muxer. */
    fun flushSentence(): Boolean = frames.trySend(Input.FlushSentence).isSuccess

    /** 切片消费循环（调用方在自有 scope 启动）；随 frames Channel 关闭而退出 */
    suspend fun run() {
        var muxer: MediaMuxer? = null
        var track = 0
        var file: File? = null
        var startPts = 0L
        var lastPts = 0L
        var generation = -1L
        var corrupted = false
        var framesWritten = 0
        var index = 0

        fun closeSegment(emit: Boolean, reportDrop: Boolean = true) {
            val m = muxer ?: return
            // stop() 成功才写全 moov：失败的段文件不可解码，一律丢弃
            val stopped = framesWritten > 0 && runCatching { m.stop() }.isSuccess
            runCatching { m.release() }
            val f = file
            if (emit && !corrupted && stopped && f != null) {
                _segments.trySend(ClipOutput.Video(ClipSegment(f, startPts, lastPts)))
            } else {
                f?.delete()
                if (reportDrop) _segments.trySend(ClipOutput.Dropped)
            }
            muxer = null
            file = null
            framesWritten = 0
        }

        try {
            for (input in frames) {
                if (overflowed) {
                    corrupted = true
                    overflowed = false
                }
                if (input is Input.FlushSentence) {
                    // A trailing fragment shorter than the CV minimum is not a word.
                    closeSegment(emit = !corrupted && framesWritten >= MIN_CV_FRAMES)
                    corrupted = false
                    onKeyFrameNeeded()
                    _segments.trySend(ClipOutput.SentenceBoundary)
                    continue
                }
                val frame = (input as Input.Frame).value
                val generationChanged = muxer != null && frame.streamGeneration != generation
                if (muxer != null && (generationChanged || frame.ptsUs - startPts >= windowUs || corrupted)) {
                    // 换代残段/坏段丢弃：只有完整窗口的干净段才产出
                    closeSegment(emit = !corrupted && !generationChanged)
                    corrupted = false
                    onKeyFrameNeeded()
                }
                if (muxer == null) {
                    if (!frame.isSyncPoint) continue
                    val csd = extractParameterSets(frame.data) ?: run {
                        onKeyFrameNeeded()
                        continue
                    }
                    val next = File(outputDir, "seg-${frame.streamGeneration}-${++index}.mp4")
                    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                        setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd.first))
                        setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(csd.second))
                    }
                    val m = runCatching {
                        MediaMuxer(next.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                            track = it.addTrack(format)
                            it.start()
                        }
                    }.getOrElse {
                        next.delete()
                        onKeyFrameNeeded()
                        continue
                    }
                    muxer = m
                    file = next
                    startPts = frame.ptsUs
                    lastPts = frame.ptsUs
                    generation = frame.streamGeneration
                    corrupted = false
                }
                // 裸 NAL（无前缀）：本机 MIUI MPEG4Writer 会给样本自行加 4 字节
                // 长度前缀（实测 range_length→bytesWritten 恒 +4；喂 AVCC 会双重
                // 前缀 → 解码器整段报废，服务器解出 0 帧）。null = 多 slice AU
                // 等不支持形态 → 整段作废，不产出半坏文件
                val sample = annexBToRawNal(frame.data) ?: run {
                    corrupted = true
                    continue
                }
                val info = MediaCodec.BufferInfo().apply {
                    presentationTimeUs = frame.ptsUs - startPts
                    size = sample.size
                    flags = if (frame.isSyncPoint) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                }
                // 只有真正写入成功才计数：写失败的段是空壳（track 无样本，
                // stop 出的 MP4 服务端解出 0 帧），必须按坏段丢弃
                val written = runCatching {
                    muxer!!.writeSampleData(track, java.nio.ByteBuffer.wrap(sample), info)
                }.isSuccess
                if (written) {
                    framesWritten++
                    lastPts = frame.ptsUs
                } else {
                    corrupted = true
                }
            }
        } finally {
            closeSegment(emit = false, reportDrop = false)
        }
    }

    fun release() {
        frames.close()
    }

    private companion object {
        const val CHANNEL_CAPACITY = 64
        const val MIN_CV_FRAMES = 12
    }
}

/**
 * Annex-B → 裸 VCL NAL（无任何前缀；writeSampleData 的样本格式，见调用处
 * MIUI writer 行为注记）：仅保留 VCL NAL（type 1/5），SPS/PPS/SEI 走 format
 * CSD 不进样本；4 字节起始码的多余前导 0 归上一 NAL 尾部，按
 * [H264DecodePrep] 同款规则裁掉。
 *
 * **要求 AU 恰好一个 VCL NAL**（GO 3S 流实测单 slice）：0 个或 ≥2 个返回
 * null——多 slice 裸拼接在长度前缀语义下不可解析，调用方整段作废。
 */
internal fun annexBToRawNal(data: ByteArray): ByteArray? {
    var found: ByteArray? = null
    var vclCount = 0
    forEachAnnexBNal(data) { start, end ->
        val type = data[start].toInt() and 0x1F
        if (type == NAL_TYPE_SLICE || type == NAL_TYPE_IDR) {
            vclCount++
            if (vclCount == 1) {
                var e = end
                while (e > start && data[e - 1] == 0.toByte()) e--
                found = data.copyOfRange(start, e)
            }
        }
    }
    return if (vclCount == 1) found else null
}

/** 从帧数据提取 SPS/PPS（各含 4 字节起始码，MediaFormat csd-0/csd-1）；不齐返回 null */
internal fun extractParameterSets(data: ByteArray): Pair<ByteArray, ByteArray>? {
    var sps: ByteArray? = null
    var pps: ByteArray? = null
    forEachAnnexBNal(data) { start, end ->
        val type = data[start].toInt() and 0x1F
        if (type != NAL_TYPE_SPS && type != NAL_TYPE_PPS) return@forEachAnnexBNal
        var e = end
        while (e > start && data[e - 1] == 0.toByte()) e--
        val nal = ByteArray(4 + e - start)
        nal[3] = 1
        System.arraycopy(data, start, nal, 4, e - start)
        if (type == NAL_TYPE_SPS) sps = nal else pps = nal
    }
    val s = sps ?: return null
    val p = pps ?: return null
    return s to p
}

/** Annex-B 扫描：以 00 00 01（或 00 00 00 01）分界，对每个 NAL 调 block(payloadStart, end) */
private inline fun forEachAnnexBNal(data: ByteArray, block: (start: Int, end: Int) -> Unit) {
    var i = 0
    var start = -1
    while (i <= data.size - 3) {
        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
            if (start >= 0) block(start, i)
            start = i + 3
            i += 3
        } else {
            i++
        }
    }
    if (start >= 0 && start < data.size) block(start, data.size)
}

private const val NAL_TYPE_SLICE = 1
private const val NAL_TYPE_IDR = 5
private const val NAL_TYPE_SPS = 7
private const val NAL_TYPE_PPS = 8
