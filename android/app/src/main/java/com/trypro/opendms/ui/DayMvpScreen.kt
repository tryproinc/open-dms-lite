package com.trypro.opendms.ui

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import com.trypro.opendms.vision.QualityAdvice
import com.trypro.opendms.domain.ThermalWatch
import com.trypro.opendms.domain.MonitoringArming
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.text.style.TextAlign
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.trypro.opendms.BuildConfig
import com.trypro.opendms.alerts.AlertKind
import com.trypro.opendms.alerts.AndroidAlertSink
import com.trypro.opendms.camera.CameraXVisionSource
import com.trypro.opendms.camera.MountOrientationSensor
import com.trypro.opendms.data.CalibrationStore
import com.trypro.opendms.data.DiagnosticLogger
import com.trypro.opendms.data.EventCsvExporter
import com.trypro.opendms.data.LocalEvent
import com.trypro.opendms.data.LocalSession
import com.trypro.opendms.data.MountOrientationStore
import com.trypro.opendms.data.SessionRepository
import com.trypro.opendms.data.SessionSummary
import com.trypro.opendms.data.SettingsStore
import com.trypro.opendms.domain.AlertSeverity
import com.trypro.opendms.domain.AlertType
import com.trypro.opendms.domain.CalibrationCollector
import com.trypro.opendms.domain.CalibrationFailure
import com.trypro.opendms.domain.CalibrationFailureReason
import com.trypro.opendms.domain.CalibrationProfile
import com.trypro.opendms.domain.CalibrationResult
import com.trypro.opendms.domain.DayDmsStateMachine
import com.trypro.opendms.domain.DayMvpSettings
import com.trypro.opendms.domain.DmsInputMapper
import com.trypro.opendms.domain.DmsOutput
import com.trypro.opendms.domain.EventLocation
import com.trypro.opendms.domain.EyeClosedCalibrationCollector
import com.trypro.opendms.domain.FiredAlert
import com.trypro.opendms.domain.MonitoringAvailability
import com.trypro.opendms.domain.MonitoringDisplayMode
import com.trypro.opendms.location.SpeedProvider
import com.trypro.opendms.ui.theme.NightBlack
import com.trypro.opendms.ui.theme.NightStatusCaution
import com.trypro.opendms.ui.theme.NightStatusNormal
import com.trypro.opendms.ui.theme.NightStatusUnavailable
import com.trypro.opendms.ui.theme.NightText
import com.trypro.opendms.ui.theme.NightTextDim
import com.trypro.opendms.ui.theme.OpenDmsTheme
import com.trypro.opendms.vision.FaultMonitor
import com.trypro.opendms.vision.FaultReason
import com.trypro.opendms.vision.FeatureFrame
import com.trypro.opendms.vision.MediaPipeFaceAnalyzer
import com.trypro.opendms.vision.MountRotation
import com.trypro.opendms.vision.PhoneDetector
import com.trypro.opendms.vision.QualityFlag
import com.trypro.opendms.vision.SignalQuality
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch

/**
 * 下段の操作パネルが画面の高さに占める上限(docs/17 §3-1)。
 * 残りは必ず映像に残す(前検査・較正で顔が見える必要がある)。詳細を開いている間はこの高さに固定する。
 */
private const val PANEL_MAX_HEIGHT_FRACTION = 0.65f

/** 下段の操作パネルの地色。映像と切り離すため不透明にする。黒ベース表示中は[NightBlack]。 */
private val PanelBackground = Color(0xFF10171C)

/**
 * DAY MVP-0の画面状態。11_DAY_SMARTPHONE_MVP_PLAN.md 3.1により1 Activity・4状態に限定する。
 * MONITORING/RESULTの実装はD0-007(状態機械)・D0-009(結果画面)で追加する。
 */
enum class DayMvpUiState {
    PREFLIGHT,
    CALIBRATING,
    MONITORING,
    RESULT,
}

/**
 * [DayMvpUiState.CALIBRATING]の中の段階(2026-09-11)。画面状態は計画書3.1どおり4つのまま。
 *
 * - [OPEN]: 正面を見て10秒(従来の較正)
 * - [EYES_CLOSED]: 目を閉じて3秒。安定区間が取れなければ6秒まで延ばす
 *   ([EyeClosedCalibrationCollector])。[OPEN]が成功したら自動で続く
 */
enum class CalibrationPhase { OPEN, EYES_CLOSED }

