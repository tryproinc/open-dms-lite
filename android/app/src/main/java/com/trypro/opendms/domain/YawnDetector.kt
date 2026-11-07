package com.trypro.opendms.domain

/** [YawnDetector.update]の結果。診断ログ・UI表示にもそのまま使う。 */
data class YawnState(
    /** 監視窓内で成立したあくびの回数。 */
    val countInWindow: Int,
    /** 現在の「口が開いている」連続時間(ms)。閾値未満なら0。診断・閾値調整の手がかりに使う。 */
    val currentOpenDurationMs: Long,
    /**
     * 現在のepisodeで観測した`jawOpen`の最大値。開いていなければ0。
     *
     * [DayMvpSettings.yawnMinPeakJawOpen]との比較対象そのもの。診断CSVへ出して、
     * 「浅い開口を数えていないか」を後から確かめられるようにする。
     */
    val currentPeakJawOpen: Float,
    /** このフレームで新たにあくび1回が成立したか(episodeにつき1度だけtrueになる)。 */
    val yawnCompleted: Boolean,
    /** 窓内の回数が[DayMvpSettings.yawnMinCountInWindow]以上に達しているか。 */
    val isFrequent: Boolean,
)

/**
 * あくびを検出する。眠気レベルD4(「かなり眠そう」)の観察指標に対応する。
 *
 * ## 根拠(一次情報)
 * 国交省ASV「ドライバーモニタリング(眠気・居眠り検知)システム 基本設計書」(令和2年10月21日)
 * 2.2の解説は、眠気レベルごとに観察される行動特徴として **D4「意識的と思われる瞬きがある、
 * あくびが頻発、瞬きも視線の動きも遅い」** を挙げている。同書付表1により D4 は
 * Karolinska Sleepiness Scale の 8 に対応し、これはEU DDAW((EU) 2021/1341)が警告を必須と
 * する水準(KSS≥8)と一致する。
 *
 * 既存の検知指標([DayDmsStateMachine]の閉眼・[NoddingDetector]のこっくり)はいずれもD5
 * (「瞼を閉じる、頭が前後に倒れる」)に対応しており、D4の帯が空いていた。
 *
 * ## ASV上の位置づけ
 * ASVが検知を**必須**とするのはD5以上(2.3.2)であり、D4の検知・報知は任意である
 * (「予防的に不安全状態に陥らないようにするため、D5未満の眠気状態も検知しても良い」)。
 * ただし同書2.4.2は「眠気レベルの低い段階から報知する場合、2.4.1で示す報知とは異なる
 * 煩わしくない手段で報知を開始し、眠気レベルが改善しない場合にはそれらの手段を段階的に
 * 強いものにしていくことも有効」としている。本検出の報知を閉眼・こっくりより弱くしているのは
 * この記述に従うため。
 *
 * ## アルゴリズム
 * 1. MediaPipe Face Landmarkerのblendshape `jawOpen`が[DayMvpSettings.yawnJawOpenThreshold]
 *    以上を[DayMvpSettings.yawnMinDurationMs]以上**連続**し、**かつ**その区間のピークが
 *    [DayMvpSettings.yawnMinPeakJawOpen]以上なら、あくび1回とする。
 *    1回のepisodeで二重に数えないよう、成立は立ち上がり1度だけ。
 * 2. 直近[DayMvpSettings.yawnWindowMs]の回数が[DayMvpSettings.yawnMinCountInWindow]以上なら
 *    「頻発」とし、呼び出し側が報知する。ASVがD4の特徴を「あくびが**頻発**」としているため、
 *    1回では成立させない。
 *
 * ## 会話・飲食との分離
 * **連続時間(長さ)と ピーク(深さ)の2つ**で分離する。発話は口が速く開閉するため`jawOpen`が
 * 閾値を頻繁に下回り連続条件を満たしにくく、満たした場合でも開きが浅いためピーク条件で落ちる。
 *
 * 当初は連続時間だけで分離していたが、2026-09-23の実車ログで**浅く長い開口**
 * (ピーク0.47〜0.53が3秒前後)が13本数えられており、これが「窓内3回」を先に埋めて
 * 本物のあくび1回目で発報させていた。深さの条件はこの実測から追加したものである
 * ([DayMvpSettings.yawnMinPeakJawOpen])。飲食は成立し得るが、10分3回という頻度条件で
 * 大半が落ちる想定である。
 *
 * このため「口が閉じた」と「値が取れていない」を**厳密に区別する**([update]の実装を参照)。
 * 両者を同じ欠損として扱うと、発話の短い閉口が欠損猶予で繋がってしまい、この分離が成立しない。
 *
 * **この分離性能は未実測**であり、D1-010で`jawOpen`の実データ(あくび・会話・飲食)を収集して
 * 検証する。分離できていなければ「あくび中は目が細くなる」等の併用条件を追加する
 * ([NoddingDetector]が開眼比を併用しているのと同じ考え方)。それまで閾値は製品保証値ではない。
 *
 * ## 較正について
 * 閉眼判定と異なり較正基準を使わない絶対閾値である。理由は[DayMvpSettings.yawnJawOpenThreshold]
 * のKDocを参照。
 *
 * 品質無効([com.trypro.opendms.vision.SignalQuality.isValidForDecision]がfalse)のフレームは
 * 呼び出し側でnullを渡して除外すること。
 */
class YawnDetector(private val settings: DayMvpSettings) {

    /** 現在のepisode(口が開いている連続区間)の開始時刻。開いていなければnull。 */
    private var openStartMs: Long? = null

