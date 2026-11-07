package com.trypro.opendms.domain

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 閾値設定画面で変えられる1項目。値は**画面の単位**(秒・分・回・度・km/h)で持つ。
 *
 * @property id 保存のキーにもなる。変えると保存済みの値が読めなくなる。
 * @property lowLabel スライダーの左端の意味(「敏感」など)
 * @property highLabel スライダーの右端の意味
 * @property decimals 表示する小数の桁
 */
data class TunableItem(
    val id: String,
    val group: String,
    val label: String,
    val description: String,
    val unit: String,
    val range: ClosedFloatingPointRange<Float>,
    val step: Float,
    val decimals: Int,
    val lowLabel: String,
    val highLabel: String,
    val get: (DayMvpSettings) -> Float,
    val set: (DayMvpSettings, Float) -> DayMvpSettings,
)

/**
 * Lite の閾値設定画面に出す**10項目**。
 *
 * 全閾値(40項目超)を出すと、何を動かせば何が変わるのか分からない。そこで、運転者が体感できて
 * 意味が説明できるものだけに絞り、**既定値を変えなくても使える**前提で、残りは既定値のまま固定する。
 * 値の向きは項目ごとに違う(継続時間は短いほど敏感、あくびの数える期間は長いほど敏感)ので、
 * 左右の端の意味を項目ごとに持つ。
 */
object TunableSettings {

    private const val SENSITIVE = "敏感"
    private const val INSENSITIVE = "鈍感"

    private const val DROWSY_EYES = "眠気: 目を閉じる"
    private const val DROWSY_NODDING = "眠気: こっくり"
    private const val DROWSY_YAWN = "眠気: あくび"
    private const val DISTRACTION = "脇見"
    private const val COMMON = "共通"

