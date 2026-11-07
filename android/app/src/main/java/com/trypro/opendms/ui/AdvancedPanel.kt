package com.trypro.opendms.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trypro.opendms.data.SettingsStore
import com.trypro.opendms.domain.DayMvpSettings

/**
 * 設定を1項目だけ変えるときの呼び出し。**ローカル状態を即時に更新し、そのキーだけを保存する。**
 *
 * `save()`は全項目を書き込むので、その時点の閾値がDataStoreへ焼き付いてしまう
 * (2026-08-31に動作確認モードのトグルで実際にやらかした)。単独の設定は必ず単独で保存する
 * ([SettingsStore.setTestMode]などの専用メソッド)。
 */
internal typealias SettingsUpdater = (updated: DayMvpSettings, persist: suspend SettingsStore.() -> Unit) -> Unit

/** 詳細パネルのタブ(docs/17 §3-3)。1画面に全部を並べると長いスクロールになるため3つに分ける。 */
internal enum class AdvancedTab(val label: String) {
    CAMERA("カメラ"),
    DECISION("判定"),
    DEVICE("端末・診断"),
}

/** 取付回転の選択肢(度)。 */
private val ROTATION_CHOICES = listOf(0, 90, 180, 270)

/**
 * 前検査画面の「詳細」(docs/17 §3-3)。下段パネルの高さいっぱいに置かれる前提
 * (スクロールしない固定行 + タブ + スクロールする中身)。
 *
 * ここにあるのは**毎回は触らない**設定と診断。主操作(運行開始・較正)は[PreflightPanel]。
 *
 * @param updateSettings 設定を1項目変えるとき。ローカル状態の反映と保存を呼び出し側が行う
 * @param message 較正の案内。主画面の案内文はこのパネルに隠れて読めないので、カメラタブの中にも出す
 * @param mountRotationLocked 運行中で前面カメラの取付回転が固定されているか(表示だけ)
 */
@Composable
internal fun ColumnScope.AdvancedPanel(
    settings: DayMvpSettings,
    updateSettings: SettingsUpdater,
    tab: AdvancedTab,
    onTabChange: (AdvancedTab) -> Unit,
    message: String?,
    resolvedMountRotationDegrees: Int,
    mountRotationLocked: Boolean,
    manualMountOverrideDegrees: Int?,
    sensorDetectedMountDegrees: Int?,
    onManualMountOverride: (Int) -> Unit,
    locationPermissionGranted: Boolean,
    onRequestLocationPermission: () -> Unit,
    onFrontZoomChange: (Float) -> Unit,
    onCalibrate: () -> Unit,
    onOpenThresholds: () -> Unit,
    onClose: () -> Unit,
) {
    // スクロールしない固定行: 見出し・閉じる・よく押す操作。
    // 以前は「アプリを再起動」が詳細の中(スクロール先)にあり、開いてから探す必要があった。
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "詳細",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.weight(1f),
        )
        SecondaryButton("閉じる", Modifier.width(96.dp), onClick = onClose)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SecondaryButton(
            label = "閾値設定",
            modifier = Modifier.weight(1f).height(48.dp),
            onClick = onOpenThresholds,
        )
    }
    // タブ。1画面に全部を並べると長いスクロールになるので、3つに分ける。
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AdvancedTab.entries.forEach { t ->
            ChoiceButton(label = t.label, selected = tab == t, modifier = Modifier.weight(1f)) { onTabChange(t) }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(top = 4.dp, bottom = 8.dp),
    ) {
        when (tab) {
            AdvancedTab.CAMERA -> CameraTab(
                zoomRatio = settings.frontCameraZoomRatio,
                onZoomChange = onFrontZoomChange,
                message = message,
                resolvedMountRotationDegrees = resolvedMountRotationDegrees,
                mountRotationLocked = mountRotationLocked,
                manualMountOverrideDegrees = manualMountOverrideDegrees,
                sensorDetectedMountDegrees = sensorDetectedMountDegrees,
                onManualMountOverride = onManualMountOverride,
                onCalibrate = onCalibrate,
            )
            AdvancedTab.DECISION -> DecisionTab(settings = settings, updateSettings = updateSettings)
            AdvancedTab.DEVICE -> DeviceTab(
                settings = settings,
                updateSettings = updateSettings,
                locationPermissionGranted = locationPermissionGranted,
                onRequestLocationPermission = onRequestLocationPermission,
            )
        }
    }
}

