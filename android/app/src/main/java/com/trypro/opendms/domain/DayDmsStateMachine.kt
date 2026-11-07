package com.trypro.opendms.domain

import kotlin.math.abs

/**
 * [DayDmsStateMachine]への入力。`FeatureFrame`から較正基準を使って算出する、センサー非依存の型。
 * (11_DAY_SMARTPHONE_MVP_PLAN.md 3.5の判定はFeatureFrameの生値でなくこの型を経由させる)
 */
data class DmsInput(
    val monotonicMs: Long,
    /** [SignalQuality.isValidForDecision]。falseのフレームは閉眼・脇見の継続時間へ加算しない(VIS-005)。 */
    val validForDecision: Boolean,
    /** 左右の正規化開眼比の平均が閾値以下(3.5: 0.45以下)。 */
    val eyesClosed: Boolean,
    /** 較正基準に対する相対頭部角度が脇見条件(3.5: abs(yaw)>=25° または pitch下方向>=20°)を満たす。 */
    val distracted: Boolean,
    /**
     * 開眼度(MediaPipeのeyeBlink blendshape)が信頼できるフレームか。
     *
     * 2026-08-29実機検証: 頭部が速く回転している間、blendshapeの開眼度が実際の
     * まぶたの状態と乖離する(閉眼中でも開眼寄りに振れる)。一方で頭部角度そのものは
     * 回転中でも安定して取得できているため、この不信頼性は**顔の表情特徴を使う判定だけ**
     * (閉眼・こっくり・あくび)に適用し、脇見判定には適用しない
     * (脇見は頭部角度のみに依存するため)。
     *
     * 当初これを`validForDecision`に含めていたが、それでは首を振る動作の最中が丸ごと
     * 脇見判定から除外され、脇見の継続時間が全く伸びない不具合になっていた
     * (診断ログsession_43a993fcで脇見最大継続0msを確認)。
     *
     * **2026-09-01: 同じ不具合が`ONE_EYE_ONLY`と`HEAD_ANGLE_TOO_LARGE`にも残っていた。**
     * この2つはqualityScoreを下げて`validForDecision`をfalseにしていたため、
     * 顔を30°以上向けた瞬間に脇見のタイマーが止まっていた
     * (session_b4ce5f53で脇見候補6区間・最長4.1秒に対し判定可フレーム0)。
     * 3つとも「顔が正面を向いていない」という同じ事象なので、扱いを揃えた。
     */
    val facialSignalReliable: Boolean = true,
    /**
     * `android.os.PowerManager`の`THERMAL_STATUS_*`。Android非依存を保つためIntのまま受け取る。
     *
     * **絶対値では判定しない。**Nothing Phone (A142)は端末が冷えていても
     * (電池32℃・USB給電中)常に`3`(SEVERE)を返す。2026-09-09の実車ログでは
     * 運行開始0秒目からSEVEREで、走ったあとむしろ1へ下がっていた。
     * 「端末が熱くないのに高温警告が出る」の原因はここで、アプリではなく端末の申告である。
     *
     * そこで[DayDmsStateMachine]は**運行中の最低値からの上昇**で判定する
     * (11_DAY_SMARTPHONE_MVP_PLAN.md 3.6)。
     */
    val thermalStatus: Int = 0,
    /** 較正基準に対する相対頭部pitch(度)。[NoddingDetector]の入力。取得できなければnull。 */
    val relativePitchDeg: Float? = null,
    /** 較正基準に対する相対頭部yaw(度)。開眼こっくり候補の除外条件に使う(2026-09-11)。 */
    val relativeYawDeg: Float? = null,
    /** 左右の正規化開眼比の平均(1.0=較正時の開眼相当)。[NoddingDetector]の入力。 */
    val eyeOpenRatio: Float? = null,
    /**
     * blendshape `jawOpen`の生値(0..1)。[YawnDetector]の入力。較正基準を使わない絶対値。
     * 顔未検出・品質無効ならnull。
     */
    val jawOpen: Float? = null,
    /**
     * 現在の車速(km/h)。**取得できない間(測位前・トンネル・位置情報権限の拒否)はnull**。
     *
     * nullのときは速度ゲートを開いたまま扱う(=脇見・スマホの判定を止めない)。安全装置として
     * 「速度が分からないから監視しない」よりも「監視する」方が安全側であるため。
     * 詳細は[DayMvpSettings.distractionMinSpeedKph]のKDoc。
     */
    val speedKph: Float? = null,
    /**
     * 警告を待っている間か([MonitoringArming]。一度20km/hを超えるまで)。
     *
     * trueの間は高温以外の発報と監視不能の通知を出さず、**出したことにもしない**。継続時間は数え続けるので、
     * 待ち終わった時点で危険状態が続いていれば次のフレームで鳴る(公開前レビュー R-03)。
     */
    val alertsHeld: Boolean = false,
)

enum class AlertType {
    EYE_CLOSURE, DISTRACTION, DROWSY_NODDING, DROWSY_YAWNING, THERMAL, SYSTEM_FAULT,

