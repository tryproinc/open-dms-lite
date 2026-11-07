package com.trypro.opendms.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * D0-007 Gate: 「閉眼、脇見、欠損、cooldown境界試験が通る」を検証するL0テスト。
 * ケース番号は05_VALIDATION_AND_ACCEPTANCE.md のUT-xxxに対応するものはコメントで示す
 * (MVP-0は速度不明・単一閾値のため、速度別に分かれるUT-008〜010/016/017、PERCLOSのUT-006/007は対象外)。
 */
class DayDmsStateMachineTest {

    private val settings = DayMvpSettings.DAY_MVP0_DEFAULT
    private lateinit var sm: DayDmsStateMachine

    @Before
    fun setUp() {
        sm = DayDmsStateMachine(settings)
    }

    private fun step(
        monotonicMs: Long,
        valid: Boolean = true,
        eyesClosed: Boolean = false,
        distracted: Boolean = false,
        facialSignalReliable: Boolean = true,
        speedKph: Float? = null,
        jawOpen: Float? = null,
    ) = sm.process(
        DmsInput(
            monotonicMs = monotonicMs,
            validForDecision = valid,
            eyesClosed = eyesClosed,
            distracted = distracted,
            facialSignalReliable = facialSignalReliable,
            jawOpen = jawOpen,
            speedKph = speedKph,
        ),
    )

    /** 0msから[toMs]まで[stepMs]間隔(既定100ms、gap猶予300ms未満なので継続として扱われる)で刻む。 */
    private fun feedContinuous(
        toMs: Long,
        stepMs: Long = 100L,
        fromMs: Long = 0L,
        eyesClosed: Boolean = false,
        distracted: Boolean = false,
        valid: Boolean = true,
        facialSignalReliable: Boolean = true,
        speedKph: Float? = null,
        jawOpen: Float? = null,
    ): List<DmsOutput> {
        val outputs = mutableListOf<DmsOutput>()
        var t = fromMs
        while (t < toMs) {
            outputs += step(t, valid, eyesClosed, distracted, facialSignalReliable, speedKph, jawOpen)
            t += stepMs
        }
        outputs += step(toMs, valid, eyesClosed, distracted, facialSignalReliable, speedKph, jawOpen)
        return outputs
    }

    // ---- 閉眼 ----


    /**
     * 2026-09-01回帰(実機session_b4ce5f53)。
     *
     * 顔を横に向けると`ONE_EYE_ONLY`/`HEAD_ANGLE_TOO_LARGE`が立ち、
     * それが`validForDecision`をfalseにしていたため**脇見のタイマーが一切伸びなかった**。
     * いまはこの2つを`facialSignalReliable=false`として扱い、判定自体は有効なままにしている。
     * ここでは「表情特徴が読めなくても脇見は成立する」ことを保証する。
     */
    @Test
    fun `facialSignalReliableがfalseでも脇見の継続時間は伸びる`() {
        var out: DmsOutput? = null
        // 発報はcooldownで1回だけなので、最終フレームではなく全フレームから拾う。
        val fired = mutableListOf<AlertType>()
        var t = 0L
        while (t <= 7_000L) {
            out = step(
                monotonicMs = t,
                distracted = true,
                facialSignalReliable = false, // 横を向いているので表情は読めない
                speedKph = 60f,
            )
            fired += out.firedAlerts.map { it.type }
            t += 100L
        }
        assertThat(out!!.distractionDurationMs).isAtLeast(settings.distractionWarningMs)
        assertThat(fired).contains(AlertType.DISTRACTION)
    }

    /**
     * 逆方向の保証。`ONE_EYE_ONLY`等の減点を0にしたことで**横を向いている間のフレームが
     * 「品質有効」になった**ので、開眼比・口の開きを使う判定をここで止めておかないと、
     * 横を向くたびに眠気を誤検知する(2026-08-31に16分で39回誤発報したのと同じ壊れ方)。
     */
    @Test
    fun `facialSignalReliableがfalseの間は閉眼を成立させない`() {
        var out: DmsOutput? = null
        var t = 0L
        while (t <= 5_000L) {
            out = step(monotonicMs = t, eyesClosed = true, facialSignalReliable = false)
            t += 100L
        }
        assertThat(out!!.eyeClosureDurationMs).isEqualTo(0L)
        assertThat(out.firedAlerts.map { it.type }).doesNotContain(AlertType.EYE_CLOSURE)
    }

    /** あくびも同じ扱い(横を向いている間は口の開きが読めない)。 */
    @Test
    fun `facialSignalReliableがfalseの間はあくびを数えない`() {
        var out: DmsOutput? = null
        var t = 0L
        while (t <= 5_000L) {
            out = step(monotonicMs = t, facialSignalReliable = false, jawOpen = 0.9f)
            t += 100L
        }
        assertThat(out!!.yawn.currentOpenDurationMs).isEqualTo(0L)
        assertThat(out.yawn.countInWindow).isEqualTo(0)
    }

    @Test
    fun `UT-001 両眼閉眼1_49秒では警告なし`() {
        val outputs = feedContinuous(1490L, eyesClosed = true)
        assertThat(outputs.flatMap { it.firedAlerts }).isEmpty()
    }

    @Test
    fun `UT-002 両眼閉眼1_50秒で注意警告`() {
        val outputs = feedContinuous(1500L, eyesClosed = true)
        assertThat(outputs.flatMap { it.firedAlerts })
            .containsExactly(FiredAlert(AlertType.EYE_CLOSURE, AlertSeverity.CAUTION, 1500L))
    }

    @Test
    fun `UT-003 両眼閉眼2_50秒で強い警告(途中の注意も発報される)`() {
        val outputs = feedContinuous(2500L, eyesClosed = true)
        assertThat(outputs.flatMap { it.firedAlerts }).containsExactly(
            FiredAlert(AlertType.EYE_CLOSURE, AlertSeverity.CAUTION, 1500L),
            FiredAlert(AlertType.EYE_CLOSURE, AlertSeverity.WARNING, 2500L),
        )
    }

