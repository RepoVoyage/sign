package com.repovoyage.sign.history

data class CachedSentenceRecord(
    val key: String,                      // (sessionId, segmentId) 的持久化主键
    val processedAtWallMs: Long,
)

/**
 * 缓存保留策略（API.md §8 初始值）：最近 90 天且 ≤10000 条，任一超限清理。
 */
class SentenceCacheRetention(
    private val maxAgeDays: Long = 90,
    private val maxCount: Int = 10_000,
) {

    /**
     * 返回应删除的记录：超龄（严格大于 90 天）全部删除；幸存者超量时再按
     * processedAtWallMs 从最老删起直至回到限内（删龄后通常已回到限内，不再按量删）。
     */
    fun evict(records: List<CachedSentenceRecord>, nowWallMs: Long): List<CachedSentenceRecord> {
        val maxAgeMs = maxAgeDays * DAY_MS
        val aged = records.filter { nowWallMs - it.processedAtWallMs > maxAgeMs }
        val survivors = records.filterNot { it in aged }
        val overage = survivors.size - maxCount
        if (overage <= 0) return aged
        return aged + survivors.sortedBy { it.processedAtWallMs }.take(overage)
    }

    private companion object {
        const val DAY_MS = 24 * 3600 * 1000L
    }
}
