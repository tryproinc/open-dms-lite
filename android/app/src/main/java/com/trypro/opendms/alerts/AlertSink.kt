package com.trypro.opendms.alerts

/**
 * 音・振動・画面で表現する警告の種別。11_DAY_SMARTPHONE_MVP_PLAN.md 3.6の表に対応する
 * (「高温」は現時点でDayDmsStateMachineが発報しないためD0-008の対象外。D0-011以降で追加検討)。
 */
enum class AlertKind {
    EYE_CLOSURE_CAUTION,
    EYE_CLOSURE_WARNING,
    DISTRACTION_WARNING,
    /** 2026-08-29追加: 「こっくり」(居眠り時の反復的な頭部上下動)。 */
    DROWSY_NODDING,
    /**
     * 2026-08-31追加: あくびの頻発(眠気レベルD4相当)。
     *
     * ASV「眠気・居眠り検知」基本設計書2.4.2は、D5未満の段階から報知する場合について
     * 「2.4.1で示す報知とは異なる**煩わしくない手段**で報知を開始し、眠気レベルが改善しない
     * 場合にはそれらの手段を段階的に強いものにしていくことも有効」としている。これに従い、
     * 音は閉眼注意([EYE_CLOSURE_CAUTION])よりさらに弱いものにする。
     */
    DROWSY_YAWNING,
    /** 端末が高温。11_DAY_SMARTPHONE_MVP_PLAN.md 3.6。 */
    THERMAL,
    UNAVAILABLE,

    /**
     * 2026-08-31追加: 監視システムの故障。
     *
     * ASV「眠気・居眠り検知」基本設計書4章および「脇見等検知」基本設計書4章の
     * 「本システムが故障を検知した場合には、故障していることをドライバーが認識できる手段を
     * 有していること」に対応する**必須要件**。
     *
     * [UNAVAILABLE](顔が写っていない等、システムは正常だが判定できない状態)とは別物である。
     * ただし警告音は既に6種あり7種目を足すと聴き分けが難しくなるため、**音は[UNAVAILABLE]と
     * 同じものを流用し、画面表示で区別する**。ASVは故障告知の手段を規定していないため、
     * これで要件を満たす。
     */
    SYSTEM_FAULT,

    /**
     * 較正の合図(2026-09-11)。警告ではない。
     * 閉眼較正では利用者が目を閉じているので、開始と終了を**音と短い振動**で知らせる。
     * 画面の警告表示は出さない([AlertKind]を使い回すのは音の経路を1つに保つため)。
     */
    CALIBRATION_CUE,
}

/**
 * 判定成立から警告(音・振動・画面)を発報する境界。11_DAY_SMARTPHONE_MVP_PLAN.md 4の
 * `AlertSink`に対応する。`trigger()`は即座に返り、実際の再生・振動は非同期に行う
 * (NFR-002: 判定成立から警告開始まで500ms以内)。走行中の操作(タップ)は要求しない(ALT-005)。
 */
interface AlertSink {
    fun trigger(kind: AlertKind)
}