    @Test
    fun `UT-004 片目だけ(eyesClosed=false)が3秒続いても連続閉眼警告なし`() {
        // 片目だけの状態はDmsInputMapperがeyesClosed=falseへ正規化する前提のため、ここではfalseで表現する。
        val outputs = feedContinuous(3000L, eyesClosed = false)
        assertThat(outputs.flatMap { it.firedAlerts }).isEmpty()
    }

    @Test
    fun `UT-005 低品質中に3秒相当続いても眠気警告は出ずLIMITEDへ遷移する`() {
        // 05_VALIDATION_AND_ACCEPTANCE.md UT-005は「LIMITED/監視不能へ」。3秒は
        // unavailableLimitedMs(2秒)は超えるがunavailableWarningMs(5秒)未満なのでLIMITEDが正しい。
        val outputs = feedContinuous(3000L, eyesClosed = true, valid = false)
        assertThat(outputs.flatMap { it.firedAlerts }.filter { it.type == AlertType.EYE_CLOSURE }).isEmpty()
        assertThat(outputs.last().availability).isEqualTo(MonitoringAvailability.LIMITED)
    }

    // ---- 脇見 ----

    @Test
    fun `脇見5_99秒では警告なし`() {
        val outputs = feedContinuous(5990L, distracted = true)
        assertThat(outputs.flatMap { it.firedAlerts }).isEmpty()
    }

    @Test
    fun `脇見6_00秒で警告`() {
        val outputs = feedContinuous(6000L, distracted = true)
        assertThat(outputs.flatMap { it.firedAlerts })
            .containsExactly(FiredAlert(AlertType.DISTRACTION, AlertSeverity.WARNING, 6000L))
    }

    /**
     * 2026-08-29回帰: 首を振っている最中(facialSignalReliable=false)でも脇見判定は継続する。
     * 頭部回転で信頼できなくなるのは開眼度(blendshape)だけで、頭部角度は有効なため。
     * これを分離しないと、実機で脇見の継続時間が0msのまま一切成立しなかった。
     */
    @Test
    fun `頭部回転中でも脇見の継続時間は伸びる`() {
        val outputs = feedContinuous(6000L, distracted = true, facialSignalReliable = false)
        assertThat(outputs.flatMap { it.firedAlerts })
            .containsExactly(FiredAlert(AlertType.DISTRACTION, AlertSeverity.WARNING, 6000L))
    }

    /** 逆に、閉眼判定は頭部回転中は成立させない(blendshapeが信用できないため)。 */
    @Test
    fun `頭部回転中は閉眼の継続時間が伸びない`() {
        val outputs = feedContinuous(3000L, eyesClosed = true, facialSignalReliable = false)
        assertThat(outputs.flatMap { it.firedAlerts }).isEmpty()
    }

    /**
     * 2026-08-29回帰: こっくりは重大度が昇格しない(常にCAUTION)ため、episode終了時に
     * cooldownのepisode状態をリセットしないと運行中2回目以降が永久に発報しなくなる。
     * 実機ログsession_3eaa59caで3回のこっくりのうち1回目しか鳴らない不具合を確認した。
     */
    @Test
    fun `こっくりが一旦収まった後の2回目も発報する`() {
        // 1回目のこっくり(反転を十分に起こす)。
        var t = 0L
        var fired1 = 0
        repeat(8) { half ->
            val target = if (half % 2 == 0) 10f else 0f
            repeat(10) {
                val out = sm.process(
                    DmsInput(t, validForDecision = true, eyesClosed = false, distracted = false,
                        relativePitchDeg = target, eyeOpenRatio = 0.6f),
                )
                if (out.firedAlerts.any { it.type == AlertType.DROWSY_NODDING }) fired1++
                t += 50L
            }
        }
        assertThat(fired1).isAtLeast(1)

        // 頭部を静止させ、監視窓(12秒)+cooldown(10秒)を十分に超えて収める。
        while (t < 40_000L) {
            sm.process(
                DmsInput(t, validForDecision = true, eyesClosed = false, distracted = false,
                    relativePitchDeg = 0f, eyeOpenRatio = 0.6f),
            )
            t += 100L
        }

        // 2回目のこっくり。
        var fired2 = 0
        repeat(8) { half ->
            val target = if (half % 2 == 0) 10f else 0f
            repeat(10) {
                val out = sm.process(
                    DmsInput(t, validForDecision = true, eyesClosed = false, distracted = false,
                        relativePitchDeg = target, eyeOpenRatio = 0.6f),
                )
                if (out.firedAlerts.any { it.type == AlertType.DROWSY_NODDING }) fired2++
                t += 50L
            }
        }
        assertThat(fired2).isAtLeast(1)
    }

    // ---- 開眼こっくり候補(2026-09-11) ----

    /**
     * 候補は本物のこっくりとは**別の型**で出る。呼び出し側はこの型を音に変換しない
     * (`toAlertKind`が対応を持たない)ので、記録だけになる。
     */
    @Test
    fun `目を開けた大きなうなずきは候補の型で発報され本物のこっくりは出ない`() {
        var t = 0L
        val fired = mutableListOf<FiredAlert>()
        // 30°の前後動を4往復、yawは0、目は開いたまま(0.95)。
        repeat(8) { half ->
            val from = if (half % 2 == 0) 0f else 30f
            val to = if (half % 2 == 0) 30f else 0f
            repeat(20) { i ->
                val pitch = from + (to - from) * (i + 1) / 20
                val out = sm.process(
                    DmsInput(
                        t, validForDecision = true, eyesClosed = false, distracted = false,
                        relativePitchDeg = pitch, relativeYawDeg = 0f, eyeOpenRatio = 0.95f,
                    ),
                )
                fired += out.firedAlerts
                t += 50L
            }
        }
        assertThat(fired.map { it.type }).contains(AlertType.DROWSY_NODDING_CANDIDATE)
        assertThat(fired.map { it.type }).doesNotContain(AlertType.DROWSY_NODDING)
        // cooldownで1回にまとまる。
        assertThat(fired.count { it.type == AlertType.DROWSY_NODDING_CANDIDATE }).isEqualTo(1)
    }