@Composable
fun DayMvpScreen(
    hasCameraPermission: Boolean = false,
    /** カメラ権限が「今後表示しない」で拒否されている。求め直せないので端末の設定を案内する。 */
    cameraPermissionPermanentlyDenied: Boolean = false,
    /** カメラ権限を求め直す(公開前レビュー R-07)。 */
    onRequestCameraPermission: () -> Unit = {},
    /** PiP(小窓)中か。trueのときは操作パネルを畳んで映像と監視状態だけにする(docs/18 §14-15)。 */
    isInPictureInPicture: Boolean = false,
    /** 設定のPiP許可をActivityへ伝える(自動遷移の有効化に使う)。 */
    onPictureInPictureAllowedChange: (Boolean) -> Unit = {},
    /** PiPから全画面へ戻す。**発報時に呼ぶ**(ASV眠気2.4.1の視覚報知のため)。 */
    onRequestExitPictureInPicture: () -> Unit = {},
    modifier: Modifier = Modifier,
    initialSettings: DayMvpSettings = DayMvpSettings.DAY_MVP0_DEFAULT,
) {
    val context = LocalContext.current
    val calibrationStore = remember { CalibrationStore(context) }
    val settingsStore = remember { SettingsStore(context) }
    val sessionRepository = remember { SessionRepository(context) }
    // SES-007: 前回の起動で終了処理されずに残った運行を片付ける。起動時刻より前に始まったものだけを
    // 対象にするので、この起動で始める運行を巻き込まない(SessionRepository.closeAbandonedSessions)。
    val launchedAtUtcMs = remember { System.currentTimeMillis() }
    LaunchedEffect(sessionRepository) {
        val (aborted, deleted) = sessionRepository.closeAbandonedSessions(launchedAtUtcMs)
        if (aborted + deleted > 0) {
            Log.i("DayMvpScreen", "SES-007: 異常終了した運行を片付けた(終了扱い${aborted}件 / 削除${deleted}件)")
        }
    }
    val diagnosticLogger = remember { DiagnosticLogger(context) }
    val alertSink = remember { AndroidAlertSink(context) }
    val activeAlert by alertSink.activeAlert.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // 2026-08-31追加: 速度ゲート(信号待ち・休憩・積卸し中の脇見誤報を消す)。
    // 緯度経度は保持しない(SpeedProviderのKDoc参照)。権限が無ければ速度はnullのままで、
    // ゲートは開いたまま(従来どおり作動する)になる。
    val speedProvider = remember { SpeedProvider(context) }
    val speedKph by speedProvider.speedKph.collectAsState()
    val locationPermissionGranted by speedProvider.permissionGranted.collectAsState()

    // 2026-08-31追加: 故障検知(ASV眠気4章/脇見4章の必須要件)。
    val faultMonitor = remember { FaultMonitor(initialSettings) }
    var faultReason by remember { mutableStateOf<FaultReason?>(null) }

    // 2026-08-31追加: スマホ検出の診断計測(D1-020の実測ゲート)。debug buildのみ。
    val phoneDetector = remember {
        if (BuildConfig.DEBUG) PhoneDetector(context, initialSettings) else null
    }
    DisposableEffect(phoneDetector) {
        onDispose { phoneDetector?.close() }
    }

    // D0-011実地試験前に閾値を設定画面から変更できるようにする。DataStoreに保存があれば
    // それを読み込み、無ければDayMvpSettings.DAY_MVP0_DEFAULT相当のinitialSettingsを使う。
    var settings by remember { mutableStateOf(initialSettings) }

    /**
     * 判定の状態機械。継続時間・cooldown・こっくり窓を持つ。
     * 運行開始のたびに作り直す(設定変更を確実に反映するため)ので、Composeの状態にはしない。
     * 結果コールバックのスレッドから使い、運行開始(メインスレッド)が入れ替えるので`AtomicReference`で持つ。
     */
    val stateMachineRef = remember { AtomicReference<DayDmsStateMachine?>(null) }

    /**
     * 運行をまたいで持ち越す、窓内のあくび成立時刻。
     *
     * 眠気は運行の区切りとは無関係に続く。休憩で「運行終了→運行開始」を挟むと、
     * それまでのあくびが消えて頻発(既定10分3回)が成立しなくなっていた。
     * 状態機械は運行ごとに作り直す(cooldownや継続時間を引きずらないため)ので、
     * **あくびの窓だけ**を明示的に引き継ぐ。窓より古いものは次のフレームで自動的に捨てられる。
     */
    val carriedYawnHistory = remember { AtomicReference<List<Long>>(emptyList()) }
    var showSettings by remember { mutableStateOf(false) }
    // ASV眠気5章/脇見5章の周知5項目。運転中には出さず、PREFLIGHTから開けるようにする。
    var showDriverNotice by remember { mutableStateOf(false) }
    // 「このアプリについて」(2026-10-17)。
    var showAbout by remember { mutableStateOf(false) }
    // 毎回は触らない項目(取付方向・動作確認モード)を畳んでおくための開閉状態。
    // 既定で閉じ、PREFLIGHTに主要な操作だけが並ぶようにする。
    var showAdvanced by remember { mutableStateOf(false) }

    // 一度20km/hを超えるまで警告を出さない(Full の docs/19 §3-2a)。運行開始で作り直し、運行中は0.5秒ごとに見直す。
    var monitoringArming by remember { mutableStateOf<MonitoringArming?>(null) }
    var alertsArmed by remember { mutableStateOf(false) }
    // 戻るボタンで閉じる前に確認する(2026-10-16)。
    var showExitConfirm by remember { mutableStateOf(false) }
    /** 詳細パネルで開いているタブ(docs/17 §3-3)。 */
    var advancedTab by remember { mutableStateOf(AdvancedTab.CAMERA) }
    // 位置情報は起動時に黙って要求せず、PREFLIGHTでボタンから要求する(何に使うかを先に読めるようにする)。
    // Android 12以降はFINE単独を要求できないため、FINEとCOARSEを同時に要求する。
    // COARSEだけ許可された場合はGPS_PROVIDERを使えず速度が取れないが、start()がfalseを返すだけで
    // 監視は継続する(速度ゲートが開いたままになる)。
    // 運行開始時の位置情報の確認から「許可する」を選んだとき、許可の結果が返ってから運行を始める(2026-10-17)。
    var startAfterLocationRequest by remember { mutableStateOf(false) }
    var locationResultCount by remember { mutableIntStateOf(0) }
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        speedProvider.start()
        locationResultCount++
    }
    /** 画面の「高温」表示。端末の申告の絶対値ではなく、見てきた最低値より上がったときだけNG。 */
    val uiThermalWatch = remember { ThermalWatch() }
    var thermalHot by remember { mutableStateOf(false) }
    var showLocationPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        settingsStore.settingsFlow.collect { settings = it }
    }


    var uiState by remember { mutableStateOf(DayMvpUiState.PREFLIGHT) }
    /** 直近フレームと直近の判定結果。 */
    var frame by remember { mutableStateOf<FeatureFrame?>(null) }
    var dmsOutput by remember { mutableStateOf<DmsOutput?>(null) }
    var calibration by remember { mutableStateOf<CalibrationProfile?>(null) }
    var calibrationMessage by remember { mutableStateOf<String?>(null) }
    var calibrationProgress by remember { mutableStateOf(0f) }
    var calibrationValidCount by remember { mutableStateOf(0) }
    /** 閉眼較正が「延長中」か(3秒を過ぎても安定区間が取れていない)。表示用。 */
    var eyeClosedCalibrationExtending by remember { mutableStateOf(false) }
    /** いまの較正の段階。CALIBRATINGに入るときに呼び出し側が決める。 */
    var calibrationPhase by remember { mutableStateOf(CalibrationPhase.OPEN) }

    // D0-009: 運行(LocalSession)の状態。座標・画像は一切保持しない(SessionRepository参照)。
    var activeSession by remember { mutableStateOf<LocalSession?>(null) }
    var sessionMonitoringMs by remember { mutableStateOf(0L) }
    var sessionValidMonitoringMs by remember { mutableStateOf(0L) }
    var lastFrameMonotonicMs by remember { mutableStateOf<Long?>(null) }
    var resultSummary by remember { mutableStateOf<SessionSummary?>(null) }
    /** 結果画面に出す発報の一覧。運行終了時に読み込む。 */
    var resultEvents by remember { mutableStateOf<List<LocalEvent>>(emptyList()) }
    var resultSessionId by remember { mutableStateOf("") }
    var exportMessage by remember { mutableStateOf<String?>(null) }

    SideEffect {
        // 設定画面で閾値を変えたら、作り直さずに次のtick/フレームから反映させる。
        faultMonitor.settings = settings
        // 位置の保持はOFFが既定。ONにされたときだけSpeedProviderが緯度経度を持つ。
        speedProvider.captureLocation = settings.recordLocationOnEvent
    }
    // PiPの許可はActivity側が自動遷移に使う(docs/18 §14-15)。
    LaunchedEffect(settings.pictureInPictureEnabled) {
        onPictureInPictureAllowedChange(settings.pictureInPictureEnabled)
    }
    // **発報したらPiPを解除して全画面へ戻す。** 小窓のままでは視覚による報知にならない
    // (ASV眠気2.4.1)。較正の合図は警告ではないので戻さない。
    LaunchedEffect(activeAlert, isInPictureInPicture) {
        if (isInPictureInPicture && activeAlert != null && activeAlert != AlertKind.CALIBRATION_CUE) {
            onRequestExitPictureInPicture()
        }
    }
    // 故障の定期評価(ASV眠気4章/脇見4章)。フレームの到着とは独立したtickで回す必要がある
    // ── 故障の大半は「フレームが1枚も届かない」状態を伴い、フレームコールバックの中から
    // 評価したのでは永久に検知できないため。
    // 立ち上がり(正常→故障)でだけ音・振動を鳴らし、故障が続く間は画面表示で知らせ続ける
    // (鳴り続けると運転の妨げになるため。ASVが求めるのは「認識できる手段」であって反復報知ではない)。
    //
    // **画面が消えている / アプリが背面にある間は評価しない。** その状態でカメラが止まるのは
    // 正常な動作であって故障ではない(2026-08-31実機確認: 画面OFFで`NO_FRAME`が成立し、
    // 「監視機能が使用できません」が鳴っていた)。`repeatOnLifecycle(STARTED)`で見えている間だけ
    // 回し、戻るたびにFaultMonitorをリセットして起動猶予を取り直す。
    //
    // **RESUMEDではなくSTARTEDにしてある。** 画面OFF・背面はどちらもON_STOPなのでSTARTEDで除外できる。
    // 一方RESUMEDだと、ダイアログが上に出ている間(ON_PAUSE)も評価が止まる。
    // PiP中もON_PAUSEだがカメラと解析は動いている(docs/18 §14-15)。CameraXもON_STOPまで止まらない。
    val faultLifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(hasCameraPermission, faultLifecycleOwner) {
        if (!hasCameraPermission) return@LaunchedEffect
        faultLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // 背面から戻った直後はカメラの再バインドとモデルの再開に時間がかかる。
            // 前回の停止状態を引きずらないよう作り直す(起点は今。FaultMonitor.reset参照)。
            faultMonitor.reset(SystemClock.elapsedRealtime())
            faultReason = null
            faultTickLoop(
                faultMonitor = faultMonitor,
                currentReason = { faultReason },
                onReason = { faultReason = it },
                onFaultRising = { reason ->
                    Log.w("DayMvpScreen", "system fault detected: $reason")
                    alertSink.trigger(AlertKind.SYSTEM_FAULT)
                    activeSession?.let { session ->
                        sessionRepository.recordAlertEvent(
                            sessionId = session.id,
                            fired = FiredAlert(
                                type = AlertType.SYSTEM_FAULT,
                                severity = AlertSeverity.WARNING,
                                atMonotonicMs = SystemClock.elapsedRealtime(),
                            ),
                            durationMs = 0L,
                            atWallClockUtcMs = System.currentTimeMillis(),
                            qualityFlags = emptySet(),
                            confidence = null,
                            modelVersion = settings.modelVersion,
                            settingsVersion = settings.version,
                        )
                    }
                },
            )
        }
    }


    // 速度の取得(脇見・スマホの速度ゲート用)。運行中だけ測位し、それ以外では止めて電力を使わない。
    // 権限が無ければstart()はfalseを返し、速度はnullのまま=ゲートは開いたままになる。
    DisposableEffect(uiState) {
        if (uiState == DayMvpUiState.MONITORING) speedProvider.start() else speedProvider.stop()
        onDispose { speedProvider.stop() }
    }

    // 3.6: メディア音量の監視。音量キーでいつでも変わるため、PREFLIGHT表示中は定期的に読み直す。
    var volumeRatio by remember { mutableStateOf(1f) }
    val volumeSufficient = volumeRatio >= MIN_MEDIA_VOLUME_RATIO
    LaunchedEffect(uiState) {
        while (isActive) {
            volumeRatio = alertSink.mediaVolumeRatio()
            delay(500L)
        }
    }

    // D0-011追加検証: スマホの取付方向(上下逆・横向き)対応。既定は自動検知(ジャイロ/加速度)、
    // センサーが検知できない/誤検知する場合のために手動固定を用意する(利用者要望)。
    val mountOrientationStore = remember { MountOrientationStore(context) }
    val mountOrientationSensor = remember { MountOrientationSensor(context) }
    DisposableEffect(Unit) {
        mountOrientationSensor.start()
        onDispose { mountOrientationSensor.stop() }
    }
    var manualMountOverrideDegrees by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        mountOrientationStore.manualOverrideDegreesFlow.collect { manualMountOverrideDegrees = it }
    }
    var sensorDetectedMountDegrees by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(mountOrientationSensor) {
        while (isActive) {
            sensorDetectedMountDegrees = mountOrientationSensor.detectedRotationDegrees
            delay(300L)
        }
    }
    /**
     * 運行中に固定した取付回転。運行開始で確定し、終了で解除する。
     *
     * 運行の途中でこの値が変わると、**その瞬間から解析へ渡すlandmark座標が回る**。
     * 設定画面を開いて触ったときだけでなく、以前はセンサー検知でも起きていた。
     */
    var sessionMountRotationDegrees by remember { mutableStateOf<Int?>(null) }

    /**
     * 前面カメラの取付回転。**人が設定した値だけを使う**(既定0°=通常設置)。
     *
     * 姿勢センサーによる自動追従は2026-09-09にやめた。車載マウントは端末を斜めに固定するので
     * `OrientationEventListener`が簡単に180°/270°へ振れる。実車ログでは
     * 16:35の運行で前面カメラが180°に張り付いて**オーバーレイが上下逆**になり、
     * 07:48の運行では0°と270°を行き来していた。
     * この値はオーバーレイだけでなく**解析へ渡すlandmark座標も回す**ので、
     * 誤検知した瞬間に判定そのものの座標系がずれる。
     * センサー値は設定画面に参考として出すだけにした([sensorDetectedMountDegrees])。
     */
    val resolvedMountRotationDegrees =
        sessionMountRotationDegrees ?: manualMountOverrideDegrees ?: 0

    // 起動時に保存済みの較正値(あれば)をDataStoreから読み込む。SES-005: 「アプリ終了...で再較正する」
    // 方針のため、ここでは前回値を「参考表示」するだけで、判定への適用は明示的な較正完了時のみ行う
    // (MediaPipeFaceAnalyzer.calibrationBaselineはcalibrationの更新に追従する)。
    LaunchedEffect(calibrationStore) {
        calibrationStore.profileFlow.collect { calibration = it }
    }

    DisposableEffect(Unit) {
        onDispose { alertSink.release() }
    }

    // D0-009 診断用: 状態遷移とセッション開始が正しく起きているかlogcatで確認できるようにする。
    LaunchedEffect(uiState) {
        Log.d("DayMvpScreen", "uiState -> $uiState, activeSession=${activeSession?.id}")
    }

    // D0-006 Gate: 「有効frame不足時に理由表示、数値のみ保存」。CALIBRATING状態に入るたびに
    // 新しいCalibrationCollectorで約10秒間frameを集め、成功/失敗いずれかを画面へ反映する。
    LaunchedEffect(uiState, calibrationPhase) {
        if (uiState != DayMvpUiState.CALIBRATING || calibrationPhase != CalibrationPhase.OPEN) return@LaunchedEffect

        calibrationMessage = null
        calibrationProgress = 0f
        calibrationValidCount = 0

        val collector = CalibrationCollector(settings)
        val startElapsedMs = SystemClock.elapsedRealtime()
        while (isActive && SystemClock.elapsedRealtime() - startElapsedMs < settings.calibrationDurationMs) {
            frame?.let { collector.addFrame(it) }
            calibrationValidCount = collector.validFrameCount
            calibrationProgress = ((SystemClock.elapsedRealtime() - startElapsedMs).toFloat() / settings.calibrationDurationMs)
                .coerceIn(0f, 1f)
            delay(100L)
        }

        when (val result = collector.finish(System.currentTimeMillis())) {
            is CalibrationResult.Success -> {
                // 開眼の値はここで保存してしまう。閉眼較正が失敗しても開眼較正は無駄にしない。
                calibrationStore.save(result.profile)
                calibration = result.profile
                calibrationMessage = "較正完了(有効frame ${collector.validFrameCount}/${collector.currentTotalFrameCount})"
                // 続けて閉眼較正へ(2026-09-11)。段階を変えるとこの効果は作り直される。
                calibrationPhase = CalibrationPhase.EYES_CLOSED
                return@LaunchedEffect
            }
            is CalibrationResult.Failure -> {
                calibrationMessage = buildCalibrationFailureMessage(result.failure)
            }
        }
        uiState = DayMvpUiState.PREFLIGHT
    }

    // 閉眼較正(2026-09-11)。開眼較正の直後、または前検査画面の「閉眼較正」から入る。
    // 「目を閉じてください」→ 合図音 → 3秒(足りなければ6秒まで延長)→ 合図音 → 結果。
    // 利用者は目を閉じているので、進み具合は音で伝える。画面の文字は補助。
    LaunchedEffect(uiState, calibrationPhase) {
        if (uiState != DayMvpUiState.CALIBRATING || calibrationPhase != CalibrationPhase.EYES_CLOSED) return@LaunchedEffect
        val open = calibration
        if (open == null) {
            // 開眼較正なしにここへは来ないはずだが、来たら黙って戻す。
            calibrationPhase = CalibrationPhase.OPEN
            uiState = DayMvpUiState.PREFLIGHT
            return@LaunchedEffect
        }

        calibrationProgress = 0f
        calibrationValidCount = 0
        eyeClosedCalibrationExtending = false

        // 開始の合図。閉じるまでの反応時間は安定区間の判定が吸収する。
        alertSink.trigger(AlertKind.CALIBRATION_CUE)

        val collector = EyeClosedCalibrationCollector(settings, open)
        val startElapsedMs = SystemClock.elapsedRealtime()
        val minMs = settings.eyeClosedCalibrationMinDurationMs
        val maxMs = settings.eyeClosedCalibrationMaxDurationMs
        while (isActive) {
            val elapsed = SystemClock.elapsedRealtime() - startElapsedMs
            frame?.let { collector.addFrame(it) }
            calibrationValidCount = collector.validFrameCount
            calibrationProgress = (elapsed.toFloat() / minMs).coerceIn(0f, 1f)
            if (elapsed >= maxMs) break
            if (elapsed >= minMs) {
                // 基本時間を過ぎたら、安定区間が取れた時点で止める。取れていなければ延長。
                if (collector.isSatisfied()) break
                eyeClosedCalibrationExtending = true
            }
            delay(100L)
        }
        // 終了の合図(2回)。目を開けてよい。
        alertSink.trigger(AlertKind.CALIBRATION_CUE)
        delay(350L)
        alertSink.trigger(AlertKind.CALIBRATION_CUE)

        when (val result = collector.finish()) {
            is CalibrationResult.Success -> {
                calibrationStore.save(result.profile)
                calibration = result.profile
                val th = result.profile.eyeClosedOpenRatioThreshold ?: settings.eyeClosedOpenRatioThreshold
                val gap = result.profile.eyeClosedNormalizedRatio?.let { 1f - it }
                calibrationMessage =
                    if (gap != null && gap < settings.eyeClosedCalibrationMarginalGap) {
                        ("閉眼較正完了: 閾値 %.2f。ただし開閉の差が%.2fと小さく、" +
                            "サングラス越しでは閉眼判定が不安定になります(外せるなら外して較正し直してください)")
                            .format(th, gap)
                    } else {
                        "閉眼較正完了: 閉眼の閾値 %.2f".format(th)
                    }
            }
            is CalibrationResult.Failure -> {
                calibrationMessage = buildEyeClosedCalibrationFailureMessage(result.failure, settings)
            }
        }
        eyeClosedCalibrationExtending = false
        calibrationPhase = CalibrationPhase.OPEN
        uiState = DayMvpUiState.PREFLIGHT
    }

    /**
     * フレームはここを通す。判定し、警告とイベント記録を行う。
     * 診断CSVにも書く(debug buildのみ)。
     */
    val processFrame: (FeatureFrame) -> Unit = processFrame@{ result ->
        // D0-009: 判定・警告・記録はMONITORING中(=運行中)だけ行う。
        // PREFLIGHT/CALIBRATING中は判定を止め、運行として記録もしない。
        val session = activeSession
        if (uiState != DayMvpUiState.MONITORING || session == null) return@processFrame

        val stateMachine = stateMachineRef.get() ?: DayDmsStateMachine(settings).also { created ->
            // 運行をまたいだあくびの持ち越し。引き継ぎは1回だけで、以降はこの状態機械が持ち主になる。
            val carried = carriedYawnHistory.getAndSet(emptyList())
            if (carried.isNotEmpty()) created.restoreYawnHistory(carried)
            stateMachineRef.set(created)
        }
        // 一度20km/hを超えるまでは、高温以外を鳴らさない・記録しない(判定は続ける)。状態機械が
        // 「鳴らしたこと」にしないので、待ち終わった時点で続いていればそこで鳴る(公開前レビュー R-03)。
        val input = DmsInputMapper.from(result, calibration, settings, speedKph).copy(alertsHeld = !alertsArmed)
        val output = stateMachine.process(input)

        // 速度は数値のみ記録する(緯度経度は保持していない)。
        diagnosticLogger.logFrame(
            frame = result,
            output = output,
            firedAlerts = output.firedAlerts,
            unavailableNoticeFired = output.unavailableNoticeFired,
            speedKph = speedKph,
            faultReason = faultReason,
            calibration = calibration,
            alertsArmed = alertsArmed,
        )

        dmsOutput = output

        val previousMs = lastFrameMonotonicMs
        lastFrameMonotonicMs = result.monotonicTimeMs
        val deltaMs = if (previousMs != null) (result.monotonicTimeMs - previousMs).coerceIn(0L, 2_000L) else 0L
        sessionMonitoringMs += deltaMs
        if (result.quality.isValidForDecision) sessionValidMonitoringMs += deltaMs

        output.firedAlerts.forEach { fired ->
            toAlertKind(fired.type, fired.severity)?.let(alertSink::trigger)
            // 発報種別ごとに意味のある継続時間を記録する。回数で成立する
            // こっくり・あくびや、瞬時に成立する高温・故障には継続時間がないため0。
            val durationMs = when (fired.type) {
                AlertType.EYE_CLOSURE -> output.eyeClosureDurationMs
                AlertType.DISTRACTION -> output.distractionDurationMs
                AlertType.DROWSY_NODDING,
                AlertType.DROWSY_NODDING_CANDIDATE,
                AlertType.DROWSY_YAWNING,
                AlertType.THERMAL,
                AlertType.SYSTEM_FAULT,
                -> 0L
            }
            // 発報の瞬間の速度は常に記録する(どの速度域で出た警告かで意味が変わるため)。
            // 位置は設定がONで、かつ**測位が新しいときだけ**添える(EventLocationのKDoc)。
            val eventSpeed = speedKph
            val eventLocation = EventLocation.from(
                enabled = settings.recordLocationOnEvent,
                latitude = speedProvider.lastLatitude,
                longitude = speedProvider.lastLongitude,
                accuracyM = speedProvider.lastAccuracyM,
                fixedAtUtcMs = speedProvider.lastFixedAtUtcMs,
                provider = speedProvider.lastProvider,
                nowUtcMs = result.wallClockUtcMs,
                maxAgeMs = settings.eventLocationMaxAgeMs,
            )
            coroutineScope.launch {
                sessionRepository.recordAlertEvent(
                    sessionId = session.id,
                    fired = fired,
                    durationMs = durationMs,
                    atWallClockUtcMs = result.wallClockUtcMs,
                    qualityFlags = result.quality.flags,
                    confidence = result.confidence,
                    modelVersion = settings.modelVersion,
                    settingsVersion = settings.version,
                    speedKph = eventSpeed,
                    location = eventLocation,
                )
            }
        }
        if (output.unavailableNoticeFired) {
            alertSink.trigger(AlertKind.UNAVAILABLE)
        }
        // 記録は監視不能に入った最初の1回だけ。繰り返しの報知(unavailableRepeatMs)は音だけ。
        if (output.unavailableNoticeFired && !output.unavailableNoticeIsRepeat) {
            coroutineScope.launch {
                sessionRepository.recordUnavailableEvent(
                    sessionId = session.id,
                    durationMs = settings.unavailableWarningMs,
                    atWallClockUtcMs = result.wallClockUtcMs,
                    modelVersion = settings.modelVersion,
                    settingsVersion = settings.version,
                    speedKph = speedKph,
                    location = EventLocation.from(
                        enabled = settings.recordLocationOnEvent,
                        latitude = speedProvider.lastLatitude,
                        longitude = speedProvider.lastLongitude,
                        accuracyM = speedProvider.lastAccuracyM,
                        fixedAtUtcMs = speedProvider.lastFixedAtUtcMs,
                        provider = speedProvider.lastProvider,
                        nowUtcMs = result.wallClockUtcMs,
                        maxAgeMs = settings.eventLocationMaxAgeMs,
                    ),
                )
            }
        }
    }

    // 運行中の表示モード(docs/17)。運行中以外は常に映像(取付位置の調整と較正に要る)。
    val displayMode = MonitoringDisplayMode.effective(
        selected = settings.monitoringDisplayMode,
        monitoring = uiState == DayMvpUiState.MONITORING,
    )
    /** 黒ベース表示中か。文字色・パネル色を減光する。 */
    val nightMode = displayMode != MonitoringDisplayMode.CAMERA
    val panelBackground = if (nightMode) NightBlack else PanelBackground

    /** 設定を1項目変える(ローカル状態を即時反映し、そのキーだけ保存する)。[SettingsUpdater]参照。 */
    val updateSettings: SettingsUpdater = { updated, persist ->
        settings = updated
        coroutineScope.launch { settingsStore.persist() }
    }
    fun setDisplayMode(mode: MonitoringDisplayMode) {
        updateSettings(settings.copy(monitoringDisplayMode = mode)) { setMonitoringDisplayMode(mode) }
    }

    /** 較正を始める。段階(開眼10秒 / 閉眼3秒)は呼び出し側が決める。 */
    fun startCalibration(phase: CalibrationPhase) {
        calibrationPhase = phase
        uiState = DayMvpUiState.CALIBRATING
    }

    LaunchedEffect(uiState, monitoringArming) {
        val arming = monitoringArming ?: return@LaunchedEffect
        while (isActive && uiState == DayMvpUiState.MONITORING) {
            alertsArmed = arming.update(SystemClock.elapsedRealtime(), speedKph, locationPermissionGranted)
            delay(500L)
        }
    }

    /** 運行開始(SES-001/003)。前検査パネルの「運行開始」から呼ばれる。 */
    fun startSession() {
        // 設定画面で変更された閾値を確実に反映するため、cooldown等を内部に
        // 持つ状態機械は使い回さず、運行開始のたびに作り直す。
        // **ただしあくびの窓だけは引き継ぐ**(眠気は運行の区切りと無関係に続くため)。
        stateMachineRef.getAndSet(null)?.exportYawnHistory()?.takeIf { it.isNotEmpty() }
            ?.let { carriedYawnHistory.set(it) }
        // 取付回転はここで固定する。運行中に変わると解析の座標系がずれる。
        sessionMountRotationDegrees = manualMountOverrideDegrees ?: 0
        monitoringArming = MonitoringArming(settings, SystemClock.elapsedRealtime())
        alertsArmed = false
        dmsOutput = null
        sessionMonitoringMs = 0L
        sessionValidMonitoringMs = 0L
        lastFrameMonotonicMs = null
        coroutineScope.launch {
            val session = sessionRepository.startSession(
                modelVersion = settings.modelVersion,
                settingsVersion = settings.version,
            )
            activeSession = session
            diagnosticLogger.start(session.id)
            uiState = DayMvpUiState.MONITORING
        }
    }


    /**
     * 運行終了(SES-006)。運行中の「長押しで終了」と、戻るボタンの「終了しますか？」(2026-10-16)から呼ぶ。
     * [onEnded]は運行の記録を閉じたあとに呼ぶ(アプリを閉じるときに使う)。
     */
    fun endSession(onEnded: () -> Unit = {}) {
        val session = activeSession ?: run { onEnded(); return }
        diagnosticLogger.stop()
        sessionMountRotationDegrees = null
        coroutineScope.launch {
            val ended = sessionRepository.endSession(session, sessionMonitoringMs, sessionValidMonitoringMs)
            resultSummary = sessionRepository.summarize(ended)
            resultSessionId = ended.id
            resultEvents = sessionRepository.eventsFor(ended.id)
            exportMessage = null
            activeSession = null
            uiState = DayMvpUiState.RESULT
            onEnded()
        }
    }

    /** カメラのズームを変える。顔の大きさ・角度の基準が変わるので、較正を消す。 */
    fun changeFrontZoom(ratio: Float) {
        updateSettings(settings.copy(frontCameraZoomRatio = ratio)) { setFrontCameraZoomRatio(ratio) }
        coroutineScope.launch { calibrationStore.clear() }
        calibrationMessage = "カメラのズームを変えたので、較正し直してください"
    }

    /**
     * 「運行開始」を押した。位置情報が未許可なら、まず許可を促す(2026-10-17)。
     * 未許可のままだと速度が分からず、20km/hまで警告を止められないため。
     */
    fun requestStartSession() {
        if (!locationPermissionGranted && !settings.locationPromptSuppressed) {
            showLocationPrompt = true
        } else {
            startSession()
        }
    }

    // 位置情報の許可の結果が返ったら、確認から来た運行開始を進める(許可されてもされなくても始める)。
    LaunchedEffect(locationResultCount) {
        if (startAfterLocationRequest) {
            startAfterLocationRequest = false
            startSession()
        }
    }

    if (showLocationPrompt) {
        var dontAskAgain by remember { mutableStateOf(false) }
        fun close(suppress: Boolean) {
            showLocationPrompt = false
            if (suppress) {
                updateSettings(settings.copy(locationPromptSuppressed = true)) { setLocationPromptSuppressed(true) }
            }
        }
        AlertDialog(
            onDismissRequest = { showLocationPrompt = false },
            title = { Text("位置情報を許可してください") },
            text = {
                Column {
                    Text(
                        "許可すると、運行開始後に時速${settings.armingSpeedKph.toInt()}kmを超えるまで警告を止め、" +
                            "速度に応じて判定します(位置は速度の判定に使います)。" +
                            "許可しない場合は速度が分からないため、運行開始からすぐ警告します。",
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .heightIn(min = 48.dp)
                            .clickable { dontAskAgain = !dontAskAgain },
                    ) {
                        Checkbox(checked = dontAskAgain, onCheckedChange = { dontAskAgain = it })
                        Text("次回から表示しない")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    close(dontAskAgain)
                    startAfterLocationRequest = true
                    locationPermissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                }) { Text("許可する") }
            },
            dismissButton = {
                TextButton(onClick = {
                    close(dontAskAgain)
                    startSession()
                }) { Text("許可しないで使う") }
            },
        )
    }

    // 戻るボタン(2026-10-16)。開いている詳細・閾値設定・注意事項はそれを閉じ、そうでなければ終了を確かめる。
    // 以前は確認なしにアプリが閉じ、運行中なら監視が止まっていた。小窓(PiP)では戻るボタンは届かない。
    BackHandler(enabled = !isInPictureInPicture) {
        when {
            showSettings -> showSettings = false
            showDriverNotice -> showDriverNotice = false
            showAbout -> showAbout = false
            showAdvanced -> showAdvanced = false
            else -> showExitConfirm = true
        }
    }
    if (showExitConfirm) {
        val monitoring = uiState == DayMvpUiState.MONITORING
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("終了しますか？") },
            text = {
                Text(
                    if (monitoring) {
                        "運行中です。終了すると監視が止まり、運行は終了として記録されます。"
                    } else {
                        "アプリを終了します。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    endSession { exitAppNow(context) }
                }) { Text("終了する") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("キャンセル") }
            },
        )
    }

    Surface(modifier = modifier.fillMaxSize()) {
        // 「映像(上・残りの高さ)」+「操作パネル(下・不透明)」の縦分割(docs/17 §3-1)。
        // 以前は映像の上に半透明(黒35%)でパネルを載せていたため、12sp中心の文字が
        // 動画と混ざって読めなかった。パネルは画面の一定割合までに抑え、映像を必ず残す。
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val panelMaxHeight = maxHeight * PANEL_MAX_HEIGHT_FRACTION
            Column(modifier = Modifier.fillMaxSize()) {
                // ===================== 上: 映像領域 =====================
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(if (nightMode) NightBlack else Color.Transparent),
                ) {
                    if (hasCameraPermission) {
                        // 黒ベース表示(docs/17 §2-2)では**プレビューViewは残したまま**上に黒を被せる。
                        // CameraXのPreviewはSurfaceを要求するので、Viewを外すと解析経路まで変わってしまう。
                        // 被せるだけなら解析には一切影響しない。
                        Box(modifier = Modifier.fillMaxSize()) {
                            CameraPreview(
                                settings = settings,
                                calibrationBaseline = calibration,
                                mountRotationDegrees = resolvedMountRotationDegrees,
                                faultMonitor = faultMonitor,
                                phoneDetector = phoneDetector,
                                onFrame = { result ->
                                    frame = result
                                    if (result != null) thermalHot = uiThermalWatch.update(result.thermalStatus)
                                    result?.let(processFrame)
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (nightMode) {
                                Box(modifier = Modifier.fillMaxSize().background(NightBlack))
                            }
                            if (displayMode != MonitoringDisplayMode.INFO_ONLY) {
                                FaceOverlay(frame = frame, modifier = Modifier.fillMaxSize(), dim = nightMode)
                            }
                        }
                        // 「情報のみ」: 黒地の中央に大きく出す。運転席から一瞥して分かる大きさにする。
                        if (displayMode == MonitoringDisplayMode.INFO_ONLY) {
                            InfoOnlyStatus(
                                output = dmsOutput,
                                monitoringMs = sessionMonitoringMs,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                        // 判定に使えないときの原因と直し方(2026-10-17)。点の色だけでは理由が分からなかった。
                        // 小窓(PiP)では出さない(狭い枠に詰め込まれて読めなかった。2026-10-17)。
                        val advice = frame?.takeIf { uiState != DayMvpUiState.RESULT && !isInPictureInPicture }?.let { f ->
                            QualityAdvice.of(
                                quality = f.quality,
                                faceSizeRatio = f.faceSizeRatio,
                                fps = f.fps,
                                zoomAvailable = settings.frontCameraZoomRatio < DayMvpSettings.FRONT_ZOOM_CHOICES.last(),
                            )
                        }
                        advice?.let {
                            QualityAdviceBanner(it, modifier = Modifier.align(Alignment.BottomCenter), dim = nightMode)
                        }
                    } else {
                        CameraPermissionRequest(
                            permanentlyDenied = cameraPermissionPermanentlyDenied,
                            onRequest = onRequestCameraPermission,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // ===================== 下: 操作パネル(不透明) =====================
                // PiP(小窓)中はパネルを畳む。文字が小さすぎて読めず、映像も潰れるため
                // (docs/18 §14-15)。映像と監視状態、発報オーバーレイだけを残す。
                if (hasCameraPermission && !isInPictureInPicture) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 詳細は中身の量でパネルの高さが変わると映像が上下に揺れるので、開いている間は
                            // 高さを固定する。それ以外は中身の分だけ(上限あり)。
                            .then(
                                if (showAdvanced && uiState == DayMvpUiState.PREFLIGHT) {
                                    Modifier.height(panelMaxHeight)
                                } else {
                                    Modifier.heightIn(max = panelMaxHeight)
                                },
                            )
                            .background(panelBackground)
                            // enableEdgeToEdge()でシステムバーの裏まで描画されるため、下端のボタンが
                            // ナビゲーションバー(戻る/ホーム等)と重なってタップできなくなるのを防ぐ。
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (uiState == DayMvpUiState.PREFLIGHT && showAdvanced) {
                            AdvancedPanel(
                                settings = settings,
                                updateSettings = updateSettings,
                                tab = advancedTab,
                                onTabChange = { advancedTab = it },
                                message = calibrationMessage,
                                resolvedMountRotationDegrees = resolvedMountRotationDegrees,
                                mountRotationLocked = sessionMountRotationDegrees != null,
                                manualMountOverrideDegrees = manualMountOverrideDegrees,
                                sensorDetectedMountDegrees = sensorDetectedMountDegrees,
                                onManualMountOverride = { deg ->
                                    coroutineScope.launch { mountOrientationStore.setManualOverride(deg) }
                                },
                                locationPermissionGranted = locationPermissionGranted,
                                onRequestLocationPermission = {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                        ),
                                    )
                                },
                                // ズームを変えると顔の大きさ・角度の基準が変わるので、較正を消す。
                                onFrontZoomChange = { changeFrontZoom(it) },
                                onCalibrate = { startCalibration(CalibrationPhase.OPEN) },
                                onOpenThresholds = { showSettings = true },
                                onClose = { showAdvanced = false },
                            )
                        } else if (uiState == DayMvpUiState.PREFLIGHT) {
                            PreflightPanel(
                                settings = settings,
                                profile = calibration,
                                frame = frame,
                                volumeRatio = volumeRatio,
                                volumeSufficient = volumeSufficient,
                                speedKph = speedKph,
                                locationPermissionGranted = locationPermissionGranted,
                                resolvedMountRotationDegrees = resolvedMountRotationDegrees,
                                message = calibrationMessage,
                                onPreviewAlertTone = { alertSink.trigger(AlertKind.EYE_CLOSURE_CAUTION) },
                                onSelectDisplayMode = { setDisplayMode(it) },
                                onStartCalibration = { phase -> startCalibration(phase) },
                                onStartSession = { requestStartSession() },
                                onFrontZoomChange = { changeFrontZoom(it) },
                                onOpenAdvanced = { showAdvanced = true },
                                onOpenNotice = { showDriverNotice = true },
                                onOpenAbout = { showAbout = true },
                                onExit = { exitAppNow(context) },
                            )
                        }

                        if (uiState == DayMvpUiState.MONITORING) {
                            MonitoringPanel(
                                output = dmsOutput,
                                monitoringMs = sessionMonitoringMs,
                                frame = frame,
                                speedKph = speedKph,
                                showLiveReadout = settings.testModeIgnoreSpeedGate,
                                notice = if (!alertsArmed) {
                                    "時速${settings.armingSpeedKph.toInt()}kmを超えると警告を開始します"
                                } else if (!locationPermissionGranted && sessionMonitoringMs < NO_LOCATION_NOTICE_MS) {
                                    "位置情報が無いため、速度に関係なく警告します"
                                } else {
                                    null
                                },
                                displayMode = displayMode,
                                onCycleDisplayMode = { setDisplayMode(settings.monitoringDisplayMode.next()) },
                                onEndConfirmed = { endSession() },
                            )
                        }

                        if (uiState == DayMvpUiState.RESULT) {
                            ResultPanel(
                                summary = resultSummary,
                                events = resultEvents,
                                exportMessage = exportMessage,
                                onExportCsv = {
                                    coroutineScope.launch {
                                        exportMessage = EventCsvExporter(context)
                                            .export(resultSessionId, resultEvents)
                                    }
                                },
                                onBackToPreflight = {
                                    resultSummary = null
                                    resultEvents = emptyList()
                                    exportMessage = null
                                    uiState = DayMvpUiState.PREFLIGHT
                                },
                            )
                        }

                        if (uiState == DayMvpUiState.CALIBRATING && calibrationPhase == CalibrationPhase.OPEN) {
                            Text(
                                text = "正面を見てください  有効frame ${calibrationValidCount}/${settings.calibrationMinValidFrames}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            LinearProgressIndicator(
                                progress = { calibrationProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            )
                        }
                        if (uiState == DayMvpUiState.CALIBRATING && calibrationPhase == CalibrationPhase.EYES_CLOSED) {
                            // 目を閉じているので読めない前提。大きく短く。終わりは音(2回)で知らせる。
                            Text(
                                text = if (eyeClosedCalibrationExtending) "そのまま閉じていてください" else "目を閉じてください",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            Text(
                                text = "音が2回鳴ったら開けてください",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFCFD8DC),
                            )
                            LinearProgressIndicator(
                                progress = { calibrationProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            // ALT-005/006: 警告は画面分割や表示モードの影響を受けず常に全画面で出す(安全上、視認性を落とさない)。
            // 3.5秒で自動的に消える(AndroidAlertSink)。この間は下の操作パネルも隠れるが、
            // タッチは通るので長押し中の終了操作は途切れない。
            if (hasCameraPermission) {
                AlertOverlay(alertKind = activeAlert, modifier = Modifier.fillMaxSize())
            }

            // 画面上部の見出し・版・状態バナー。操作ボタン群(下段)と分けて、
            // 情報と操作が1つの塊に積み上がらないようにする。
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 上部は縦を使わない。以前はタイトルと版を2行に分けていたが、
                // バナーと診断テキストが加わると画面上部が埋まり、
                // プレビューの札とも重なって読めなくなっていた(2026-09-07に実機で指摘)。
                // アプリ版はSession/Eventにも記録している(NFR-009)が、実機試験中に
                // 「今どのビルドが入っているか」を画面で即座に確認できるようにここにも出す。
                Text(
                    text = "Open DMS Lite  v${BuildConfig.VERSION_NAME}" +
                        (if (BuildConfig.DEBUG) " debug" else "") + "  ·  ${uiState.name}",
                    style = MaterialTheme.typography.labelMedium,
                    // 黒ベース表示中は光源を減らすため落とす。
                    color = if (nightMode) Color(0xFFFFCC80).copy(alpha = 0.45f) else Color(0xFFFFCC80),
                )

                if (!hasCameraPermission) {
                    StatusBanner("カメラ権限が必要です", Color(0xFFB71C1C))
                }
                // 故障表示(ASV眠気4章/脇見4章)。立ち上がりで鳴らした後も、故障が続く間は出し続ける。
                faultReason?.let { reason ->
                    StatusBanner(
                        text = "監視機能が使用できません(${reason.label()})\n" +
                            "安全な場所でアプリを再起動してください",
                        background = Color(0xFFB71C1C),
                    )
                }
                // 誤ってこのまま実運用へ入らないよう、ONの間は常時表示する。
                if (settings.testModeIgnoreSpeedGate) {
                    StatusBanner(
                        text = "動作確認モード ON  速度に関係なく脇見を判定します",
                        background = Color(0xFF6A1B9A),
                    )
                }

                // 診断テキストはバナーの「下」に積む。以前はFaceOverlay側で画面上部へ
                // 絶対配置していたため、バナーが出るたびに重なって両方読めなくなっていた
                // (2026-08-31に実機のスクリーンショットで確認)。
                // 「情報のみ」では出さない(監視できているかは中央の状態行で分かる)。「顔座標」では減光する。
                if (hasCameraPermission && displayMode != MonitoringDisplayMode.INFO_ONLY && !isInPictureInPicture) {
                    // 普段は簡単な表示だけを大きめに出し、NGは色を変える(2026-10-17、利用者の要望)。
                    // 数値は詳細 → 端末・診断のスイッチを入れたときだけ。
                    Column(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (nightMode) Color.Transparent else Color.Black.copy(alpha = 0.5f))
                            .padding(8.dp),
                    ) {
                        if (settings.showDiagnosticDetails) {
                            Text(
                                text = diagnosticDetailsText(frame),
                                color = if (nightMode) NightTextDim else Color.White,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            text = checklistText(frame, thermalHot, ngColor = if (nightMode) NightTextDim else AccentAmber),
                            color = if (nightMode) NightTextDim else Color.White,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            // 画面上部の見出し・診断テキストより後に書く(後に書いたものが前面に出る。先に書くと、それらが設定パネルの上に重なる)。
            if (showSettings) {
                SettingsPanel(
                    current = settings,
                    onSave = { updated ->
                        coroutineScope.launch {
                            settingsStore.saveTunable(updated)
                            showSettings = false
                        }
                    },
                    onResetDefault = {
                        coroutineScope.launch {
                            settingsStore.resetTunable()
                            showSettings = false
                        }
                    },
                    onDismiss = { showSettings = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (showAbout) {
                AboutPanel(onDismiss = { showAbout = false }, modifier = Modifier.fillMaxSize())
            }

            if (showDriverNotice) {
                DriverNoticePanel(
                    recordLocation = settings.recordLocationOnEvent,
                    onDismiss = { showDriverNotice = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * ドライバーへの周知5項目(ASV眠気5章/脇見5章)を全文表示する。
 *
 * ASVは提示方法を「取扱説明書、表示等により」としか定めていないため、運転前に読める場所として
 * PREFLIGHTから開く形にした。運行中は表示しない(走行中の操作・読み物を要求しない)。
 * 文言は[DriverNotice]が持つ。
 */
@Composable
private fun DriverNoticePanel(
    recordLocation: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = Color(0xFF101418)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                text = "ご使用の前に",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            DriverNotice.sections(recordLocation).forEach { section ->
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFFFCC80),
                    modifier = Modifier.padding(top = 20.dp),
                )
                Text(
                    text = section.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Button(onClick = onDismiss, modifier = Modifier.padding(top = 28.dp)) {
                Text("閉じる")
            }
        }
    }
}

/** [CalibrationFailure]を、除外理由の内訳付きで運転者にも開発者にも分かる短文へ変換する(3.3)。 */
/**
 * 閉眼較正の失敗理由。**次に何をすればよいか**が分かる文にする。
 * 「目を閉じていない/サングラス」は較正の時点で分かる最も価値のある失敗なので、はっきり言う。
 */
private fun buildEyeClosedCalibrationFailureMessage(failure: CalibrationFailure, settings: DayMvpSettings): String {
    val fallback = "既定の閾値 %.2f で判定します。「閉眼較正」でやり直せます。".format(settings.eyeClosedOpenRatioThreshold)
    return when (failure.reason) {
        CalibrationFailureReason.EYES_NOT_CLOSED_OR_OCCLUDED ->
            "閉眼較正できず: 目を閉じても開眼比が%.2fまでしか下がりません。目を閉じていないか、サングラス等で瞼が読めません。$fallback"
                .format(failure.observedClosedRatio ?: 1f)
        CalibrationFailureReason.UNSTABLE ->
            "閉眼較正できず: 値が安定しませんでした(瞬き・半開き)。音が2回鳴るまで閉じたままにしてください。$fallback"
        CalibrationFailureReason.INSUFFICIENT_VALID_FRAMES ->
            "閉眼較正できず: 顔が取れたフレームが不足(${failure.validFrameCount}/${failure.requiredFrameCount})。$fallback"
    }
}

private fun buildCalibrationFailureMessage(failure: CalibrationFailure): String {
    val reasons = failure.excludedByFlag.entries
        .sortedByDescending { it.value }
        .joinToString(" ") { (flag, count) -> "${flag.label()}x$count" }
    val reasonSuffix = if (reasons.isNotEmpty()) "  除外: $reasons" else ""
    return "較正やり直し: 有効frame ${failure.validFrameCount}/${failure.requiredFrameCount}不足$reasonSuffix"
}

/** 画面上部に出す短い状態バナー(権限不足・故障・動作確認モード)。色で種別を区別する。 */
/**
 * カメラ権限が無いときの案内(公開前レビュー R-07)。以前は「カメラ権限が必要です」の帯だけで、
 * 一度拒否するとアプリから直す方法がなかった。
 */
@Composable
private fun CameraPermissionRequest(permanentlyDenied: Boolean, onRequest: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 端末のテーマに関係なく白文字が読めるよう、パネルと同じ暗い背景を敷く。
    Column(
        modifier = modifier.background(PanelBackground).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            "目と顔の向きから眠気・脇見を判定するため、カメラの許可が必要です。映像は保存も送信もしません。",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
        )
        if (permanentlyDenied) {
            Text(
                "端末の設定の「権限」で、カメラを「アプリの使用中のみ許可」にしてください。",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            SecondaryButton("設定を開く") {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }
        } else {
            SecondaryButton("カメラを許可する", onClick = onRequest)
        }
    }
}

@Composable
private fun StatusBanner(text: String, background: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * 故障の定期評価ループ。呼び出し側で`repeatOnLifecycle(RESUMED)`の中から回すこと
 * (前面にいない間はカメラが止まるのが正常で、それを故障として報知してはならない)。
 *
 * 音・振動は**正常→故障の立ち上がりで1回だけ**鳴らす。故障が続く間は画面の赤帯で知らせ続ける。
 * ASV眠気4章/脇見4章が求めるのは「故障をドライバーが認識できる手段」であって反復報知ではなく、
 * 運転中に鳴り続けると運転の妨げになるため。
 */
private suspend fun faultTickLoop(
    faultMonitor: FaultMonitor,
    currentReason: () -> FaultReason?,
    onReason: (FaultReason?) -> Unit,
    onFaultRising: suspend (FaultReason) -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        val previous = currentReason()
        val current = faultMonitor.evaluate(SystemClock.elapsedRealtime())
        onReason(current)
        if (previous == null && current != null) onFaultRising(current)
        delay(500L)
    }
}

/**
 * アプリを終了する。
 *
 * `Runtime.exit(0)`は使わない。それだとComposeの
 * `DisposableEffect.onDispose`が走らず、カメラ・AudioTrack・位置情報が解放されないまま
 * プロセスが消えるため。[android.app.Activity.finishAndRemoveTask]なら通常の破棄経路を通り、
 * 既存の`onDispose`(`visionSource.shutdown()`/`alertSink.release()`/`speedProvider.stop()`等)が
 * すべて実行される。
 */
private fun exitAppNow(context: Context) {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (c is Activity) {
            c.finishAndRemoveTask()
            return
        }
        c = c.baseContext
    }
}

/** DmsStateMachineの発報型からAlertSinkの種別へ変換する(D0-008)。対応しない組み合わせはnull。 */
private fun toAlertKind(type: AlertType, severity: AlertSeverity): AlertKind? = when (type to severity) {
    AlertType.EYE_CLOSURE to AlertSeverity.CAUTION -> AlertKind.EYE_CLOSURE_CAUTION
    AlertType.EYE_CLOSURE to AlertSeverity.WARNING -> AlertKind.EYE_CLOSURE_WARNING
    AlertType.DISTRACTION to AlertSeverity.WARNING -> AlertKind.DISTRACTION_WARNING
    AlertType.DROWSY_NODDING to AlertSeverity.CAUTION -> AlertKind.DROWSY_NODDING
    AlertType.DROWSY_YAWNING to AlertSeverity.CAUTION -> AlertKind.DROWSY_YAWNING
    AlertType.THERMAL to AlertSeverity.CAUTION -> AlertKind.THERMAL
    AlertType.SYSTEM_FAULT to AlertSeverity.WARNING -> AlertKind.SYSTEM_FAULT
    else -> null
}

/**
 * 全画面の短い警告表示。ALT-005: 短い日本語だけを大きく表示し、操作(タップ)を要求しない。
 * [AndroidAlertSink]が一定時間後に自動でnullへ戻すため、ここでは表示するだけでよい。
 * ALT-006: 監視不能は眠気・脇見警告と混同しない色にする。
 */
@Composable
private fun AlertOverlay(alertKind: AlertKind?, modifier: Modifier = Modifier) {
    val kind = alertKind ?: return
    val (message, background) = when (kind) {
        // 較正の合図は警告ではないので、画面には何も出さない。
        AlertKind.CALIBRATION_CUE -> return
        AlertKind.EYE_CLOSURE_CAUTION -> "休憩を検討してください" to Color(0xFFF9A825)
        AlertKind.EYE_CLOSURE_WARNING -> "安全な場所で休憩してください" to Color(0xFFC62828)
        AlertKind.DISTRACTION_WARNING -> "前を確認してください" to Color(0xFFD84315)
        AlertKind.DROWSY_NODDING -> "眠気の兆候があります 休憩してください" to Color(0xFFE65100)
        // あくびはASV眠気2.4.2の「煩わしくない手段」に合わせ、他の眠気警告より穏やかな文言・色にする。
        AlertKind.DROWSY_YAWNING -> "あくびが続いています 休憩を検討してください" to Color(0xFFFBC02D)
        AlertKind.UNAVAILABLE -> "顔を確認できません" to Color(0xFF37474F)
        AlertKind.THERMAL -> "端末が高温です" to Color(0xFF6A1B9A)
        // 故障(ASV眠気4章/脇見4章)。音は監視不能と同じなので、文言と色で明確に区別する。
        AlertKind.SYSTEM_FAULT -> "監視機能が使用できません" to Color(0xFFB71C1C)
    }
    Box(
        modifier = modifier.background(background.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(24.dp),
        )
    }
}

/**
 * 監視状態の文言と色。黒ベース表示中([night])は暗い色に落とす(docs/17)。
 */
private fun availabilityLabel(output: DmsOutput?, night: Boolean): Pair<String, Color> =
    when (output?.availability) {
        MonitoringAvailability.LIMITED ->
            "品質低下" to (if (night) NightStatusCaution else Color(0xFFF9A825))
        MonitoringAvailability.UNAVAILABLE ->
            "監視できません" to (if (night) NightStatusUnavailable else Color(0xFF616161))
        else -> "監視中" to (if (night) NightStatusNormal else Color(0xFF2E7D32))
    }

/**
 * 「情報のみ」(docs/17 §2-1)の中央表示。黒地に「DMS起動中」と監視状態・経過だけを出す。
 * 監視できていないときも見出しは変えず、2行目の文言と色で伝える。
 */
@Composable
private fun InfoOnlyStatus(output: DmsOutput?, monitoringMs: Long, modifier: Modifier = Modifier) {
    val (label, color) = availabilityLabel(output, night = true)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            // 小窓(PiP)では幅が狭く「DMS起動 / 中」と折り返していたので、語の切れ目で改行する(2026-10-16)。
            text = "DMS\n起動中",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.displaySmall,
            color = NightText,
        )
        Text(
            text = "$label  ·  経過 ${formatDuration(monitoringMs)}",
            style = MaterialTheme.typography.titleMedium,
            color = color,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * MONITORING画面(A-07)。3.1: 「通常、注意、強い警告、監視不能を大きい文字と色・アイコンで表示する」。
 * SES-006: 終了は2秒長押し(誤操作防止)で、走行中のタップ操作は要求しない。
 *
 * 表示モード(docs/17)が[MonitoringDisplayMode.INFO_ONLY]のときは、状態と経過は映像領域の中央
 * ([InfoOnlyStatus])に出るので、ここでは切替ボタンと終了だけを出す。
 */
@Composable
private fun MonitoringPanel(
    output: DmsOutput?,
    monitoringMs: Long,
    /** 直近フレーム。動作確認モードのライブ表示にだけ使う(通常運用では読まない)。 */
    frame: FeatureFrame? = null,
    speedKph: Float? = null,
    /** ライブ表示を出すか。動作確認モードのときに出す。 */
    showLiveReadout: Boolean = false,
    /** 運転者に知らせること(警告を開始するまでの案内)。 */
    notice: String? = null,
    displayMode: MonitoringDisplayMode = MonitoringDisplayMode.CAMERA,
    /** 運行中の表示切替(映像→顔座標→情報のみ→…)。夕方の走行中に暗くなったときに押す。 */
    onCycleDisplayMode: () -> Unit = {},
    onEndConfirmed: () -> Unit,
) {
    val night = displayMode != MonitoringDisplayMode.CAMERA
    val (label, color) = availabilityLabel(output, night)
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (displayMode != MonitoringDisplayMode.INFO_ONLY) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.headlineSmall,
                    color = color,
                )
                Text(
                    text = "経過 ${formatDuration(monitoringMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (night) NightTextDim else Color.White,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // 表示モードに関係なく出す。
            notice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AccentAmber,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // 小さく隅に置く。走行中の操作を要求するものではない(押さなくても運行は続く)。
        DisplayModeButton(mode = displayMode, night = night, onClick = onCycleDisplayMode)
    }
    // 動作確認モードのライブ表示。あくびとスマホ検出は「成立するまで待つ」種類の判定
    // (あくびは10分3回、スマホ検出は現時点で判定に未使用)なので、警告を待たずに
    // 検知が動いているかを画面で直接確かめられるようにする。通常運用では出さない。
    if (showLiveReadout) {
        val jaw = frame?.jawOpen
        val yawn = output?.yawn
        val phone = frame?.phone
        Text(
            text = listOf(
                "jawOpen " + (jaw?.let { "%.2f".format(it) } ?: "-"),
                "あくび " + (yawn?.let { "${it.countInWindow}回 (開口${it.currentOpenDurationMs}ms)" } ?: "-"),
                "スマホ " + when {
                    phone == null -> "-"
                    !phone.detected -> "なし"
                    else -> "検出 score=${"%.2f".format(phone.score ?: 0f)} " +
                        "面積=${"%.3f".format(phone.areaRatio ?: 0f)}"
                },
                // 脇見は速度ゲートで警告が止まっていても、生の継続時間は伸び続ける。
                // 停車中に検知そのものが動いているかを画面で確かめられるようにする。
                "脇見 " + (output?.distractionRawDurationMs ?: 0L) + "ms" +
                    (if (output?.distractionSpeedGateOpen == false) "(ゲート閉・記録のみ)" else ""),
                "速度 " + (speedKph?.let { "${it.toInt()}km/h" } ?: "未取得"),
            ).joinToString("   "),
            style = MaterialTheme.typography.labelMedium,
            color = if (night) NightTextDim else Color(0xFFCE93D8),
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    LongPressToEndButton(holdMs = 2_000L, onConfirmed = onEndConfirmed, dim = night)
}

/** 運行中の表示モードの切替ボタン。現在のモードを札として見せ、押すと次へ巡回する。 */
@Composable
private fun DisplayModeButton(mode: MonitoringDisplayMode, night: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (night) NightTextDim else Color.White,
        ),
        border = BorderStroke(1.dp, if (night) NightTextDim.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f)),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.heightIn(min = 40.dp),
    ) {
        Text("表示: ${mode.label()}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** RESULT画面(A-09)。SES-008: 監視時間、有効監視率、警告件数、同期状態(MVP-0は通信なしのため対象外)。 */
@Composable
private fun ColumnScope.ResultPanel(
    summary: SessionSummary?,
    /** この運行の発報。時刻の昇順。 */
    events: List<LocalEvent>,
    /** CSV書き出し。書き出し先のパスを返す(失敗ならnull)。 */
    onExportCsv: () -> Unit,
    exportMessage: String?,
    onBackToPreflight: () -> Unit,
) {
    if (summary == null) {
        Text("集計中…", color = Color.White, modifier = Modifier.padding(top = 8.dp))
        return
    }
    Text(
        text = "監視時間 ${formatDuration(summary.monitoringMs)}  " +
            "有効監視率 ${(summary.validMonitoringRatio * 100).toInt()}%",
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = "閉眼注意 ${summary.eyeClosureCautionCount}件  閉眼強 ${summary.eyeClosureWarningCount}件  " +
            "脇見 ${summary.distractionWarningCount}件  監視不能 ${summary.unavailableCount}件",
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        modifier = Modifier.padding(top = 4.dp),
    )
    // **発報の一覧**(docs/16 §2 P0-3)。
    // 集計だけでは「いつ・何が・何km/hで鳴ったか」が分からず、
    // 参考録画(mp4)と突き合わせて妥当性を判断できない。
    // mp4と診断CSVは同じsessionIdなので、ここに時刻が出れば該当箇所を見に行ける。
    if (events.isEmpty()) {
        Text(
            text = "発報なし",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB0BEC5),
            modifier = Modifier.padding(top = 10.dp),
        )
    } else {
        Text(
            text = "発報 ${events.size}件(時刻 · 種別 · 速度 · 位置)",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.padding(top = 12.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            events.forEach { e ->
                Text(
                    text = eventLineText(e),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (e.severity == "WARNING") Color(0xFFFFAB91) else Color(0xFFCFD8DC),
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }

    Text(
        text = "アプリ版 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFFB0BEC5),
        modifier = Modifier.padding(top = 8.dp),
    )
    exportMessage?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF80CBC4),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (events.isNotEmpty()) {
            SecondaryButton("発報をCSVで書き出す", Modifier.weight(1f)) { onExportCsv() }
        }
        Button(onClick = onBackToPreflight, modifier = Modifier.weight(1f)) {
            Text("戻る")
        }
    }
}

/**
 * 一覧の1行。**後からmp4を見に行くための手がかり**を並べる。
 *
 * 位置は「その場所で起きたと言えるか」が分かるように、精度と古さを添える。
 * 位置が無い行は、設定がOFFか、測位が古すぎて捨てられたか
 * ([com.trypro.opendms.domain.EventLocation])。
 */
private fun eventLineText(e: LocalEvent): String {
    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.JAPAN)
        .apply { timeZone = java.util.TimeZone.getDefault() }
        .format(java.util.Date(e.endedAtUtcMs))
    val kind = alertTypeLabel(e.type) + if (e.severity == "WARNING") "(強)" else ""
    val speed = e.speedKph?.let { "%.0fkm/h".format(it) } ?: "速度なし"
    val place = when {
        e.latitude == null || e.longitude == null -> "位置なし"
        else -> {
            val acc = e.locationAccuracyM?.let { "±%.0fm".format(it) } ?: "精度不明"
            val ageS = e.locationFixedAtUtcMs?.let { (e.endedAtUtcMs - it) / 1000 }
            val age = if (ageS != null && ageS >= 5) " ${ageS}秒前" else ""
            "GPS $acc$age"
        }
    }
    return "$time  $kind  $speed  $place"
}

/** 種別の短い日本語。CSVには`type`をそのまま出すので、これは表示だけに使う。 */
private fun alertTypeLabel(type: String): String = when (type) {
    "EYE_CLOSURE" -> "閉眼"
    "DISTRACTION" -> "脇見"
    "DROWSY_NODDING" -> "こっくり"
    "DROWSY_NODDING_CANDIDATE" -> "こっくり候補(無音)"
    "DROWSY_YAWNING" -> "あくび"
    "THERMAL" -> "高温"
    "UNAVAILABLE" -> "監視不能"
    else -> type
}

/**
 * 指定時間([holdMs])押し続けたら[onConfirmed]を呼ぶボタン。SES-006の「2秒長押し」誤操作防止を
 * 汎用のCompose gestureで実装する(標準Buttonの長押しは既定時間が短く用途に合わないため)。
 */
@Composable
private fun LongPressToEndButton(
    holdMs: Long,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    /** 黒ベース表示中は全体を減光する(docs/17)。押せる位置と大きさは変えない。 */
    dim: Boolean = false,
) {
    var pressProgress by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer { alpha = if (dim) 0.55f else 1f }
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFB71C1C).copy(alpha = 0.85f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val job = scope.launch {
                            val startMs = System.currentTimeMillis()
                            while (isActive) {
                                val elapsed = System.currentTimeMillis() - startMs
                                pressProgress = (elapsed.toFloat() / holdMs).coerceIn(0f, 1f)
                                if (elapsed >= holdMs) {
                                    onConfirmed()
                                    break
                                }
                                delay(16L)
                            }
                        }
                        tryAwaitRelease()
                        job.cancel()
                        pressProgress = 0f
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        LinearProgressIndicator(
            progress = { pressProgress },
            modifier = Modifier.fillMaxSize(),
        )
        Text("長押しで終了", color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * D0-011実地試験前に閾値を変更するための設定画面。11_DAY_SMARTPHONE_MVP_PLAN.md 3.4/3.5の
 * 「閾値はDayMvpSettingsの1箇所へ集約する」対象を全てここで編集可能にする(`version`/`modelVersion`は
 * 識別子のため対象外)。数値として解釈できない入力は保存時に無視し、元の値を維持する(クラッシュ防止)。
 */
private fun QualityFlag.label(): String = when (this) {
    QualityFlag.NO_FACE -> "顔なし"
    QualityFlag.FACE_TOO_SMALL -> "顔小"
    QualityFlag.FACE_TOO_LARGE -> "顔大"
    QualityFlag.ONE_EYE_ONLY -> "片目"
    QualityFlag.LOW_LIGHT -> "暗さ"
    QualityFlag.LOW_FPS -> "低fps"
    QualityFlag.THERMAL_DEGRADED -> "高温"
    QualityFlag.HEAD_ANGLE_TOO_LARGE -> "頭部角度"
    QualityFlag.HEAD_ROTATING_RAPIDLY -> "頭部回転速度"
}

/** 故障の原因を運転者にも意味が伝わる短い日本語にする(ASV眠気4章/脇見4章)。 */
private fun FaultReason.label(): String = when (this) {
    FaultReason.CAMERA_UNAVAILABLE -> "カメラを開けません"
    FaultReason.INFERENCE_FAILING -> "解析が繰り返し失敗しています"
    FaultReason.NO_RESULT -> "解析結果が返りません"
    FaultReason.NO_FRAME -> "カメラ映像が届きません"
}

/**
 * インカメラのPreviewを表示するCompose用ラッパー。カメラ・MediaPipeの取得/解放は
 * Composeのライフサイクルに束縛する。`calibrationBaseline`の変化は[MediaPipeFaceAnalyzer]へ反映する。
 */
@Composable
private fun CameraPreview(
    settings: DayMvpSettings,
    calibrationBaseline: CalibrationProfile?,
    mountRotationDegrees: Int = 0,
    /** 故障検知(ASV眠気4章/脇見4章)。カメラのbind失敗・推論の連続エラー/無応答を記録する。 */
    faultMonitor: FaultMonitor? = null,
    /** スマホ検出の診断計測(D1-020)。release buildではnull。 */
    phoneDetector: PhoneDetector? = null,
    onFrame: (FeatureFrame?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }
    val visionSource = remember { CameraXVisionSource(context) }
    val faceAnalyzer = remember {
        MediaPipeFaceAnalyzer(context, initialSettings = settings, onResult = onFrame)
    }
    SideEffect {
        faceAnalyzer.calibrationBaseline = calibrationBaseline
        // 設定画面で閾値を変更したら、アナライザ・カメラを作り直さずに次フレームから反映させる。
        faceAnalyzer.settings = settings
        // D0-011追加検証: 取付回転(上下逆・横向き)の解析結果への反映。
        // 画面自体はscreenOrientation="portrait"で固定のため、オンスクリーンのプレビュー映像・UI表示は
        // 取付方向に応じて視覚的には回転しない(意図的なスコープ限定。詳細はMountRotationのKDoc参照)。
        faceAnalyzer.mountRotationDegrees = mountRotationDegrees
        faceAnalyzer.faultMonitor = faultMonitor
        faceAnalyzer.phoneDetector = phoneDetector
        phoneDetector?.settings = settings
    }

    DisposableEffect(lifecycleOwner) {
        visionSource.start(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            onFrame = { imageProxy -> faceAnalyzer.analyze(imageProxy, isFrontCamera = true) },
            onBindResult = { bound ->
                if (bound) faultMonitor?.onCameraBound(SystemClock.elapsedRealtime()) else faultMonitor?.onCameraBindFailed()
            },
        )
        onDispose {
            visionSource.shutdown()
            faceAnalyzer.close()
        }
    }
    LaunchedEffect(settings.frontCameraZoomRatio) {
        visionSource.setZoomRatio(settings.frontCameraZoomRatio)
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/**
 * landmark/両目/頭部姿勢候補・FPS/推論時間/thermal・事前点検チェックリストを描画する。
 * D0-005: [SignalQualityEvaluator]の判定(顔なし/顔小/片目/暗さ/低fps)をチェックリストとして区別表示する。
 * VIS-005に沿い、`quality.isValidForDecision == false`の間はlandmark overlayを警告色にして
 * 「判定に使われていない」ことを視覚的にも分かるようにする(実際にタイマーへ使わない実装はD0-007)。
 */
@Composable
private fun FaceOverlay(
    frame: FeatureFrame?,
    modifier: Modifier = Modifier,
    /** 黒ベース表示(docs/17)中は点・枠を半透明にして光源を減らす。 */
    dim: Boolean = false,
) {
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = frame ?: return@Canvas
            if (!c.faceDetected || c.imageWidth <= 0 || c.imageHeight <= 0) return@Canvas

            // PreviewViewの既定ScaleType(FILL_CENTER)と同じcenter-crop変換を再現する。
            // これをしないと、解析画像(例: 480x640)とView(端末画面サイズ)のアスペクト比の差分だけ
            // landmarkが実際の顔からズレて表示される。
            //
            // landmark座標はMediaPipeへ渡した「取付回転込みで補正済み」の画像
            // (c.imageWidth/imageHeight)の座標系だが、PreviewViewは取付回転を知らず、
            // カメラ本来の向き+ミラーだけが効いた「生」の映像を表示している。
            // 取付回転の分だけ逆回転して生の座標系へ戻してからFILL_CENTER変換する。
            val viewW = size.width
            val viewH = size.height
            val mountDeg = c.mountRotationDegrees
            val swapped = MountRotation.swapsDimensions(mountDeg)
            // 90/270°では取付回転により幅と高さが入れ替わっているため、生の映像(PreviewView)の
            // アスペクト比に合わせて戻す。
            val imgW = if (swapped) c.imageHeight.toFloat() else c.imageWidth.toFloat()
            val imgH = if (swapped) c.imageWidth.toFloat() else c.imageHeight.toFloat()
            val scale = maxOf(viewW / imgW, viewH / imgH)
            val offsetX = (viewW - imgW * scale) / 2f
            val offsetY = (viewH - imgH * scale) / 2f

            fun toViewOffset(nx: Float, ny: Float): Offset {
                val (rawX, rawY) = MountRotation.undoRotation(nx, ny, mountDeg)
                return Offset(rawX * imgW * scale + offsetX, rawY * imgH * scale + offsetY)
            }

            val overlayAlpha = if (dim) 0.45f else 1f
            val landmarkColor =
                (if (c.quality.isValidForDecision) Color(0xFF66FF99) else Color(0xFFFF8A65)).copy(alpha = overlayAlpha)
            val eyeColor =
                (if (c.quality.isValidForDecision) Color(0xFFFFEB3B) else Color(0xFFFFCC80)).copy(alpha = overlayAlpha)

            // 顔の特徴点(座標)を描く。運転者が「カメラに正しく写っているか」を自分で確認して
            // 取付位置を直せるようにするため(ASV眠気5章②の周知の一部でもある)。
            // 点は画面表示だけで、保存はしない。
            c.landmarks.forEach { lm ->
                drawCircle(
                    color = landmarkColor,
                    radius = 1.2.dp.toPx(),
                    center = toViewOffset(lm.x(), lm.y()),
                )
            }
            val eyeIndices = FeatureFrame.LEFT_EYE_INDICES + FeatureFrame.RIGHT_EYE_INDICES
            eyeIndices.forEach { index ->
                c.landmarks.getOrNull(index)?.let { lm ->
                    drawCircle(
                        color = eyeColor,
                        radius = 2.5.dp.toPx(),
                        center = toViewOffset(lm.x(), lm.y()),
                    )
                }
            }
        }

    }
}

/** 画面上部の数値(調査用。詳細 → 端末・診断のスイッチで出す)。 */
private fun diagnosticDetailsText(frame: FeatureFrame?): String {
    val f = frame ?: return "解析待機中"
    val faceLine = if (f.faceDetected) {
        "顔候補: 検出中  yaw=${f.headYawDeg?.format1()}  " +
            "pitch=${f.headPitchDeg?.format1()}  roll=${f.headRollDeg?.format1()}\n" +
            "eyeOpen L=${f.eyeLeftOpen?.format2()} R=${f.eyeRightOpen?.format2()}  " +
            "jawOpen=${f.jawOpen?.format2()}  faceSize=${f.faceSizeRatio?.format2()}"
    } else {
        "顔候補: 検出なし"
    }
    return "$faceLine\n" +
        "fps=${f.fps.format1()} 推論=${f.inferenceTimeMs}ms " +
        "輝度=${f.luminance?.format1()} thermal=${f.thermalStatus}\n" +
        "品質score=${f.quality.score.format2()} valid=${f.quality.isValidForDecision}"
}

/**
 * 画面上部の簡単な表示(D0-005 Gate: 5条件を区別できること)。**NGの項目は色を変えて目立たせる**(2026-10-17)。
 * 顔サイズは%も出す。直し方は映像の下端の帯([QualityAdviceBanner])に出す。
 * 高温は端末の申告の絶対値ではなく、見てきた最低値より上がったときだけNG(Full の docs/19 §6-6)。
 */
private fun checklistText(frame: FeatureFrame?, thermalHot: Boolean, ngColor: Color): AnnotatedString {
    val f = frame ?: return AnnotatedString("解析待機中")
    val quality = f.quality
    val faceSize = f.faceSizeRatio?.let { "(${(it * 100).toInt()}%)" } ?: ""
    val items = listOf(
        "顔検出" to (QualityFlag.NO_FACE in quality.flags),
        "顔サイズ" to (QualityFlag.FACE_TOO_SMALL in quality.flags),
        "両目" to (QualityFlag.ONE_EYE_ONLY in quality.flags),
        "明るさ" to (QualityFlag.LOW_LIGHT in quality.flags),
        "fps" to (QualityFlag.LOW_FPS in quality.flags),
        "高温" to thermalHot,
    )
    return buildAnnotatedString {
        items.forEachIndexed { index, (label, ng) ->
            if (index > 0) append("  ")
            val suffix = if (label == "顔サイズ") faceSize else ""
            if (ng) {
                withStyle(SpanStyle(color = ngColor, fontWeight = FontWeight.Bold)) { append("$label:NG$suffix") }
            } else {
                append("$label:OK$suffix")
            }
        }
    }
}

private fun Float.format1(): String = "%.1f".format(this)
private fun Float.format2(): String = "%.2f".format(this)

@Preview(showBackground = true)
@Composable
private fun DayMvpScreenPreflightPreview() {
    OpenDmsTheme {
        DayMvpScreen()
    }
}

/** 位置情報が無いまま運行しているとき、運行開始から案内を出しておく時間。 */
private const val NO_LOCATION_NOTICE_MS = 10_000L
