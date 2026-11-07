package com.trypro.opendms.domain

/** [NoddingDetector.update]の結果。診断ログ・UI表示にもそのまま使う。 */
data class NoddingState(
    /** 監視窓内で検出した頭部pitchの反転回数。 */
    val reversalCount: Int,
    /** 監視窓内の平均正規化開眼比(1.0=較正時の開眼相当)。サンプルが無ければnull。 */
    val meanEyeOpenRatio: Float?,
    /** 反転回数・開眼比の両条件を満たし「こっくり」状態と判定したか。 */
    val isNodding: Boolean,
    /** 監視窓内の相対pitchの振幅(最大−最小、度)。サンプルが無ければnull。 */
    val pitchRangeDeg: Float? = null,
    /** 監視窓内の相対yawの振幅(度)。yawが渡されていなければnull。 */
    val yawRangeDeg: Float? = null,
    /**
     * **開眼こっくり候補**(2026-09-11)。目の条件を見ずに、
     * 反転回数・pitch振幅・yaw振幅だけで「こっくりらしい」と言えるか。
     * 無音で記録するだけ。理由は[DayMvpSettings.noddingCandidateMinPitchRangeDeg]。
     */
    val isOpenEyeCandidate: Boolean = false,
)

/**
 * 居眠り時の「こっくり、こっくり」(頭部の反復的な小さい上下動)を検出する独自指標。
 *
 * ## 背景(2026-08-29 実機検証)
 * 既存の判定は次の理由でこっくりを検出できなかった:
 * - 脇見判定は相対pitch 20°以上が条件だが、こっくりの実測振幅は**3〜5°**しかない。
 * - 閉眼判定は連続1.5秒以上が条件だが、こっくり中の閉眼は開閉を繰り返し連続しない。
 *
 * つまり「大きく傾ける」でも「長く閉じる」でもない中間の症状で、
 * 振幅・持続時間のどちらの閾値にも掛からない領域だった。
 *
 * ## アルゴリズム
 * 1. 相対pitchに対しZigZag法で極値を追跡し、直近の極値から[DayMvpSettings.noddingMinAmplitudeDeg]
 *    以上戻ったら「反転1回」として時刻を記録する(微小なノイズは振幅条件で除去される)。
 * 2. 直近[DayMvpSettings.noddingWindowMs]の反転回数が[DayMvpSettings.noddingMinReversals]以上、
 *    **かつ**同窓の平均開眼比が[DayMvpSettings.noddingMaxEyeOpenRatio]以下なら「こっくり」とする。
 *
 * 開眼比の条件を併用するのは、会話中の頷きやミラー確認の反復といった「覚醒したまま頭を動かす」
 * 動作を除外するため。実測ログでの分離性は良好だった(こっくり時の平均開眼比0.56〜0.65に対し、
 * 覚醒・静止時は0.88〜0.90)。
 *
 * ## 実測での検証(オフライン再現)
 * - session_70b193d0(こっくり再現): 2区間(12.8秒/19.9秒)を検出。
 * - session_43a993fc(上下向き試験)/session_8c2033fe(閉眼試験): 検出0=誤検知なし。
 *
 * 品質無効([SignalQuality.isValidForDecision]がfalse)のフレームは呼び出し側で除外すること。
 */
class NoddingDetector(private val settings: DayMvpSettings) {

    private var direction = 1
    private var extremumDeg: Float? = null

    /** 反転が起きた時刻(monotonic ms)。窓外になったものから捨てる。 */
    private val reversalTimesMs = ArrayDeque<Long>()

    /**
     * あくび中とその直後、反転・開眼比を数えないための期限(monotonic ms)。
     * 理由は[DayMvpSettings.noddingYawnSuppressionMs]のKDocを参照。
     */
    private var suppressUntilMs: Long? = null

    /** 開眼比の窓内サンプル(時刻, 値)。平均を求めるために保持する。 */
    private val eyeSamples = ArrayDeque<Pair<Long, Float>>()

    /**
     * 窓内の開眼比の合計。毎フレーム加算・減算するため、Floatだと2時間連続運用(Gate D3)で
     * 丸め誤差が蓄積し得る。Doubleで保持し、さらに[RESUM_INTERVAL]回ごとに窓内サンプルから
     * 再計算して誤差をリセットする。
     */
    private var eyeSum = 0.0
    private var updatesSinceResum = 0

    /** 窓内の姿勢サンプル(時刻, 相対pitch, 相対yaw)。振幅を出すために保持する。 */
    private val poseSamples = ArrayDeque<Triple<Long, Float, Float?>>()