    // ---- 高温(3.6) ----

    private fun thermal(monotonicMs: Long, status: Int) = sm.process(
        DmsInput(
            monotonicMs,
            validForDecision = true,
            eyesClosed = false,
            distracted = false,
            thermalStatus = status,
        ),
    )

    /**
     * 11_DAY_SMARTPHONE_MVP_PLAN.md 3.6「高温: 端末が高温です」。
     * **運行中に上がったときだけ**鳴らす(2026-09-09変更。理由は[DmsInput.thermalStatus])。
     */
    @Test
    fun `運行中にSEVEREまで上がると高温警告を発報する`() {
        assertThat(thermal(0L, 1).firedAlerts.map { it.type }).doesNotContain(AlertType.THERMAL)
        assertThat(thermal(100L, 3).firedAlerts.map { it.type }).contains(AlertType.THERMAL)
    }

    /**
     * **これが本題。**Nothing Phone (A142)は端末が冷えていても常に`3`(SEVERE)を返す。
     * 2026-09-09の実車ログでは4運行すべてが開始0秒目にTHERMALを発報していた
     * (利用者の報告:「端末熱くないのに高温警告」)。
     * 最初から高いだけの端末では鳴らさない。
     */
    @Test
    fun `端末が最初から高温を申告しているだけなら鳴らさない`() {
        var fired = 0
        var t = 0L
        while (t <= settings.alertCooldownMs * 3) {
            fired += thermal(t, 3).firedAlerts.count { it.type == AlertType.THERMAL }
            t += 100L
        }
        assertThat(fired).isEqualTo(0)
    }

    /**
     * 最低値は「開始時の値」ではなく「これまでの最低値」。
     * 開始直後だけ高く出てすぐ下がる端末でも、そのあと本当に熱くなれば鳴る。
     */
    @Test
    fun `最初だけ高く出た端末でも下がってから上がれば鳴る`() {
        assertThat(thermal(0L, 3).firedAlerts.map { it.type }).doesNotContain(AlertType.THERMAL)
        assertThat(thermal(100L, 1).firedAlerts.map { it.type }).doesNotContain(AlertType.THERMAL)
        assertThat(thermal(200L, 3).firedAlerts.map { it.type }).contains(AlertType.THERMAL)
    }

    @Test
    fun `thermalがMODERATEでは高温警告を出さない`() {
        assertThat(thermal(0L, 0).firedAlerts.map { it.type }).doesNotContain(AlertType.THERMAL)
        assertThat(thermal(100L, 2).firedAlerts.map { it.type }).doesNotContain(AlertType.THERMAL)
    }

    @Test
    fun `高温が続く間は連続発報せずcooldownで抑制する`() {
        thermal(0L, 0)
        assertThat(thermal(100L, 3).firedAlerts.map { it.type }).contains(AlertType.THERMAL)
        var count = 0
        var t = 200L
        while (t < settings.alertCooldownMs) {
            count += thermal(t, 3).firedAlerts.count { it.type == AlertType.THERMAL }
            t += 100L
        }
        assertThat(count).isEqualTo(0)
    }

    // ---- 欠損(gap許容) ----

    @Test
    fun `UT-011 50ms未満の欠損はタイマーをリセットしない`() {
        step(0L, eyesClosed = true)
        step(20L, valid = false)
        val after = step(50L, eyesClosed = true)
        assertThat(after.eyeClosureDurationMs).isEqualTo(50L)
    }

    @Test
    fun `UT-012 300ms以内の欠損はタイマーを保持する`() {
        step(0L, eyesClosed = true)
        step(150L, valid = false)
        val after = step(300L, eyesClosed = true) // 直近trueからの間隔ちょうど300ms
        assertThat(after.eyeClosureDurationMs).isEqualTo(300L)
    }

    @Test
    fun `UT-013 猶予を超える欠損はタイマーを中断(0へリセット)する`() {
        // 猶予はsettings.qualityGapGraceMs(既定500ms)。既定値を変えてもテストが壊れないよう参照する。
        val gap = settings.qualityGapGraceMs
        step(0L, eyesClosed = true)
        step(gap / 2, valid = false)
        val after = step(gap + 1, eyesClosed = true) // 直近trueからの間隔が猶予超過
        assertThat(after.eyeClosureDurationMs).isEqualTo(0L)
    }

    // ---- cooldown ----

    @Test
    fun `UT-014 同種警告後クールダウン中は再警告なし`() {
        val gap = settings.qualityGapGraceMs
        // episode 1: 1500msでCAUTION発報。
        feedContinuous(1500L, eyesClosed = true)
        // 目を開けて猶予超過させ、episodeを終了させる。
        step(1500L + gap / 2, eyesClosed = false)
        step(1500L + gap + 100L, eyesClosed = false)

        // episode 2: cooldown(1500 + alertCooldownMs)が明ける前にCAUTION相当へ到達させる。
        val start = 1500L + gap + 200L
        val end = start + settings.eyeClosureCautionMs
        check(end < 1500L + settings.alertCooldownMs) { "テスト前提: episode2はcooldown中に収まること" }
        val outputs = feedContinuous(end, fromMs = start, eyesClosed = true)

        assertThat(outputs.flatMap { it.firedAlerts }).isEmpty()
    }

    @Test
    fun `UT-015 クールダウン中でも重大度の昇格(強い警告)は発報を許可する`() {
        val gap = settings.qualityGapGraceMs
        val firstEpisode = feedContinuous(1500L, eyesClosed = true)
        assertThat(firstEpisode.flatMap { it.firedAlerts })
            .containsExactly(FiredAlert(AlertType.EYE_CLOSURE, AlertSeverity.CAUTION, 1500L))

        step(1500L + gap / 2, eyesClosed = false)
        step(1500L + gap + 100L, eyesClosed = false) // 猶予超過でepisodeをreset

        // episode 2: WARNING相当まで継続させる。cooldown中でも昇格は許可されるはず。
        val start = 1500L + gap + 200L
        val end = start + settings.eyeClosureWarningMs
        check(end < 1500L + settings.alertCooldownMs) { "テスト前提: episode2はcooldown中に収まること" }
        val secondEpisode = feedContinuous(end, fromMs = start, eyesClosed = true)

        assertThat(secondEpisode.flatMap { it.firedAlerts })
            .containsExactly(FiredAlert(AlertType.EYE_CLOSURE, AlertSeverity.WARNING, end))
    }

