package com.repovoyage.sign.history

/**
 * 把保留策略（§2.6：最近 90 天且 ≤10000 条）套用到 Room 库：全量读取
 * (sessionId, segmentId, 最近处理墙钟)，复用纯域 [SentenceCacheRetention]
 * 判定后按段删除（语言结果随 FK 级联）。语言结果的 processedAtWallMs 缺失时
 * 回退句子 wallTimeEnd（DAO 查询内 COALESCE）。
 *
 * [CachedSentenceRecord].key 用 '\u001F'（Unit Separator）连接两个主键分量——
 * sessionId 为 UUID、segmentId 为应用内生成的序号，均不含该控制字符。
 */
suspend fun applyRetentionPolicy(
    dao: SentenceDao,
    nowWallMs: Long,
    policy: SentenceCacheRetention = SentenceCacheRetention(),
) {
    val rows = dao.sentenceAges()
    if (rows.isEmpty()) return
    val records = rows.map {
        CachedSentenceRecord(
            key = it.sessionId + KEY_SEPARATOR + it.segmentId,
            processedAtWallMs = it.lastProcessedAtWallMs,
        )
    }
    for (victim in policy.evict(records, nowWallMs)) {
        val sep = victim.key.indexOf(KEY_SEPARATOR)
        if (sep < 0) continue
        dao.deleteSentence(victim.key.substring(0, sep), victim.key.substring(sep + 1))
    }
}

private const val KEY_SEPARATOR = '\u001F'
