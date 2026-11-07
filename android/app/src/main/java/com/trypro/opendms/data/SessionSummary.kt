package com.trypro.opendms.data

/** 運行結果画面(A-09)向けの集計。SES-008: 監視時間、有効監視率、警告件数。 */
data class SessionSummary(
    val monitoringMs: Long,
    val validMonitoringMs: Long,
    val eyeClosureCautionCount: Int,
    val eyeClosureWarningCount: Int,
    val distractionWarningCount: Int,
    val unavailableCount: Int,
) {
    val validMonitoringRatio: Float
        get() = if (monitoringMs > 0L) (validMonitoringMs.toFloat() / monitoringMs).coerceIn(0f, 1f) else 0f

    val totalWarningCount: Int
        get() = eyeClosureCautionCount + eyeClosureWarningCount + distractionWarningCount
}