    // ---- 監視不能 ----

    @Test
    fun `品質無効が2秒でLIMITEDへ遷移する`() {
        val outputs = feedContinuous(2000L, valid = false)
        assertThat(outputs.last().availability).isEqualTo(MonitoringAvailability.LIMITED)
    }

    @Test
    fun `品質無効が5秒で監視不能通知が一度だけ発報されUNAVAILABLEになる`() {
        val outputs = feedContinuous(6000L, valid = false)
        val noticeCount = outputs.count { it.unavailableNoticeFired }
        assertThat(noticeCount).isEqualTo(1)
        assertThat(outputs.last().availability).isEqualTo(MonitoringAvailability.UNAVAILABLE)
    }

    @Test
    fun `品質が1秒連続して回復するとNORMALへ自動復帰し次回また通知できる`() {
        feedContinuous(5000L, valid = false) // UNAVAILABLEへ、通知済み
        // feedContinuousの第1引数は絶対時刻(toMs)。5100msから1000ms分=6100msまで有効フレームを流す。
        val recovered = feedContinuous(6100L, fromMs = 5100L, valid = true)
        assertThat(recovered.last().availability).isEqualTo(MonitoringAvailability.NORMAL)

        // 再度5秒品質無効が続けば、通知フラグがリセットされているので再度一度だけ通知される。
        val secondOutage = feedContinuous(11200L, fromMs = 6200L, valid = false)
        assertThat(secondOutage.count { it.unavailableNoticeFired }).isEqualTo(1)
    }

    // ---- 速度ゲート(2026-08-31追加) ----

    /**
     * 速度が下限未満の間は脇見を判定しない。信号待ち・休憩・積卸し中の誤報を消すのが目的。
     * ASV脇見編2.2.3は閾値を規定せず(メーカー設定)、2.5は商用車特有の運行に伴う視認行動を
     * 除外要件として認めているため、この挙動は国内基準の範囲内である。
     */
    @Test
    fun `速度が下限未満なら脇見警告は出ない`() {
        val outputs = feedContinuous(
            10_000L,
            distracted = true,
            speedKph = settings.distractionMinSpeedKph - 1f,
        )
        assertThat(outputs.flatMap { it.firedAlerts }).isEmpty()
        assertThat(outputs.last().distractionSpeedGateOpen).isFalse()
    }

    /**
     * 速度ゲートが止めるのは警告だけで、脇見の継続時間は速度に関係なく数える
     * (2026-10-01、docs/21 R-24、UN R183 5.5.2.3)。
     */
    @Test
    fun `速度が下限未満でも脇見の継続時間は数え警告は出さない`() {
        val outputs = feedContinuous(
            10_000L,
            distracted = true,
            speedKph = settings.distractionMinSpeedKph - 1f,
        )
        // 警告は出ない(ゲートが閉じている)。
        assertThat(outputs.flatMap { it.firedAlerts }).isEmpty()
        assertThat(outputs.last().distractionDurationMs).isAtLeast(9_000L)
        assertThat(outputs.last().distractionRawDurationMs).isAtLeast(9_000L)
    }

    /** 停車中から見続けたまま発進したら、速度が下限を越えた最初のフレームで鳴る(利用者の決定)。 */
    @Test
    fun `停車中から脇見を続けたまま発進したら下限を越えた瞬間に鳴る`() {
        val stopped = feedContinuous(8_000L, distracted = true, speedKph = 0f)
        val moving = feedContinuous(
            9_000L,
            fromMs = 8_100L,
            distracted = true,
            speedKph = settings.distractionMinSpeedKph,
        )

        assertThat(stopped.flatMap { it.firedAlerts }).isEmpty()
        assertThat(moving.flatMap { it.firedAlerts })
            .containsExactly(FiredAlert(AlertType.DISTRACTION, AlertSeverity.WARNING, 8_100L))
    }

    /** 品質無効のフレームは記録用の継続時間にも加算しない(顔が写っていない時間を脇見と呼ばない)。 */
    @Test
    fun `品質無効なら記録用の脇見継続時間も伸びない`() {
        val outputs = feedContinuous(10_000L, distracted = true, valid = false, speedKph = 0f)
        assertThat(outputs.last().distractionRawDurationMs).isEqualTo(0L)
    }

    /** あくびは10分3回に届かなくても、1回ごとの成立を記録できる必要がある(診断ログ用)。 */
    @Test
    fun `あくび1回でもyawnCompletedが立つ`() {
        val outputs = feedContinuous(
            settings.yawnMinDurationMs + 300L,
            stepMs = 50L,
            jawOpen = 0.85f,
        )
        assertThat(outputs.count { it.yawn.yawnCompleted }).isEqualTo(1)
        assertThat(outputs.last().yawn.isFrequent).isFalse()
    }

    @Test
    fun `速度が下限以上なら脇見警告が出る`() {
        val outputs = feedContinuous(
            6000L,
            distracted = true,
            speedKph = settings.distractionMinSpeedKph,
        )
        assertThat(outputs.flatMap { it.firedAlerts })
            .containsExactly(FiredAlert(AlertType.DISTRACTION, AlertSeverity.WARNING, 6000L))
    }

    /**
     * **速度別の脇見閾値**(docs/16 §2 P1-1)。
     *
     * EU委任規則 ADDW ((EU) 2023/2590) は**50km/h超で3.5秒**を求めている。
     * 実車では60〜80km/hで走る時間が長いので、ここが6秒のままだと
     * 高速域でいちばん危ない脇見に対して2.5秒遅れる。
     */
    @Test
    fun `50km_h超なら3_5秒で脇見警告が出る`() {
        val outputs = feedContinuous(3500L, distracted = true, speedKph = 60f)
        assertThat(outputs.flatMap { it.firedAlerts })
            .containsExactly(FiredAlert(AlertType.DISTRACTION, AlertSeverity.WARNING, 3500L))
    }