    /**
     * 開眼こっくり候補(2026-09-11)。**音も画面も出さない。記録だけ。**
     * 条件は[DayMvpSettings.noddingCandidateMinPitchRangeDeg]。
     * 実走で覚醒中に何回出るかを測るためのもので、鳴らす判断はその数字を見てから。
     */
    DROWSY_NODDING_CANDIDATE,
}
enum class AlertSeverity { NONE, CAUTION, WARNING }

/**
 * 脇見の方向(2026-09-27)。**cooldownを方向ごとに持つためだけに使う**(継続時間は全方向の合計)。
 *
 * 名前は較正基準に対する角度の符号で付ける。上下は前面カメラの実測で
 * [PITCH_PLUS]=下向き・[PITCH_MINUS]=上向き(2026-09-27、docs/18 §14-21)。
 * 左右と符号の対応は前面カメラの鏡像・取付回転で変わりうるので、左右の名前は付けない。
 * 同じカメラの中では一貫するので、方向を区別する目的には支障ない。
 */
enum class DistractionDirection {
    YAW_PLUS, YAW_MINUS, PITCH_PLUS, PITCH_MINUS,

    /** 角度が取れない(試験用の入力など)。1つの方向として扱う。 */
    UNKNOWN,
}

/**
 * 脇見の領域(2026-10-01、docs/21 R-21)。UN R183 が警報の対象にするのは手元(Area 3)だけ。
 * 標準は両方を数え、[DayMvpSettings.distractionSideEnabled]がfalseなら[HAND]だけを数える。
 */
enum class DistractionZone {
    /** 手元(R183 Area 3 相当)。下向きで、左右[DayMvpSettings.distractionHandMaxYawDeg]以内。 */
    HAND,

    /** 手元以外(側方・上方)。ミラー確認・振り向きなど。 */
    SIDE,
}
enum class MonitoringAvailability { NORMAL, LIMITED, UNAVAILABLE }

data class FiredAlert(val type: AlertType, val severity: AlertSeverity, val atMonotonicMs: Long)

data class DmsOutput(
    val availability: MonitoringAvailability,
    /** 診断用の現在の連続時間(ms)。UIのdebug overlayや測定記録に使う。 */
    val eyeClosureDurationMs: Long,
    /**
     * 警告の判定に使う脇見の継続時間。**速度ゲートが閉じている間も伸びる**(2026-10-01、docs/21 R-24)。
     * 側方を数えない設定([DayMvpSettings.distractionSideEnabled]=false)では手元の脇見だけ。
     */
    val distractionDurationMs: Long,
    /**
     * 手元・側方を区別しない脇見の継続時間(ms)。警告には使わず、記録・診断のためだけに持つ。
     *
     * 元は速度ゲートを無視した記録用だった(ゲートが閉じると[distractionDurationMs]が0のままだったため)。
     * R-24で[distractionDurationMs]もゲートに関係なく数えるようになったので、いまは
     * [DayMvpSettings.distractionSideEnabled]=falseのときだけ値が異なる。
     *
     * 品質フィルタ(`validForDecision`)は従来どおり適用する ── 顔が写っていないフレームまで
     * 脇見として数えてしまうと、記録として意味を成さないため(見失い中の脇見扱いは含む)。
     */
    val distractionRawDurationMs: Long = 0L,
    /** 「こっくり」検出の現在状態(診断ログ・UI表示用)。 */
    val nodding: NoddingState = NoddingState(0, null, false),
    /** あくび検出の現在状態(診断ログ・UI表示用)。 */
    val yawn: YawnState = YawnState(0, 0L, 0f, false, false),
    /**
     * 速度ゲートが開いているか(脇見・スマホの判定が有効か)。
     * 速度が取得できない間もtrue。UIで「速度により停止中」を表示するために返す。
     */
    val distractionSpeedGateOpen: Boolean = true,
    /** このprocess()呼び出しで新たに発報したアラート(通常0〜1件)。 */
    val firedAlerts: List<FiredAlert>,
    /** このprocess()呼び出しで「顔を確認できません」を新たに発報したか。 */
    val unavailableNoticeFired: Boolean,
    /**
     * [unavailableNoticeFired]が、続いている監視不能の**繰り返し**の報知か
     * ([DayMvpSettings.unavailableRepeatMs])。音は鳴らすが、記録は監視不能に入った最初の1回だけにする。
     */
    val unavailableNoticeIsRepeat: Boolean = false,
    /** 顔を見失っている時間を脇見として数えている最中か([DayMvpSettings.distractionCarryEntryYawDeg])。 */
    val distractionCarrying: Boolean = false,
    /** いまの脇見の方向。脇見の途中の一瞬の途切れでは直前の方向を保つ。脇見でなければnull。 */
    val distractionDirection: DistractionDirection? = null,
    /** このフレームの脇見の領域(数える対象かどうかに関係なく分類だけ返す)。脇見でなければnull。 */
    val distractionZone: DistractionZone? = null,
)

