package com.repovoyage.sign.recognition

/**
 * 识别模型目录（P5 产物，2026-09-23 用户决定：**两个模型均部署云端直接调用**）。
 * 模型 A = 面对面视角训练（相机对着打手语的人）；模型 B = 第一视角训练
 * （打语者自身视角/穿戴机位）——按实际拍摄视角选择。
 *
 * 选择持久化于 [com.repovoyage.sign.settings.AppSettings].selectedModelId；
 * 云端识别客户端的接线随 P6 SignRecognizer 适配器落地。
 */
data class ModelCatalogEntry(
    val id: String,
    val displayName: String,
    val viewpointHint: String,
)

object ModelCatalog {

    /** 第一视角模型（云端词级 CV + 组句 Agent 已部署，固定窗口切片识别已接线） */
    const val MODEL_B_ID = "model-b"

    val ENTRIES = listOf(
        ModelCatalogEntry("model-a", "模型 A", "面对面视角（相机对着打语者）"),
        ModelCatalogEntry(MODEL_B_ID, "模型 B", "第一视角（打语者自身视角）"),
    )
}