    /** 50km/h以下は従来どおり6秒。3.5秒では鳴らない。 */
    @Test
    fun `50km_h以下は3_5秒では鳴らない`() {
        val outputs = feedContinuous(3500L, distracted = true, speedKph = 40f)
        assertThat(outputs.flatMap { it.firedAlerts }).isEmpty()
    }

    /**
     * **速度が分からないときは厳しい方(3.5秒)ではなく緩い方(6秒)を使う。**
     * 分からないのに短い方で鳴らすと、停車中の誤報が増えるため。
     */
    @Test
    fun `速度が不明なら3_5秒では鳴らない`() {
        val outputs = feedContinuous(3500L, distracted = true, speedKph = null)
        assertThat(outputs.flatMap { it.firedAlerts }).isEmpty()
    }

    /**
     * 速度が取得できない間(測位前・トンネル・位置情報権限の拒否)はゲートを開いたままにし、
     * 監視を止めない。安全装置として「速度が分からないから監視しない」より安全側であるため。
     */
    @Test
    fun `速度が不明ならゲートは開いたままで従来どおり警告する`() {
        val outputs = feedContinuous(6000L, distracted = true, speedKph = null)
        assertThat(outputs.flatMap { it.firedAlerts })
            .containsExactly(FiredAlert(AlertType.DISTRACTION, AlertSeverity.WARNING, 6000L))
        assertThat(outputs.last().distractionSpeedGateOpen).isTrue()
    }

    /**
     * 動作確認モード: 停車中(速度0)でも脇見を判定する。机上や停車状態で検知の動きを
     * 確かめるためのスイッチで、製品運用ではOFFにする。
     */
    @Test
    fun `動作確認モードなら速度が下限未満でも脇見警告が出る`() {
        val testSettings = settings.copy(testModeIgnoreSpeedGate = true)
        val testSm = DayDmsStateMachine(testSettings)
        val fired = mutableListOf<FiredAlert>()
        var t = 0L
        var last: DmsOutput? = null
        while (t <= 6000L) {
            val out = testSm.process(
                DmsInput(
                    monotonicMs = t,
                    validForDecision = true,
                    eyesClosed = false,
                    distracted = true,
                    speedKph = 0f,
                ),
            )
            fired += out.firedAlerts
            last = out
            t += 100L
        }

        assertThat(fired.map { it.type }).contains(AlertType.DISTRACTION)
        assertThat(last?.distractionSpeedGateOpen).isTrue()
    }

    /** 眠気系には速度ゲートを掛けない。ASV眠気編は速度に一切触れておらず、停車中の居眠りも報知すべきため。 */
    @Test
    fun `停車中でも閉眼警告は出る`() {
        val outputs = feedContinuous(3000L, eyesClosed = true, speedKph = 0f)
        assertThat(outputs.flatMap { it.firedAlerts }.map { it.type })
            .contains(AlertType.EYE_CLOSURE)
    }

    // ---- あくび(2026-08-31追加) ----

    /** 1回のあくびでは発報しない。ASV眠気2.2解説はD4の特徴を「あくびが**頻発**」としているため。 */
    @Test
    fun `あくび1回では発報しない`() {
        val outputs = feedContinuous(
            settings.yawnMinDurationMs + 500L,
            stepMs = 50L,
            jawOpen = 0.85f,
        )
        assertThat(outputs.flatMap { it.firedAlerts }.map { it.type })
            .doesNotContain(AlertType.DROWSY_YAWNING)
    }

    @Test
    fun `窓内で規定回数のあくびが揃うと発報する`() {
        val fired = mutableListOf<FiredAlert>()
        var t = 0L
        repeat(settings.yawnMinCountInWindow) {
            // 口を開ける
            val openEnd = t + settings.yawnMinDurationMs + 300L
            while (t <= openEnd) {
                fired += step(t, jawOpen = 0.85f).firedAlerts
                t += 50L
            }
            // 閉じる(episodeを終わらせる)
            val closeEnd = t + 1_000L
            while (t <= closeEnd) {
                fired += step(t, jawOpen = 0.05f).firedAlerts
                t += 50L
            }
        }

        assertThat(fired.map { it.type }).contains(AlertType.DROWSY_YAWNING)
        assertThat(fired.filter { it.type == AlertType.DROWSY_YAWNING }.map { it.severity })
            .containsExactly(AlertSeverity.CAUTION)
    }

    /** 品質無効のフレームはあくびの継続時間へ加算しない(閉眼・こっくりと同じ扱い)。 */
    @Test
    fun `品質無効の間はあくびが成立しない`() {
        val outputs = feedContinuous(
            settings.yawnMinDurationMs * 3,
            stepMs = 50L,
            valid = false,
            jawOpen = 0.85f,
        )
        assertThat(outputs.last().yawn.countInWindow).isEqualTo(0)
    }
    /**
     * 2026-09-08回帰: **3回目で鳴ったあと、4回目以降が鳴らなかった。**
     *
     * 「頻発」は10分の窓に3回以上という*状態*なので、一度立つと窓が空くまで下がらない。
     * episodeの解除を「頻発でなくなったとき」だけにしていたため、
     * 実測(session_354db919)ではあくび10回に対して発報が2回しかなかった。
     * ASV基本設計書のD4は「あくびが頻発」で、続いている間は知らせ続けるのが筋。
     */
    @Test
    fun `あくびは4回目以降も知らせ直す`() {
        val fired = mutableListOf<Long>()
        var t = 0L
        // 1回のあくび = jawOpen>=閾値 を yawnMinDurationMs 以上続けてから閉じる。
        fun yawn() {
            val until = t + settings.yawnMinDurationMs + 400L
            while (t <= until) {
                step(monotonicMs = t, jawOpen = 0.9f).firedAlerts
                    .filter { it.type == AlertType.DROWSY_YAWNING }
                    .forEach { fired += t }
                t += 100L
            }
            // 口を閉じてepisodeを終わらせ、cooldownが明けるまで間を空ける。
            // 実際のあくびは数分おきなので、連発抑制(alertCooldownMs)には掛からない。
            val closeUntil = t + settings.alertCooldownMs + 2_000L
            while (t <= closeUntil) {
                step(monotonicMs = t, jawOpen = 0.05f).firedAlerts
                    .filter { it.type == AlertType.DROWSY_YAWNING }
                    .forEach { fired += t }
                t += 100L
            }
        }
        repeat(6) { yawn() }
        // 3回目で頻発が立って以降、成立のたびに知らせ直せること(3・4・5・6回目)。
        // 修正前はここが1回で止まっていた。
        assertThat(fired.size).isAtLeast(4)
    }