    /** 直近で「口が開いている」と観測した時刻。欠損許容の判定に使う。 */
    private var lastOpenMs: Long? = null

    /** 現在のepisodeで既に1回数えたか。1回のあくびを毎フレーム数えないためのラッチ。 */
    private var countedThisEpisode = false

    /** 現在のepisodeで観測した`jawOpen`の最大値。深さの条件([DayMvpSettings.yawnMinPeakJawOpen])に使う。 */
    private var episodePeakJawOpen = 0f

    /** あくびが成立した時刻(monotonic ms)。窓外になったものから捨てる。 */
    private val yawnTimesMs = ArrayDeque<Long>()

    /**
     * @param jawOpen blendshape `jawOpen`の生値(0..1)。顔未検出・品質無効ならnullを渡す。
     */
    fun update(monotonicMs: Long, jawOpen: Float?): YawnState {
        var completed = false

        // 「口が閉じている」(値が取れていて閾値未満)と「値が取れていない」(品質無効・顔未検出)を
        // 区別することが、会話との分離の要である。
        //
        // 当初は両者を区別せず、どちらも`qualityGapGraceMs`(500ms)以内なら同じepisodeとして
        // 繋いでいた。しかしそれでは「200ms開けて400ms閉じる」を繰り返す発話がすべて1つの
        // episodeとして連結され、実時間1.2秒でauto的にあくびが成立してしまう(unit testで検出)。
        //
        // 正しい意味づけは次のとおり:
        // - 閉口(値あり・閾値未満) = **条件が偽**。その場でepisodeを終了する。
        // - 欠損(null)            = **測れていない**。猶予以内なら判断を保留しepisodeを維持する。
        val isOpen = jawOpen != null && jawOpen >= settings.yawnJawOpenThreshold
        val isMissing = jawOpen == null

        if (isOpen) {
            val lastOpen = lastOpenMs
            // 欠損が許容範囲を超えていた場合はここで別のepisodeとして数え直す。
            if (openStartMs == null || lastOpen == null || monotonicMs - lastOpen > settings.qualityGapGraceMs) {
                openStartMs = monotonicMs
                countedThisEpisode = false
                episodePeakJawOpen = 0f
            }
            lastOpenMs = monotonicMs
            episodePeakJawOpen = maxOf(episodePeakJawOpen, jawOpen ?: 0f)

            val duration = monotonicMs - (openStartMs ?: monotonicMs)
            // **連続時間と深さの両方**を満たして1回。ピークは連続時間より後に来ることもあるので、
            // 「両方が揃った時点」で数える(浅く長い開口=会話・口呼吸をここで落とす)。
            if (!countedThisEpisode &&
                duration >= settings.yawnMinDurationMs &&
                episodePeakJawOpen >= settings.yawnMinPeakJawOpen
            ) {
                countedThisEpisode = true
                completed = true
                yawnTimesMs.addLast(monotonicMs)
            }
        } else if (isMissing) {
            // 欠損。猶予を超えたらepisodeを破棄する(超えるまでは何もせず維持する)。
            val lastOpen = lastOpenMs
            if (lastOpen != null && monotonicMs - lastOpen > settings.qualityGapGraceMs) {
                openStartMs = null
                lastOpenMs = null
                countedThisEpisode = false
                episodePeakJawOpen = 0f
            }
        } else {
            // 口が閉じた。あくびのepisodeはここで終わる。
            openStartMs = null
            lastOpenMs = null
            countedThisEpisode = false
            episodePeakJawOpen = 0f
        }

        pruneWindow(monotonicMs)

        val start = openStartMs
        val lastOpen = lastOpenMs
        val currentDuration = if (start != null && lastOpen != null) lastOpen - start else 0L

        return YawnState(
            countInWindow = yawnTimesMs.size,
            currentOpenDurationMs = currentDuration,
            currentPeakJawOpen = if (start != null) episodePeakJawOpen else 0f,
            yawnCompleted = completed,
            isFrequent = yawnTimesMs.size >= settings.yawnMinCountInWindow,
        )
    }

    private fun pruneWindow(nowMs: Long) {
        val cutoff = nowMs - settings.yawnWindowMs
        while (yawnTimesMs.isNotEmpty() && yawnTimesMs.first() < cutoff) {
            yawnTimesMs.removeFirst()
        }
    }

    fun reset() {
        openStartMs = null
        lastOpenMs = null
        countedThisEpisode = false
        episodePeakJawOpen = 0f
        yawnTimesMs.clear()
    }

    /**
     * 窓内のあくび成立時刻(monotonic ms)。**運行をまたいで持ち越すために公開する。**
     *
     * 眠気は運行の区切りとは無関係に続く。休憩で「運行終了→運行開始」を挟むと、
     * それまでのあくびが消えて頻発(10分3回)が成立しなくなっていた。
     * 生理現象の連続性を保つため、窓の中身だけを次の運行へ渡す。
     */
    fun exportYawnTimes(): List<Long> = yawnTimesMs.toList()

    /**
     * [exportYawnTimes]で取り出した成立時刻を復元する。**現在進行中のepisodeは復元しない**
     * ── 口が開いている途中で運行を切り替えた場合、その開口は次の運行で数え直すのが妥当。
     *
     * 時刻は`SystemClock.uptimeMillis()`基準で、プロセスをまたいでも単調なので
     * そのまま比較できる。窓(既定10分)より古いものは次の[update]で自動的に捨てられる。
     */
    fun restoreYawnTimes(times: List<Long>) {
        yawnTimesMs.clear()
        times.sorted().forEach { yawnTimesMs.addLast(it) }
    }
}