@Composable
private fun CameraTab(
    zoomRatio: Float,
    onZoomChange: (Float) -> Unit,
    message: String?,
    resolvedMountRotationDegrees: Int,
    mountRotationLocked: Boolean,
    manualMountOverrideDegrees: Int?,
    sensorDetectedMountDegrees: Int?,
    onManualMountOverride: (Int) -> Unit,
    onCalibrate: () -> Unit,
) {
    // 較正まわりの案内はこのタブでも読めるようにする。
    message?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = AccentAmber,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    CalibrationButton(
        label = "較正(10秒 + 閉眼3秒)",
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        onClick = onCalibrate,
    )

    // インカメラの回転。
    // **「自動」は廃止した**(理由はDayMvpScreenのresolvedMountRotationDegreesのKDoc)。
    ChoiceRow(
        label = "カメラの回転(${resolvedMountRotationDegrees}°を適用中" +
            (if (mountRotationLocked) "・運行中は固定" else "") + ")",
        options = ROTATION_CHOICES,
        selected = manualMountOverrideDegrees ?: 0,
        optionLabel = { "${it}°" },
        onSelect = onManualMountOverride,
    )
    // インカメラのズーム。実車で顔が小さく(20%未満で判定無効)なるときに上げる。
    ChoiceRow(
        label = "カメラのズーム(顔が小さいときに上げる。変えると較正し直し)",
        options = DayMvpSettings.FRONT_ZOOM_CHOICES,
        selected = zoomRatio,
        optionLabel = { "%.2f倍".format(it).replace(".00倍", ".0倍") },
    ) { ratio ->
        if (ratio != zoomRatio) onZoomChange(ratio)
    }
    // センサー値は参考。押さない限り判定には入らない。
    Text(
        text = sensorDetectedMountDegrees?.let {
            "参考: 姿勢センサーの判定は${it}°(車載では傾きで振れるため自動適用しません)"
        } ?: "参考: 姿勢センサーは検知不能(平置きなど)",
        style = MaterialTheme.typography.bodyMedium,
        color = SubtleText,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun DecisionTab(
    settings: DayMvpSettings,
    updateSettings: SettingsUpdater,
) {
    // 動作確認モード: 停車中(速度0・GPS未取得)でも脇見を判定する。
    // 眠気系(閉眼・こっくり・あくび)は元から速度ゲートが無いので影響を受けない。
    SwitchRow(
        title = "動作確認モード",
        description = "停車中でも脇見を判定します。実運用ではOFFにしてください。",
        checked = settings.testModeIgnoreSpeedGate,
    ) { checked ->
        updateSettings(settings.copy(testModeIgnoreSpeedGate = checked)) { setTestMode(checked) }
    }

    // 発報時の位置記録。**既定はOFF。**運転者への周知内容が変わるので、
    // 「注意事項」の文面もこのスイッチに連動して切り替わる。
    SwitchRow(
        title = "警告が出た地点を記録",
        description = "警告の瞬間の緯度経度を残します(軌跡は残しません)。" +
            "ONにすると「注意事項」の記載も変わります。時刻と速度は常に記録します。",
        checked = settings.recordLocationOnEvent,
    ) { checked ->
        updateSettings(settings.copy(recordLocationOnEvent = checked)) { setRecordLocationOnEvent(checked) }
    }
}

@Composable
private fun DeviceTab(
    settings: DayMvpSettings,
    updateSettings: SettingsUpdater,
    locationPermissionGranted: Boolean,
    onRequestLocationPermission: () -> Unit,
) {
    // 速度ゲート用の位置情報。拒否されても監視は続くので、開始をブロックしない。
    if (!locationPermissionGranted) {
        Text(
            text = "位置情報が未許可です。停車中(信号待ち・休憩・積卸し)にも" +
                "前方確認の警告が出ることがあります。位置情報は速度の判定にのみ使用し、" +
                "走行位置は記録しません。",
            style = MaterialTheme.typography.bodyMedium,
            color = SubtleText,
            modifier = Modifier.padding(top = 10.dp),
        )
        SecondaryButton("位置情報を許可", Modifier.padding(top = 6.dp), onClick = onRequestLocationPermission)
    } else {
        Text(
            text = "位置情報: 許可済み(速度の判定に使用)",
            style = MaterialTheme.typography.bodyMedium,
            color = SubtleText,
            modifier = Modifier.padding(top = 10.dp),
        )
    }

    // 運行開始時の位置情報の確認。「次回から表示しない」を選んだあとで戻せるように置く。
    SwitchRow(
        title = "運行開始時に位置情報の確認を出す",
        description = "位置情報が未許可のまま運行を始めるとき、許可を促す画面を出します。",
        checked = !settings.locationPromptSuppressed,
    ) { checked ->
        updateSettings(settings.copy(locationPromptSuppressed = !checked)) { setLocationPromptSuppressed(!checked) }
    }

    // 画面上部の数値(開発・不具合の調査用)。普段は簡単な表示だけにする(2026-10-17)。
    SwitchRow(
        title = "画面に数値も表示(調査用)",
        description = "顔の向き・目の開き・処理時間などの数値を画面上部に出します。普段は切っておいてください。",
        checked = settings.showDiagnosticDetails,
    ) { checked ->
        updateSettings(settings.copy(showDiagnosticDetails = checked)) { setShowDiagnosticDetails(checked) }
    }

    // PiP(小窓)。運用前提(アプリを前面に維持)を変えるスイッチなので既定OFF(docs/18 §14-15)。
    SwitchRow(
        title = "小窓(PiP)を許可",
        description = "ホームへ戻ると小窓になり、ナビ等を前面にしたまま監視を続けます。" +
            "発報すると自動で全画面に戻ります。",
        checked = settings.pictureInPictureEnabled,
    ) { checked ->
        updateSettings(settings.copy(pictureInPictureEnabled = checked)) {
            setPictureInPictureEnabled(checked)
        }
    }
}