/**
 * 11_DAY_SMARTPHONE_MVP_PLAN.md 3.5(最低限の判定)の純粋Kotlin実装。Android非依存でテストする。
 *
 * - 長い両眼閉眼: 有効フレームで連続1.5秒→注意、2.5秒→強い警告。
 * - 長い道路外注視: 有効フレームで連続6.0秒→警告。**速度が[DayMvpSettings.distractionMinSpeedKph]
 *   未満の間は鳴らさない**(2026-08-31追加。信号待ち・休憩・積卸し中の誤報を消すため。
 *   速度が取得できない間はゲートを開いたままにする)。継続時間は速度に関係なく数える(2026-10-01、R-24)。
 *   手元・側方に分類し、側方を数えない設定では手元だけを数える(R-21)。
 * - あくび(2026-08-31追加): [YawnDetector]。眠気レベルD4の指標で、ASV上は任意の検知。
 *   眠気系のため速度ゲートは掛けない。
 * - 品質欠損300ms以下はタイマーを保持し、それを超えたら中断(=継続時間を0へ戻す)。
 * - 同種警告のcooldownは20秒。cooldown中でも重大度の昇格(注意→強い警告)は発報を許可する。
 * - 品質無効が2秒でLIMITED、5秒で「顔を確認できません」を通知してUNAVAILABLEへ。
 *   続く間は[DayMvpSettings.unavailableRepeatMs]ごとに繰り返す(2026-09-27)。
 *   品質が1秒連続して回復したらNORMALへ自動復帰する。**[DayMvpSettings.qualityGapGraceMs]未満の
 *   一瞬の回復では監視不能までの計測を戻さない**(2026-09-27、誤検出1枚で無音になっていたため)。
 * - 横または下を向いて顔を見失ったら、見失っている間も脇見として数える
 *   ([DayMvpSettings.distractionCarryEntryYawDeg]、2026-09-27。下向きは
 *   [DayMvpSettings.distractionCarryEntryPitchDownDeg]、2026-10-01)。その間は監視不能を数えない。
 *
 * 故障(ASV眠気4章/脇見4章の必須要件)は**ここでは扱わない**。故障の大半は「フレームが1枚も
 * 届かない」状態を伴い、フレーム駆動のこの状態機械では検知も発報もできないため、
 * [com.trypro.opendms.vision.FaultMonitor]とUI側の定期tickが担当する。
 */
class DayDmsStateMachine(private val settings: DayMvpSettings) {

    private val eyeClosureTracker = ConditionDurationTracker(settings.qualityGapGraceMs)
    private val distractionTracker = ConditionDurationTracker(settings.qualityGapGraceMs)

    /**
     * 速度ゲートを無視した脇見の継続時間。記録専用で、警告には使わない
     * ([DmsOutput.distractionRawDurationMs]のKDoc参照)。
     */
    private val distractionRawTracker = ConditionDurationTracker(settings.qualityGapGraceMs)
    private val eyeClosureCooldown = AlertCooldownTracker(settings.alertCooldownMs)

    /**
     * 脇見のcooldownは**方向ごと**(2026-09-27、利用者の指定)。右で鳴らした直後に左を長く見たら鳴らす。
     * 継続時間は[distractionTracker](全方向の合計)で数える ── 方向ごとに数えると、正面に戻らずに
     * 左右を2秒ずつ見回し続けたときに、どの方向も基準に届かず鳴らなくなるため。
     */
    private val distractionCooldownUntilMs = mutableMapOf<DistractionDirection, Long>()

    /** 同じ脇見の中で既に鳴らした方向。脇見が途切れたら空にする。 */
    private val distractionEpisodeFired = mutableSetOf<DistractionDirection>()

    /**
     * 方向ごとの連続時間。同じ脇見の中で方向を変えたとき、**新しい方向が単独で基準時間続いたら**
     * 鳴らすために使う(振り向いた瞬間に鳴らさない)。
     */
    private val distractionDirectionTrackers =
        DistractionDirection.entries.associateWith { ConditionDurationTracker(settings.qualityGapGraceMs) }

    /** この脇見で直近に観測した方向。途切れの猶予中(方向が取れないフレーム)の判定に使う。 */
    private var lastDistractionDirection: DistractionDirection? = null
    private val noddingDetector = NoddingDetector(settings)
    private val noddingCooldown = AlertCooldownTracker(settings.alertCooldownMs)
    private val noddingCandidateCooldown = AlertCooldownTracker(settings.alertCooldownMs)
    private val yawnDetector = YawnDetector(settings)
    private val yawnCooldown = AlertCooldownTracker(settings.alertCooldownMs)

    /**
     * 高温通知のcooldown。3.6の「短いシステム音1回」に対し、SEVERE状態が続く間ずっと鳴り続けると
     * 運転の妨げになるため、他警告と同じcooldown間隔で抑制する。
     */
    private val thermalCooldown = AlertCooldownTracker(settings.alertCooldownMs)
    private var wasThermalSevere = false

    /**
     * この運行で観測したthermal statusの最低値。高温判定の基準。
     *
     * 状態機械は運行開始のたびに作り直されるので、これは運行単位の値になる
     * (`DayMvpScreen`の`stateMachines.clear()`)。
     */
    private var thermalWatch = ThermalWatch()

    /** 直前フレームであくび頻発が成立中だったか。用途は[wasNodding]と同じ(episode終了の検出)。 */
    private var wasYawnFrequent = false

