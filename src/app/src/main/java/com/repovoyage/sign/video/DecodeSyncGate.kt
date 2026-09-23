package com.repovoyage.sign.video

/**
 * 解码投喂门控（ARCHITECTURE.md §2.2.2 第 5 行）：不直接投喂任意中间帧——
 * 每个代次从第一个 isSyncPoint=true（参数集齐全的 IDR）起开始投喂，之前的帧直接丢弃。
 * 代次切换视为新流重新门控；解码链冲刷/过载重同步后须调 reset() 重新门控
 * （配套动作是请求关键帧，由接线层负责）。
 */
class DecodeSyncGate {

    private var feeding = false
    private var generation: Long? = null

    /** 该帧是否应投喂解码器；迟到的旧代次帧直接忽略（不重置当前代次状态） */
    fun shouldFeed(frame: EncodedFrame): Boolean {
        val gen = generation
        if (gen != null && frame.streamGeneration < gen) return false
        if (frame.streamGeneration != generation) {
            generation = frame.streamGeneration
            feeding = false
        }
        if (frame.isSyncPoint) feeding = true
        return feeding
    }

    /** 解码链重同步：回到等待随机访问点状态 */
    fun reset() {
        feeding = false
    }
}
