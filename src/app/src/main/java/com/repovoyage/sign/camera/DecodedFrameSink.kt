package com.repovoyage.sign.camera

import com.repovoyage.sign.video.DecodedFrame

/**
 * API.md §2.2 输出端契约（flavor 注入，运行时无开关）：共用解码链只面向此接口。
 * production: ModelInputAdapter（采样+预处理 → SignRecognizer，P6）
 * training:   UsbCaptureAdapter（像素转换 → USB 发送池，P4）
 *
 * 回调在解码线程执行，实现方须自行保证线程安全且不长时间阻塞
 * （I420 转换允许在回调线程做，socket 写出不得）。
 */
interface DecodedFrameSink {
    fun onFrame(frame: DecodedFrame)

    /** 缺帧/解码重建/重连/过载：连续性失效，消费方不得将前后帧拼连续样本 */
    fun onGap(event: FrameGapEvent)

    /** 上游过载（编码入口/解码端）：连续性失效信号，同 onGap 语义 */
    fun onOverload(location: OverloadLocation)
}

/** 连续性失效原因，与线协议 GAP_EVENT reason 对齐（API.md §9.3） */
enum class GapReason { LOST_FRAMES, DECODE_RESET, RECONNECT, OVERLOAD }

/** 连续性失效事件；[streamGeneration] 为失效检测时刻的流代次 */
data class FrameGapEvent(
    val reason: GapReason,
    val streamGeneration: Long,
)