    /**
     * 直前フレームでこっくり成立中だったか。閉眼・脇見は[ConditionDurationTracker]が
     * `episodeReset`を返すのでそれをepisode終了の合図に使えるが、こっくりは継続時間でなく
     * 反復回数で成立するため同等の合図が無い。そのため成立→非成立への遷移を自前で検出して
     * [AlertCooldownTracker.onEpisodeReset]を呼ぶ。
     */
    private var wasNodding = false
    private var wasNoddingCandidate = false

    private var availability = MonitoringAvailability.NORMAL
    private var validSinceMs: Long? = null
    private var invalidSinceMs: Long? = null
    private var unavailableNotified = false
    private var lastUnavailableNoticeMs: Long? = null
    private var unavailableNoticeIsRepeat = false

    /** 直近の有効フレームが、見失ったら脇見として数える向きだったか。 */
    private var carryEligible = false

    /** 脇見として数え始めた(=顔を見失った)時刻。 */
    private var carryStartMs: Long? = null

    /** 見失い中の脇見の方向。見失う直前の有効フレームの向き。 */
    private var carryDirection = DistractionDirection.UNKNOWN

    /** 見失い中の脇見の領域。見失う直前の有効フレームの向き。 */
    private var carryZone = DistractionZone.SIDE

    fun process(input: DmsInput): DmsOutput {
        // 速度ゲート(2026-08-31): 信号待ち・休憩・積卸し中の脇見誤報を消す。速度が取得できない
        // 間(null)はゲートを開いたままにして監視を止めない。眠気系(閉眼・こっくり・あくび)には
        // 掛けない。理由は[DayMvpSettings.distractionMinSpeedKph]のKDocを参照。
        // 動作確認モード(testModeIgnoreSpeedGate)ではゲートを常に開ける。停車中や屋内でも
        // 脇見の動きを確かめられるようにするため。製品運用ではOFFにすること。
        val speed = input.speedKph
        val speedGateOpen = settings.testModeIgnoreSpeedGate ||
            speed == null ||
            speed >= settings.distractionMinSpeedKph

        val carrying = updateDistractionCarry(input)
        // 手元か側方か(R-21)。側方を数えない設定(告示相当)では手元の脇見だけを数える。
        val visibleDistracted = input.validForDecision && input.distracted
        val zone = when {
            visibleDistracted -> zoneOf(input.relativeYawDeg, input.relativePitchDeg)
            carrying -> carryZone
            else -> null
        }
        val counted = zone != null && (settings.distractionSideEnabled || zone == DistractionZone.HAND)
        // 監視不能を止めるのは、見失いを**実際に脇見として鳴らせるとき**だけ。停車中(ゲートが
        // 閉じている)に止めると、脇見も監視不能も鳴らない時間ができる(9/25実車の停車中の
        // 見失いで、試算上その状態になった。docs/18 §14-20)。数えない側方の見失いも同じ。
        val unavailableNoticeFired = updateAvailability(input, carrying && counted && speedGateOpen)

        // 閉眼判定はblendshapeに依存するため、顔が正面を向いていない間
        // (facialSignalReliable=false)は成立させない。
        // 脇見判定は頭部角度のみに依存するのでこの制約を課さない(下の行を参照)。
        val eyeConditionTrue = input.validForDecision && input.facialSignalReliable && input.eyesClosed
        val eyeResult = eyeClosureTracker.update(input.monotonicMs, eyeConditionTrue)
        if (eyeResult.episodeReset) eyeClosureCooldown.onEpisodeReset()

        // 顔を見失っている間の脇見(carrying)も同じタイマーで数える。
        // **継続時間は速度に関係なく数える**(2026-10-01、R-24、UN R183 5.5.2.3)。速度ゲートは
        // 警告だけを止める([shouldFireDistraction])。停車中から見続けたまま発進すれば、ゲートが
        // 開いた瞬間に鳴る。
        val distractionResult = distractionTracker.update(input.monotonicMs, counted)
        if (distractionResult.episodeReset) {
            distractionEpisodeFired.clear()
            lastDistractionDirection = null
        }
        val currentDirection = when {
            !counted -> null
            visibleDistracted -> directionOf(input.relativeYawDeg, input.relativePitchDeg)
            else -> carryDirection
        }
        val directionDurations = distractionDirectionTrackers.mapValues { (direction, tracker) ->
            tracker.update(input.monotonicMs, currentDirection == direction).durationMs
        }
        if (currentDirection != null) lastDistractionDirection = currentDirection

        // 手元・側方を区別しない記録用の計測([DmsOutput.distractionRawDurationMs])。
        val distractionRawResult = distractionRawTracker.update(input.monotonicMs, visibleDistracted || carrying)

        // 「こっくり」検出。頭部pitchと開眼比の両方を使うため、品質有効なフレームだけを与える
        // (無効フレームでは角度・開眼比とも信用できず、反転を誤カウントするため)。
        //
        // `facialSignalReliable`も見る。2026-09-01に`ONE_EYE_ONLY`等の減点を0にしたことで、
        // **横を向いている間のフレームが「品質有効」になった**。開眼比はその間読めないので、
        // ここで弾かないと横を向くたびに「開眼比が低い=眠そう」と誤カウントする
        // (2026-08-31に16分で39回誤発報したのと同じ壊れ方に戻ってしまう)。
        val noddingState = if (input.validForDecision && input.facialSignalReliable) {
            noddingDetector.update(
                monotonicMs = input.monotonicMs,
                relativePitchDeg = input.relativePitchDeg,
                eyeOpenRatio = input.eyeOpenRatio,
                // あくびの頭部の動きをこっくりと数えないための抑制材料。
                jawOpen = input.jawOpen,
                relativeYawDeg = input.relativeYawDeg,
            )
        } else {
            noddingDetector.update(input.monotonicMs, null, null)
        }

        // あくび検出。`jawOpen`は頭部回転中でも比較的安定して取れるが、品質無効フレームでは
        // 顔そのものが信用できないため、こっくりと同じく有効フレームだけを与える。
        // 横を向いている間は口の開きも読めないので`facialSignalReliable`も見る(こっくりと同じ理由)。
        val yawnState = if (input.validForDecision && input.facialSignalReliable) {
            yawnDetector.update(input.monotonicMs, input.jawOpen)
        } else {
            yawnDetector.update(input.monotonicMs, null)
        }

        val fired = mutableListOf<FiredAlert>()
        // 警告を待っている間([DmsInput.alertsHeld])は、cooldownを評価しない=鳴らしたことにしない。
        // エピソードの区切り(onEpisodeReset)と継続時間は数え続けるので、待ち終わった時点で続いていれば鳴る。
        val held = input.alertsHeld

        if (!held) {
            eyeClosureCooldown.evaluate(input.monotonicMs, severityForEyeClosure(eyeResult.durationMs))?.let {
                fired += FiredAlert(AlertType.EYE_CLOSURE, it, input.monotonicMs)
            }
        }
        if (!held && shouldFireDistraction(input, speedGateOpen, distractionResult.durationMs, directionDurations)) {
            fired += FiredAlert(AlertType.DISTRACTION, AlertSeverity.WARNING, input.monotonicMs)
        }
        // こっくりが一旦収まったらepisode終了とみなし、次のこっくりを新しいepisodeとして
        // 発報できるようにする。これを呼ばないと`episodeFiredSeverity`がCAUTIONのまま残り、
        // こっくりは重大度が昇格しない(常にCAUTION)ため運行中2回目以降が永久に発報しなくなる
        // (2026-08-29実機ログsession_3eaa59caで、3回のこっくりのうち1回目しか鳴らない不具合を確認)。
        if (wasNodding && !noddingState.isNodding) noddingCooldown.onEpisodeReset()
        wasNodding = noddingState.isNodding

        // こっくりは継続時間でなく「窓内の反復回数」で成立するため、成立中は常に注意レベルとして扱い、
        // 連続発報の抑制はcooldownに任せる。
        val noddingSeverity = if (noddingState.isNodding && !held) AlertSeverity.CAUTION else AlertSeverity.NONE
        noddingCooldown.evaluate(input.monotonicMs, noddingSeverity)?.let {
            fired += FiredAlert(AlertType.DROWSY_NODDING, it, input.monotonicMs)
        }

        // 開眼こっくり候補(無音)。本物のこっくりが同時に成立しているときは、そちらだけ残す。
        // 窓平均開眼が高い(=目の条件で落ちた)ものだけを候補として数えたいため。
        if (wasNoddingCandidate && !noddingState.isOpenEyeCandidate) noddingCandidateCooldown.onEpisodeReset()
        wasNoddingCandidate = noddingState.isOpenEyeCandidate
        val candidateSeverity =
            if (noddingState.isOpenEyeCandidate && !noddingState.isNodding && !held) AlertSeverity.CAUTION else AlertSeverity.NONE
        noddingCandidateCooldown.evaluate(input.monotonicMs, candidateSeverity)?.let {
            fired += FiredAlert(AlertType.DROWSY_NODDING_CANDIDATE, it, input.monotonicMs)
        }

        // あくび。こっくりと同じく回数で成立するため、成立中は常にCAUTIONとしcooldownに抑制を任せる。
        // 報知そのものは呼び出し側で閉眼・こっくりより弱くする(ASV眠気2.4.2の段階的報知)。
        // **あくびが1回成立するたびに、知らせ直せるようにする。**
        //
        // `AlertCooldownTracker`は「1つのepisodeにつき1回」で抑えるが、
        // あくびの「頻発」は**10分の窓に3回以上**という状態なので、
        // 一度立つと窓が空くまで下がらない。episodeの解除を「頻発でなくなったとき」
        // だけにしていたため、**3回目で1度鳴ったあと4回目以降は何度あくびをしても鳴らなかった**。
        //
        // 実測(session_354db919、10:10〜11:08): あくび10回に対して発報は2回だけ。
        //   10:12:44 / 10:13:03 / 10:13:12(3回目→発報) / 10:14:10(4回目→鳴らない)
        //   …窓が空いて… 10:38:43 / 10:46:27 / 10:47:07(→発報) / 10:47:25 以降は鳴らない
        //
        // ASV基本設計書のD4は「あくびが頻発」であり、続いている間は知らせ続けるのが筋。
        // 連発の抑制は`alertCooldownMs`が担うので、ここはあくびの成立ごとに解除してよい。
        if (yawnState.yawnCompleted) yawnCooldown.onEpisodeReset()
        if (wasYawnFrequent && !yawnState.isFrequent) yawnCooldown.onEpisodeReset()
        wasYawnFrequent = yawnState.isFrequent
        val yawnSeverity = if (yawnState.isFrequent && !held) AlertSeverity.CAUTION else AlertSeverity.NONE
        yawnCooldown.evaluate(input.monotonicMs, yawnSeverity)?.let {
            fired += FiredAlert(AlertType.DROWSY_YAWNING, it, input.monotonicMs)
        }

        // 高温通知(3.6)。thermal statusがSEVERE以上**かつ運行中の最低値より上**の間だけ
        // 候補とし、一旦下がったらepisode終了として次回また通知できるようにする。
        //
        // 最低値を基準にするのは、端末の申告が当てにならないため([DmsInput.thermalStatus])。
        // 「開始時の値」ではなく「これまでの最低値」を使うのは、開始直後だけ高く出て
        // すぐ下がる端末があるから(2026-09-09のログでSEVERE→NONEへ落ちている)。
        // 開始時の値で固定すると、そのあと本当に熱くなっても二度と鳴らない。
        val thermalSevere = thermalWatch.update(input.thermalStatus)
        if (wasThermalSevere && !thermalSevere) thermalCooldown.onEpisodeReset()
        wasThermalSevere = thermalSevere
        val thermalSeverity = if (thermalSevere) AlertSeverity.CAUTION else AlertSeverity.NONE
        thermalCooldown.evaluate(input.monotonicMs, thermalSeverity)?.let {
            fired += FiredAlert(AlertType.THERMAL, it, input.monotonicMs)
        }

        return DmsOutput(
            availability = availability,
            eyeClosureDurationMs = eyeResult.durationMs,
            distractionDurationMs = distractionResult.durationMs,
            distractionRawDurationMs = distractionRawResult.durationMs,
            nodding = noddingState,
            yawn = yawnState,
            distractionSpeedGateOpen = speedGateOpen,
            firedAlerts = fired,
            unavailableNoticeFired = unavailableNoticeFired,
            unavailableNoticeIsRepeat = unavailableNoticeFired && unavailableNoticeIsRepeat,
            distractionCarrying = carrying,
            distractionDirection = lastDistractionDirection,
            distractionZone = zone,
        )
    }

