package com.ai.android.model

import kotlinx.serialization.Serializable

/** 单次 Agent 任务期间的会话统计（用于 InputBar 底部状态栏） */
@Serializable
data class SessionStats(
    val round: Int = 0,
    val steps: Int = 0,
    val tokensPerSec: Double = 0.0,
    val totalTokens: Int = 0,
    val cacheHitRate: Double = 0.0,
)