    // ---- 顔を見失った間の脇見・監視不能(2026-09-27、docs/18 §14-20) ----

    /** 相対yawを指定して1フレーム進める。見失ったフレームはvalid=false・yaw=null。 */
    private fun look(t: Long, yaw: Float?, speedKph: Float? = null) = sm.process(
        DmsInput(
            monotonicMs = t,
            validForDecision = yaw != null,
            eyesClosed = false,
            distracted = yaw != null && kotlin.math.abs(yaw) >= settings.distractionYawDeg,
            relativeYawDeg = yaw,
            speedKph = speedKph,
        ),
    )

    private fun lookRange(fromMs: Long, toMs: Long, yaw: Float?, speedKph: Float? = null): List<DmsOutput> =
        (fromMs..toMs step 100L).map { look(it, yaw, speedKph) }

    /**
     * 机上試験(マスク着用)の再現: 横を向き始めて顔を見失っても、脇見として6秒で警告する。
     * 見失っている間は監視不能を先に鳴らさない。
     */
    @Test
    fun `横を向いて顔を見失っても見失い中を脇見として数えて警告する`() {
        val outputs = lookRange(0L, 500L, 30f) + lookRange(600L, 7000L, null)

        assertThat(outputs.flatMap { it.firedAlerts }.map { it.type }).contains(AlertType.DISTRACTION)
        assertThat(outputs.none { it.unavailableNoticeFired }).isTrue()
        assertThat(outputs.last().distractionCarrying).isTrue()
    }

    @Test
    fun `見失う直前の向きが浅ければ見失いは脇見にせず監視不能にする`() {
        val shallow = settings.distractionCarryEntryYawDeg - 1f
        val outputs = lookRange(0L, 500L, shallow) + lookRange(600L, 7000L, null)

        assertThat(outputs.flatMap { it.firedAlerts }.none { it.type == AlertType.DISTRACTION }).isTrue()
        assertThat(outputs.count { it.unavailableNoticeFired }).isEqualTo(1)
    }

    /** 上限を超えたら見失った時点から数えた監視不能になる(=超えた瞬間に鳴る)。 */
    @Test
    fun `見失いを脇見として数える上限を超えたら監視不能を鳴らす`() {
        lookRange(0L, 500L, 30f)
        val lostAt = 600L
        val carryEnd = lostAt + settings.distractionCarryMaxMs
        val times = (lostAt..carryEnd + 500L step 100L).toList()
        val noticeTimes = times.filter { look(it, null).unavailableNoticeFired }

        // 上限までは鳴らさず、超えた最初のフレームで鳴る(無効の起点は見失った時点なので5秒を待たない)。
        assertThat(noticeTimes).containsExactly(carryEnd + 100L)
    }

    /** 机上試験の1回目: 横を向いている最中の誤検出1枚で監視不能の計測が戻り、9秒見失っても無音だった。 */
    @Test
    fun `一瞬だけ有効フレームが来ても監視不能までの計測を戻さない`() {
        val outputs = feedContinuous(3000L, valid = false) +
            listOf(step(3100L, valid = true)) +
            feedContinuous(5500L, fromMs = 3200L, valid = false)

        assertThat(outputs.count { it.unavailableNoticeFired }).isEqualTo(1)
    }

    @Test
    fun `品質欠損の許容時間以上回復したら監視不能までの計測を戻す`() {
        val recoverEnd = 3100L + settings.qualityGapGraceMs
        val outputs = feedContinuous(3000L, valid = false) +
            feedContinuous(recoverEnd, fromMs = 3100L, valid = true) +
            feedContinuous(recoverEnd + settings.unavailableWarningMs - 200L, fromMs = recoverEnd + 100L, valid = false)

        assertThat(outputs.none { it.unavailableNoticeFired }).isTrue()
    }

    /** 机上試験の4回目: 監視不能が続いたまま2回目のよそ見に入り、無音だった。 */
    @Test
    fun `監視不能が続く間は間隔を空けて通知を繰り返し繰り返しは印を付ける`() {
        val outputs = feedContinuous(settings.unavailableWarningMs + settings.unavailableRepeatMs + 100L, valid = false)
        val notices = outputs.filter { it.unavailableNoticeFired }

        assertThat(notices.size).isEqualTo(2)
        assertThat(notices[0].unavailableNoticeIsRepeat).isFalse()
        assertThat(notices[1].unavailableNoticeIsRepeat).isTrue()
    }

    /**
     * 停車中(速度ゲートが閉じている)は見失いを脇見として鳴らせない。そこで監視不能まで止めると
     * 何も鳴らない時間ができる(9/25実車の停車中の見失いで、試算上その状態になった)。
     */
    @Test
    fun `停車中は横を向いて見失っても監視不能を通常どおり鳴らす`() {
        val outputs = lookRange(0L, 500L, 30f, speedKph = 0f) + lookRange(600L, 6000L, null, speedKph = 0f)

        assertThat(outputs.count { it.unavailableNoticeFired }).isEqualTo(1)
        assertThat(outputs.flatMap { it.firedAlerts }.none { it.type == AlertType.DISTRACTION }).isTrue()
    }

    // ---- 脇見の方向ごとのcooldown(2026-09-27、docs/18 §14-21) ----