    /**
     * 脇見を鳴らすか(2026-09-27、方向ごとのcooldown)。
     *
     * 0. 速度ゲートが開いていること(継続時間はゲートに関係なく数えている。R-24)
     * 1. 全方向の合計の継続時間が基準([severityForDistraction])に達していること
     * 2. その脇見でまだ1度も鳴らしていなければ、いまの方向がcooldown中でなければ鳴らす
     * 3. 既に別の方向で鳴らしていれば、いまの方向が**単独で**基準時間続き、かつcooldown中でなければ鳴らす
     *    (右で鳴った後、正面に戻らず左へ振り向けた場合)
     * 同じ方向は同じ脇見の中で2度鳴らさない(従来どおり)。
     */
    private fun shouldFireDistraction(
        input: DmsInput,
        speedGateOpen: Boolean,
        totalDurationMs: Long,
        directionDurations: Map<DistractionDirection, Long>,
    ): Boolean {
        if (!speedGateOpen) return false
        if (severityForDistraction(totalDurationMs, input.speedKph) != AlertSeverity.WARNING) return false
        val direction = lastDistractionDirection ?: return false
        if (direction in distractionEpisodeFired) return false
        if (distractionEpisodeFired.isNotEmpty() &&
            severityForDistraction(directionDurations[direction] ?: 0L, input.speedKph) != AlertSeverity.WARNING
        ) {
            return false
        }
        if (input.monotonicMs < (distractionCooldownUntilMs[direction] ?: Long.MIN_VALUE)) return false
        distractionEpisodeFired += direction
        distractionCooldownUntilMs[direction] = input.monotonicMs + settings.alertCooldownMs
        return true
    }