    /**
     * @param jawOpen blendshape `jawOpen`の生値。あくび中とその直後は、こっくりの判定材料を
     *   一切取り込まない(あくびの頭部の動きをこっくりと数えないため)。nullなら抑制しない。
     */
    fun update(
        monotonicMs: Long,
        relativePitchDeg: Float?,
        eyeOpenRatio: Float?,
        jawOpen: Float? = null,
        relativeYawDeg: Float? = null,
    ): NoddingState {
        if (jawOpen != null && jawOpen >= settings.noddingYawnSuppressionJawOpen) {
            suppressUntilMs = monotonicMs + settings.noddingYawnSuppressionMs
        }
        val suppressed = suppressUntilMs?.let { monotonicMs <= it } ?: false

        if (suppressed) {
            // 反転の基準(極値)だけは現在値へ追従させる。そうしないと抑制の解除直後に、
            // あくびで動いた分がまとめて1回の大きな反転として数えられてしまう。
            if (relativePitchDeg != null) extremumDeg = relativePitchDeg
        } else if (relativePitchDeg != null) {
            updateReversals(monotonicMs, relativePitchDeg)
        }
        // 振幅の材料。あくび中は反転と同じ理由で取り込まない。
        if (relativePitchDeg != null && !suppressed) {
            poseSamples.addLast(Triple(monotonicMs, relativePitchDeg, relativeYawDeg))
        }
        // 開眼比も同様。あくび中は目が細くなるので、取り込むと窓平均が下がり
        // 「覚醒時の頷きを除外する」という併用条件の意味が失われる。
        if (eyeOpenRatio != null && !suppressed) {
            eyeSamples.addLast(monotonicMs to eyeOpenRatio)
            eyeSum += eyeOpenRatio
        }
        pruneWindow(monotonicMs)

        val meanEye = if (eyeSamples.isEmpty()) null else (eyeSum / eyeSamples.size).toFloat()
        val count = reversalTimesMs.size
        val nodding = count >= settings.noddingMinReversals &&
            meanEye != null && meanEye <= settings.noddingMaxEyeOpenRatio

        val pitchRange = poseSamples.range { it.second }
        val yawRange = poseSamples.range { it.third }
        // yawが取れていない窓では候補にしない(左右を見ていたかどうか言えないため)。
        val candidate = count >= settings.noddingMinReversals &&
            pitchRange != null && pitchRange >= settings.noddingCandidateMinPitchRangeDeg &&
            yawRange != null && yawRange < settings.noddingCandidateMaxYawRangeDeg
        return NoddingState(
            reversalCount = count,
            meanEyeOpenRatio = meanEye,
            isNodding = nodding,
            pitchRangeDeg = pitchRange,
            yawRangeDeg = yawRange,
            isOpenEyeCandidate = candidate,
        )
    }

    /** 窓内サンプルの (最大 − 最小)。nullの値は飛ばし、1つも無ければnull。 */
    private inline fun <T> Collection<T>.range(value: (T) -> Float?): Float? {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (s in this) {
            val v = value(s) ?: continue
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return if (lo <= hi) hi - lo else null
    }

    /**
     * ZigZag法。直近の極値([extremumDeg])を進行方向へ更新し続け、逆方向へ
     * [DayMvpSettings.noddingMinAmplitudeDeg]以上振れた時点を「反転」とみなす。
     */
    private fun updateReversals(monotonicMs: Long, pitchDeg: Float) {
        val extremum = extremumDeg
        if (extremum == null) {
            extremumDeg = pitchDeg
            return
        }
        if (direction > 0) {
            if (pitchDeg > extremum) {
                extremumDeg = pitchDeg
            } else if (extremum - pitchDeg >= settings.noddingMinAmplitudeDeg) {
                reversalTimesMs.addLast(monotonicMs)
                direction = -1
                extremumDeg = pitchDeg
            }
        } else {
            if (pitchDeg < extremum) {
                extremumDeg = pitchDeg
            } else if (pitchDeg - extremum >= settings.noddingMinAmplitudeDeg) {
                reversalTimesMs.addLast(monotonicMs)
                direction = 1
                extremumDeg = pitchDeg
            }
        }
    }

    private fun pruneWindow(nowMs: Long) {
        val cutoff = nowMs - settings.noddingWindowMs
        while (reversalTimesMs.isNotEmpty() && reversalTimesMs.first() < cutoff) {
            reversalTimesMs.removeFirst()
        }
        while (eyeSamples.isNotEmpty() && eyeSamples.first().first < cutoff) {
            eyeSum -= eyeSamples.removeFirst().second
        }
        while (poseSamples.isNotEmpty() && poseSamples.first().first < cutoff) {
            poseSamples.removeFirst()
        }
        if (eyeSamples.isEmpty()) {
            eyeSum = 0.0
            updatesSinceResum = 0
            return
        }
        // 累積誤差のリセット。窓内サンプルから合計を作り直す。
        if (++updatesSinceResum >= RESUM_INTERVAL) {
            updatesSinceResum = 0
            var sum = 0.0
            for (sample in eyeSamples) sum += sample.second
            eyeSum = sum
        }
    }

    fun reset() {
        direction = 1
        extremumDeg = null
        suppressUntilMs = null
        reversalTimesMs.clear()
        eyeSamples.clear()
        poseSamples.clear()
        eyeSum = 0.0
        updatesSinceResum = 0
    }

    private companion object {
        /** 開眼比合計を再計算する間隔(フレーム数)。20fpsで約25秒ごと。 */
        const val RESUM_INTERVAL = 500
    }
}