    /** 相対yaw/pitchを指定して刻む。脇見の判定は角度から作る(DmsInputMapperと同じ条件)。 */
    private fun gaze(fromMs: Long, toMs: Long, yaw: Float, pitch: Float = 0f): List<Pair<Long, DmsOutput>> =
        (fromMs..toMs step 100L).map { t ->
            t to sm.process(
                DmsInput(
                    monotonicMs = t,
                    validForDecision = true,
                    eyesClosed = false,
                    distracted = kotlin.math.abs(yaw) >= settings.distractionYawDeg ||
                        kotlin.math.abs(pitch) >= settings.distractionPitchDownDeg,
                    relativeYawDeg = yaw,
                    relativePitchDeg = pitch,
                ),
            )
        }

    private fun List<Pair<Long, DmsOutput>>.distractionTimes() =
        filter { (_, o) -> o.firedAlerts.any { it.type == AlertType.DISTRACTION } }.map { it.first }

    private val warnMs get() = settings.distractionWarningMs

    @Test
    fun `右で鳴った後のcooldown中でも左の脇見は鳴る`() {
        val right = gaze(0L, warnMs + 500L, 30f)
        val front = gaze(warnMs + 600L, warnMs + 2000L, 0f)
        val leftStart = warnMs + 2100L
        val left = gaze(leftStart, leftStart + warnMs + 100L, -30f)

        assertThat(right.distractionTimes()).containsExactly(warnMs)
        assertThat(front.distractionTimes()).isEmpty()
        // 右のcooldown(alertCooldownMs)が明けるのを待たず、左が基準時間に達した時点で鳴る。
        assertThat(leftStart + warnMs).isLessThan(warnMs + settings.alertCooldownMs)
        assertThat(left.distractionTimes()).containsExactly(leftStart + warnMs)
    }

    @Test
    fun `右で鳴った後のcooldown中の右は鳴らない`() {
        gaze(0L, warnMs + 500L, 30f)
        gaze(warnMs + 600L, warnMs + 2000L, 0f)
        val again = gaze(warnMs + 2100L, warnMs + settings.alertCooldownMs - 100L, 30f)

        assertThat(again.distractionTimes()).isEmpty()
    }

    /** 方向ごとに継続時間を数えると、ここが永久に鳴らなくなる。合計で数えていることの確認。 */
    @Test
    fun `正面に戻らず左右を見回し続けても合計の時間で鳴る`() {
        val outputs = (0L..warnMs + 500L step 100L).map { t ->
            val yaw = if ((t / 2000L) % 2L == 0L) 30f else -30f
            t to gaze(t, t, yaw).single().second
        }

        assertThat(outputs.distractionTimes()).hasSize(1)
    }

    /** 右で鳴った後、正面に戻らず左へ振り向けた。振り向いた瞬間ではなく、左が単独で基準時間続いたら鳴る。 */
    @Test
    fun `同じ脇見の中で方向を変えたら新しい方向が単独で基準時間続いたときに鳴る`() {
        gaze(0L, warnMs + 500L, 30f)
        val switchAt = warnMs + 600L
        val left = gaze(switchAt, switchAt + warnMs + 100L, -30f)

        assertThat(left.distractionTimes()).containsExactly(switchAt + warnMs)
    }

    @Test
    fun `上下の脇見は左右とは別の方向として扱う`() {
        gaze(0L, warnMs + 500L, 30f)
        gaze(warnMs + 600L, warnMs + 2000L, 0f)
        val downStart = warnMs + 2100L
        val down = gaze(downStart, downStart + warnMs + 100L, 0f, pitch = 25f)

        assertThat(down.distractionTimes()).containsExactly(downStart + warnMs)
        assertThat(down.last().second.distractionDirection).isEqualTo(DistractionDirection.PITCH_PLUS)
    }

    // ---- 手元・側方と、下を向いて見失ったとき(2026-10-01、docs/21 R-21) ----

    /** 相対yaw/pitchを指定して1フレーム進める。見失ったフレームはyaw・pitchともnull。 */
    private fun pose(t: Long, yaw: Float?, pitch: Float?, speedKph: Float? = null) = sm.process(
        DmsInput(
            monotonicMs = t,
            validForDecision = yaw != null || pitch != null,
            eyesClosed = false,
            distracted = (yaw != null && kotlin.math.abs(yaw) >= settings.distractionYawDeg) ||
                (pitch != null && kotlin.math.abs(pitch) >= settings.distractionPitchDownDeg),
            relativeYawDeg = yaw,
            relativePitchDeg = pitch,
            speedKph = speedKph,
        ),
    )

    private fun poseRange(fromMs: Long, toMs: Long, yaw: Float?, pitch: Float?, speedKph: Float? = null) =
        (fromMs..toMs step 100L).map { pose(it, yaw, pitch, speedKph) }

    /** 膝・足元を見て顔を見失った。従来はyawだけで判定していたため監視不能になっていた。 */
    @Test
    fun `下を向いて顔を見失っても見失い中を脇見として数えて警告する`() {
        val down = settings.distractionCarryEntryPitchDownDeg + 2f
        val outputs = poseRange(0L, 500L, 0f, down) + poseRange(600L, 7_000L, null, null)

        assertThat(outputs.flatMap { it.firedAlerts }.map { it.type }).contains(AlertType.DISTRACTION)
        assertThat(outputs.none { it.unavailableNoticeFired }).isTrue()
        assertThat(outputs.last().distractionCarrying).isTrue()
        assertThat(outputs.last().distractionDirection).isEqualTo(DistractionDirection.PITCH_PLUS)
        assertThat(outputs.last().distractionZone).isEqualTo(DistractionZone.HAND)
    }

    @Test
    fun `見失う直前の下向きが浅ければ見失いは脇見にせず監視不能にする`() {
        val shallow = settings.distractionCarryEntryPitchDownDeg - 1f
        val outputs = poseRange(0L, 500L, 0f, shallow) + poseRange(600L, 7_000L, null, null)

        assertThat(outputs.flatMap { it.firedAlerts }.none { it.type == AlertType.DISTRACTION }).isTrue()
        assertThat(outputs.count { it.unavailableNoticeFired }).isEqualTo(1)
    }