    /** 較正基準からの外れが大きい軸で方向を決める(斜めを向いたとき)。 */
    private fun directionOf(yaw: Float?, pitch: Float?): DistractionDirection {
        if (yaw == null && pitch == null) return DistractionDirection.UNKNOWN
        val yawRatio = yaw?.let { abs(it) / settings.distractionYawDeg } ?: 0f
        val pitchRatio = pitch?.let { abs(it) / settings.distractionPitchDownDeg } ?: 0f
        return if (yaw != null && yawRatio >= pitchRatio) {
            if (yaw >= 0f) DistractionDirection.YAW_PLUS else DistractionDirection.YAW_MINUS
        } else {
            if ((pitch ?: 0f) >= 0f) DistractionDirection.PITCH_PLUS else DistractionDirection.PITCH_MINUS
        }
    }

    /**
     * 手元か側方か(R-21)。R183 Area 3 は視線で測るが、ここでは頭部の向きで近似する。
     * 下向き(pitchプラス)が[DayMvpSettings.distractionHandMinPitchDownDeg]以上で、
     * 左右が[DayMvpSettings.distractionHandMaxYawDeg]以内なら手元。斜め下(ナビ・シフト)も手元に入れるため、
     * [directionOf]のように1つの軸へ振り分けない。
     */
    private fun zoneOf(yaw: Float?, pitch: Float?): DistractionZone =
        if (pitch != null && pitch >= settings.distractionHandMinPitchDownDeg &&
            (yaw == null || abs(yaw) <= settings.distractionHandMaxYawDeg)
        ) {
            DistractionZone.HAND
        } else {
            DistractionZone.SIDE
        }