    val items: List<TunableItem> = listOf(
        TunableItem(
            id = "eyeClosureCaution", group = DROWSY_EYES,
            label = "注意まで",
            description = "目を閉じ続けて、最初の注意(休憩を検討してください)が出るまでの時間",
            unit = "秒", range = 0.5f..3.0f, step = 0.1f, decimals = 1,
            lowLabel = SENSITIVE, highLabel = INSENSITIVE,
            get = { it.eyeClosureCautionMs / 1000f },
            set = { s, v -> s.copy(eyeClosureCautionMs = (v * 1000f).roundToLong()) },
        ),
        TunableItem(
            id = "eyeClosureWarning", group = DROWSY_EYES,
            label = "強い警告まで",
            description = "さらに目を閉じ続けて、強い警告(安全な場所で休憩してください)が出るまでの時間。注意より長くなるよう自動で揃えます",
            unit = "秒", range = 1.0f..5.0f, step = 0.1f, decimals = 1,
            lowLabel = SENSITIVE, highLabel = INSENSITIVE,
            get = { it.eyeClosureWarningMs / 1000f },
            set = { s, v -> s.copy(eyeClosureWarningMs = (v * 1000f).roundToLong()) },
        ),
        TunableItem(
            id = "noddingReversals", group = DROWSY_NODDING,
            label = "警告に必要な回数",
            description = "頭が前後に揺れた回数(12秒の間)がこの回数に達すると警告します",
            unit = "回", range = 3f..8f, step = 1f, decimals = 0,
            lowLabel = SENSITIVE, highLabel = INSENSITIVE,
            get = { it.noddingMinReversals.toFloat() },
            set = { s, v -> s.copy(noddingMinReversals = v.roundToInt()) },
        ),
        TunableItem(
            id = "yawnCount", group = DROWSY_YAWN,
            label = "注意に必要な回数",
            description = "下の期間のうちに、あくびがこの回数に達すると注意します",
            unit = "回", range = 1f..6f, step = 1f, decimals = 0,
            lowLabel = SENSITIVE, highLabel = INSENSITIVE,
            get = { it.yawnMinCountInWindow.toFloat() },
            set = { s, v -> s.copy(yawnMinCountInWindow = v.roundToInt()) },
        ),
        TunableItem(
            id = "yawnWindow", group = DROWSY_YAWN,
            label = "数える期間",
            description = "あくびの回数を数える期間。長いほど回数が溜まりやすくなります",
            unit = "分", range = 5f..30f, step = 5f, decimals = 0,
            lowLabel = INSENSITIVE, highLabel = SENSITIVE,
            get = { it.yawnWindowMs / 60_000f },
            set = { s, v -> s.copy(yawnWindowMs = (v * 60_000f).roundToLong()) },
        ),
        TunableItem(
            id = "distractionWarning", group = DISTRACTION,
            label = "警告まで",
            description = "前方以外を見続けて、警告(前を確認してください)が出るまでの時間。時速50km超は別に3.5秒です",
            unit = "秒", range = 2f..10f, step = 0.5f, decimals = 1,
            lowLabel = SENSITIVE, highLabel = INSENSITIVE,
            get = { it.distractionWarningMs / 1000f },
            set = { s, v -> s.copy(distractionWarningMs = (v * 1000f).roundToLong()) },
        ),
        TunableItem(
            id = "distractionYaw", group = DISTRACTION,
            label = "横向きの角度",
            description = "正面からこの角度以上に横を向くと、前方以外を見ていると数えます",
            unit = "度", range = 15f..45f, step = 1f, decimals = 0,
            lowLabel = SENSITIVE, highLabel = INSENSITIVE,
            get = { it.distractionYawDeg },
            set = { s, v -> s.copy(distractionYawDeg = v) },
        ),
        TunableItem(
            id = "distractionPitch", group = DISTRACTION,
            label = "上下向きの角度",
            description = "正面からこの角度以上に上下を向く(手元を見下ろすなど)と、前方以外を見ていると数えます",
            unit = "度", range = 10f..40f, step = 1f, decimals = 0,
            lowLabel = SENSITIVE, highLabel = INSENSITIVE,
            get = { it.distractionPitchDownDeg },
            set = { s, v -> s.copy(distractionPitchDownDeg = v) },
        ),
        TunableItem(
            id = "distractionMinSpeed", group = DISTRACTION,
            label = "判定する速度の下限",
            description = "この速度未満では脇見の警告を出しません(信号待ち・休憩中の誤報を防ぎます)",
            unit = "km/h", range = 0f..30f, step = 5f, decimals = 0,
            lowLabel = SENSITIVE, highLabel = INSENSITIVE,
            get = { it.distractionMinSpeedKph },
            set = { s, v -> s.copy(distractionMinSpeedKph = v) },
        ),
        TunableItem(
            id = "alertCooldown", group = COMMON,
            label = "同じ警告の間隔",
            description = "同じ種類の警告を鳴らしたあと、次を鳴らさない時間",
            unit = "秒", range = 5f..30f, step = 5f, decimals = 0,
            lowLabel = "頻繁に鳴る", highLabel = "間隔をあける",
            get = { it.alertCooldownMs / 1000f },
            set = { s, v -> s.copy(alertCooldownMs = (v * 1000f).roundToLong()) },
        ),
    )

    /** 強い警告が注意より短くならないための最小の差(ms)。 */
    private const val MIN_WARNING_GAP_MS = 500L

    /**
     * 項目どうしの整合を取る。値を1つ動かすたびに通す。
     *
     * - 閉眼の強い警告は、注意より[MIN_WARNING_GAP_MS]以上長くする
     * - 脇見の「警告まで(通常)」より、時速50km超の警告時間を長くしない
     */
    fun normalize(s: DayMvpSettings): DayMvpSettings = s.copy(
        eyeClosureWarningMs = maxOf(s.eyeClosureWarningMs, s.eyeClosureCautionMs + MIN_WARNING_GAP_MS),
        distractionWarningMsHighSpeed = minOf(s.distractionWarningMsHighSpeed, s.distractionWarningMs),
    )

    /** [value]を範囲内の刻みへ丸める。 */
    fun snap(item: TunableItem, value: Float): Float {
        val steps = ((value - item.range.start) / item.step).roundToInt()
        val snapped = item.range.start + steps * item.step
        return snapped.coerceIn(item.range)
    }
}