    /** 上を向いて見失うのは手元の脇見ではない(下向きだけを見る)。 */
    @Test
    fun `上を向いて見失っても見失いは脇見にしない`() {
        val up = -(settings.distractionCarryEntryPitchDownDeg + 2f)
        val outputs = poseRange(0L, 500L, 0f, up) + poseRange(600L, 7_000L, null, null)

        assertThat(outputs.count { it.unavailableNoticeFired }).isEqualTo(1)
    }

    /** 右ハンドル車のナビ・シフト: 横向きの脇見の角度でも、やや下向きなら手元に分類する。 */
    @Test
    fun `斜め下の脇見は手元に分類し真横と上は側方に分類する`() {
        val diagonal = pose(0L, -30f, settings.distractionHandMinPitchDownDeg)
        sm.reset()
        val side = pose(0L, 30f, 0f)
        sm.reset()
        val up = pose(0L, 0f, -25f)

        assertThat(diagonal.distractionZone).isEqualTo(DistractionZone.HAND)
        assertThat(side.distractionZone).isEqualTo(DistractionZone.SIDE)
        assertThat(up.distractionZone).isEqualTo(DistractionZone.SIDE)
    }

    @Test
    fun `側方を数えない設定では側方の脇見は鳴らさず手元は鳴らす`() {
        sm = DayDmsStateMachine(settings.copy(distractionSideEnabled = false))
        val side = poseRange(0L, warnMs + 500L, 30f, 0f)
        val front = poseRange(warnMs + 600L, warnMs + 2_000L, 0f, 0f)
        val handStart = warnMs + 2_100L
        val hand = poseRange(handStart, handStart + warnMs + 100L, -30f, 15f)

        assertThat(side.flatMap { it.firedAlerts }).isEmpty()
        assertThat(side.last().distractionDurationMs).isEqualTo(0L)
        // 記録用は側方も数える。
        assertThat(side.last().distractionRawDurationMs).isAtLeast(warnMs)
        assertThat(front.flatMap { it.firedAlerts }).isEmpty()
        assertThat(hand.flatMap { it.firedAlerts }.map { it.type }).containsExactly(AlertType.DISTRACTION)
    }

    /** 数えない側方の見失いで監視不能まで止めると、何も鳴らない時間ができる。 */
    @Test
    fun `側方を数えない設定では横を向いて見失ったら監視不能を鳴らす`() {
        sm = DayDmsStateMachine(settings.copy(distractionSideEnabled = false))
        val outputs = poseRange(0L, 500L, 30f, 0f) + poseRange(600L, 6_000L, null, null)

        assertThat(outputs.count { it.unavailableNoticeFired }).isEqualTo(1)
        assertThat(outputs.flatMap { it.firedAlerts }.none { it.type == AlertType.DISTRACTION }).isTrue()
    }

    // ---- 警告を待っている間(一度 20km/h を超えるまで。公開前レビュー R-03) ----

    /** [alertsHeld]だけを足した1フレーム。 */
    private fun held(
        monotonicMs: Long,
        alertsHeld: Boolean,
        valid: Boolean = true,
        eyesClosed: Boolean = false,
        distracted: Boolean = false,
        speedKph: Float? = null,
        thermalStatus: Int = 0,
    ) = sm.process(
        DmsInput(
            monotonicMs = monotonicMs,
            validForDecision = valid,
            eyesClosed = eyesClosed,
            distracted = distracted,
            speedKph = speedKph,
            thermalStatus = thermalStatus,
            alertsHeld = alertsHeld,
        ),
    )

    /** レビューの再現: 15km/hで脇見を始め、7秒後に25km/hへ加速、脇見は続けたまま。 */
    @Test
    fun `待っている間に続いた脇見は警告を始めた時点ですぐ鳴る`() {
        var t = 0L
        while (t <= 7_000L) {
            assertThat(held(t, alertsHeld = true, distracted = true, speedKph = 15f).firedAlerts).isEmpty()
            t += 100L
        }
        val out = held(7_100L, alertsHeld = false, distracted = true, speedKph = 25f)
        assertThat(out.firedAlerts.map { it.type }).containsExactly(AlertType.DISTRACTION)
    }

    @Test
    fun `待っている間に続いた閉眼は警告を始めた時点の重さで鳴る`() {
        var t = 0L
        while (t <= 3_000L) {
            assertThat(held(t, alertsHeld = true, eyesClosed = true).firedAlerts).isEmpty()
            t += 100L
        }
        val fired = held(3_100L, alertsHeld = false, eyesClosed = true).firedAlerts
        assertThat(fired.map { it.type to it.severity })
            .containsExactly(AlertType.EYE_CLOSURE to AlertSeverity.WARNING)
    }

    @Test
    fun `待っている間に続いた監視不能は警告を始めた時点で鳴る`() {
        var t = 0L
        while (t <= 6_000L) {
            assertThat(held(t, alertsHeld = true, valid = false).unavailableNoticeFired).isFalse()
            t += 100L
        }
        val out = held(6_100L, alertsHeld = false, valid = false)
        assertThat(out.unavailableNoticeFired).isTrue()
        assertThat(out.unavailableNoticeIsRepeat).isFalse()
    }

    @Test
    fun `待っている間に収まった危険状態は警告を始めても鳴らさない`() {
        var t = 0L
        while (t <= 7_000L) {
            held(t, alertsHeld = true, distracted = true, speedKph = 15f)
            t += 100L
        }
        // 正面に戻ってから(猶予を超えて)警告を始める。
        held(7_100L, alertsHeld = true, speedKph = 15f)
        held(8_000L, alertsHeld = true, speedKph = 15f)
        assertThat(held(8_100L, alertsHeld = false, speedKph = 25f).firedAlerts).isEmpty()
    }

    @Test
    fun `待っている間も高温は鳴らす`() {
        held(0L, alertsHeld = true, thermalStatus = 1)
        assertThat(held(100L, alertsHeld = true, thermalStatus = 3).firedAlerts.map { it.type })
            .containsExactly(AlertType.THERMAL)
    }
}