    /**
     * 顔を見失っている今を脇見として数えるか([DayMvpSettings.distractionCarryEntryYawDeg]・
     * [DayMvpSettings.distractionCarryEntryPitchDownDeg])。
     *
     * 見失う直前の有効フレームが横または下を向いていたら、見失った時点から
     * [DayMvpSettings.distractionCarryMaxMs]まで脇見として数える。有効フレームが戻ったら終わる。
     * 上限を超えたら打ち切り、次に顔が見えるまで再開しない。
     */
    private fun updateDistractionCarry(input: DmsInput): Boolean {
        if (input.validForDecision) {
            val yaw = input.relativeYawDeg
            val pitch = input.relativePitchDeg
            // 見失いの閾値に対する外れの比。下向きだけを見る(上向きで見失う場面は扱わない)。
            val yawRatio = yaw?.let { abs(it) / settings.distractionCarryEntryYawDeg } ?: 0f
            val pitchRatio = pitch?.let { it / settings.distractionCarryEntryPitchDownDeg } ?: 0f
            carryEligible = yawRatio >= 1f || pitchRatio >= 1f
            carryDirection = when {
                pitchRatio >= 1f && pitchRatio > yawRatio -> DistractionDirection.PITCH_PLUS
                yaw != null -> if (yaw >= 0f) DistractionDirection.YAW_PLUS else DistractionDirection.YAW_MINUS
                else -> carryDirection
            }
            carryZone = zoneOf(yaw, pitch)
            carryStartMs = null
            return false
        }
        if (!carryEligible) return false
        val start = carryStartMs ?: input.monotonicMs.also { carryStartMs = it }
        if (input.monotonicMs - start <= settings.distractionCarryMaxMs) return true
        carryEligible = false
        return false
    }

    /**
     * 窓内のあくび成立時刻。**運行をまたいで持ち越すために公開する**
     * ([YawnDetector.exportYawnTimes]参照)。
     */
    fun exportYawnHistory(): List<Long> = yawnDetector.exportYawnTimes()

    /** [exportYawnHistory]の値を新しい運行の状態機械へ引き継ぐ。 */
    fun restoreYawnHistory(times: List<Long>) = yawnDetector.restoreYawnTimes(times)

    /**
     * 運行(LocalSession)を新規に開始する際に呼ぶ。前回の運行のcooldown/継続時間/監視不能状態を
     * 一切引き継がない(D0-009: 「運行開始」ごとに判定をやり直す)。
     */
    fun reset() {
        eyeClosureTracker.resetState()
        distractionTracker.resetState()
        distractionRawTracker.resetState()
        eyeClosureCooldown.resetAll()
        distractionCooldownUntilMs.clear()
        distractionEpisodeFired.clear()
        distractionDirectionTrackers.values.forEach { it.resetState() }
        lastDistractionDirection = null
        noddingDetector.reset()
        noddingCooldown.resetAll()
        noddingCandidateCooldown.resetAll()
        wasNodding = false
        wasNoddingCandidate = false
        yawnDetector.reset()
        yawnCooldown.resetAll()
        wasYawnFrequent = false
        thermalCooldown.resetAll()
        wasThermalSevere = false
        thermalWatch = ThermalWatch()
        availability = MonitoringAvailability.NORMAL
        validSinceMs = null
        invalidSinceMs = null
        unavailableNotified = false
    }

    private fun severityForEyeClosure(durationMs: Long): AlertSeverity = when {
        durationMs >= settings.eyeClosureWarningMs -> AlertSeverity.WARNING
        durationMs >= settings.eyeClosureCautionMs -> AlertSeverity.CAUTION
        else -> AlertSeverity.NONE
    }

    /**
     * 脇見の重さ。**速度で必要な継続時間が変わる**(EU委任規則 ADDW)。
     *
     * - 50km/h超 → 3.5秒
     * - それ以下 → 6秒
     * - **速度が取れないとき → 6秒**(厳しい方ではなく緩い方)。
     *   速度が分からないのに短い方で鳴らすと、停車中に誤って鳴らしうる。
     */
    private fun severityForDistraction(durationMs: Long, speedKph: Float?): AlertSeverity {
        val needed =
            if (speedKph != null && speedKph > settings.distractionHighSpeedKph) {
                settings.distractionWarningMsHighSpeed
            } else {
                settings.distractionWarningMs
            }
        return if (durationMs >= needed) AlertSeverity.WARNING else AlertSeverity.NONE
    }

    /**
     * LIMITED/UNAVAILABLE/NORMALの遷移。有効/無効の連続時間を見る。
     *
     * @param carrying 顔を見失っている時間を脇見として数えている最中。その間は監視不能へ進めない
     *   (5秒の監視不能が6秒の脇見より先に鳴ってしまうため)。無効の起点は記録しておくので、
     *   上限を超えて脇見扱いが外れた瞬間に、見失った時点から数えた時間で判定される。
     */
    private fun updateAvailability(input: DmsInput, carrying: Boolean): Boolean {
        var noticeFired = false
        unavailableNoticeIsRepeat = false
        if (input.validForDecision) {
            val since = validSinceMs ?: input.monotonicMs
            validSinceMs = since
            val validStreakMs = input.monotonicMs - since
            // 一瞬だけ顔が見えても監視不能までの計測は戻さない。2026-09-27の机上試験では
            // 横を向いている最中の誤検出1枚ずつが計測を戻し、9秒見失っても無音だった。
            if (validStreakMs >= settings.qualityGapGraceMs) invalidSinceMs = null
            if (availability != MonitoringAvailability.NORMAL && validStreakMs >= settings.unavailableRecoveryMs) {
                availability = MonitoringAvailability.NORMAL
                unavailableNotified = false
                lastUnavailableNoticeMs = null
            }
        } else {
            val since = invalidSinceMs ?: input.monotonicMs
            invalidSinceMs = since
            validSinceMs = null
            if (carrying) return false
            val invalidStreakMs = input.monotonicMs - since
            when {
                invalidStreakMs >= settings.unavailableWarningMs -> {
                    availability = MonitoringAvailability.UNAVAILABLE
                    val lastNotice = lastUnavailableNoticeMs
                    if (input.alertsHeld) {
                        // 待っている間は通知しない。通知したことにもしないので、待ち終わったら最初の通知を出す。
                    } else if (!unavailableNotified) {
                        unavailableNotified = true
                        noticeFired = true
                        lastUnavailableNoticeMs = input.monotonicMs
                    } else if (settings.unavailableRepeatMs > 0 && lastNotice != null &&
                        input.monotonicMs - lastNotice >= settings.unavailableRepeatMs
                    ) {
                        noticeFired = true
                        unavailableNoticeIsRepeat = true
                        lastUnavailableNoticeMs = input.monotonicMs
                    }
                }
                invalidStreakMs >= settings.unavailableLimitedMs -> {
                    if (availability == MonitoringAvailability.NORMAL) {
                        availability = MonitoringAvailability.LIMITED
                    }
                }
            }
        }
        return noticeFired
    }
}

private data class DurationResult(val durationMs: Long, val episodeReset: Boolean)

/**
 * 「条件が真である連続時間」を、短い欠損(gapGraceMs以下)を跨いでも保持しつつ計測する。
 * 3.5: 「300ms以下の一時frame欠損はtimerを保持し、それを超えたら中断する」/
 * 「50ms未満の一時的な信号変動でタイマーをリセットしない」(50ms<300msなので同じ仕組みで満たす)。
 */

private class ConditionDurationTracker(private val gapGraceMs: Long) {
    private var startMs: Long? = null
    private var lastTrueMs: Long? = null

    fun update(nowMs: Long, conditionTrue: Boolean): DurationResult {
        if (conditionTrue) {
            val lastTrue = lastTrueMs
            if (startMs == null || lastTrue == null || nowMs - lastTrue > gapGraceMs) {
                startMs = nowMs
            }
            lastTrueMs = nowMs
            return DurationResult(nowMs - (startMs ?: nowMs), episodeReset = false)
        }

        val lastTrue = lastTrueMs
        if (lastTrue != null && nowMs - lastTrue > gapGraceMs) {
            val wasActive = startMs != null
            startMs = null
            lastTrueMs = null
            return DurationResult(0L, episodeReset = wasActive)
        }

        val start = startMs
        val duration = if (start != null && lastTrue != null) lastTrue - start else 0L
        return DurationResult(duration, episodeReset = false)
    }

    fun resetState() {
        startMs = null
        lastTrueMs = null
    }
}

/**
 * 警告の重大度とcooldownを管理する。同種警告は`cooldownMs`の間、同一episode内で既に発報した
 * severity以下の再発報を抑制する。ただしそのepisode/cooldown期間中でも、それまでに発報した
 * severityを上回る昇格(注意→強い警告)は常に発報を許可する(3.5)。
 */
private class AlertCooldownTracker(private val cooldownMs: Long) {
    private var lastFiredSeverity: AlertSeverity = AlertSeverity.NONE
    private var cooldownUntilMs: Long = Long.MIN_VALUE
    private var episodeFiredSeverity: AlertSeverity = AlertSeverity.NONE

    fun onEpisodeReset() {
        episodeFiredSeverity = AlertSeverity.NONE
    }

    fun evaluate(nowMs: Long, candidateSeverity: AlertSeverity): AlertSeverity? {
        if (candidateSeverity == AlertSeverity.NONE) return null
        if (candidateSeverity <= episodeFiredSeverity) return null
        if (candidateSeverity <= lastFiredSeverity && nowMs < cooldownUntilMs) return null

        episodeFiredSeverity = candidateSeverity
        lastFiredSeverity = candidateSeverity
        cooldownUntilMs = nowMs + cooldownMs
        return candidateSeverity
    }

    fun resetAll() {
        lastFiredSeverity = AlertSeverity.NONE
        cooldownUntilMs = Long.MIN_VALUE
        episodeFiredSeverity = AlertSeverity.NONE
    }
}
