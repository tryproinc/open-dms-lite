package com.trypro.opendms.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trypro.opendms.domain.CalibrationProfile
import com.trypro.opendms.domain.DayMvpSettings
import com.trypro.opendms.domain.MonitoringDisplayMode
import com.trypro.opendms.vision.FeatureFrame

/**
 * 運行開始に必要なメディア音量の下限(最大音量に対する比)。
 * 11_DAY_SMARTPHONE_MVP_PLAN.md 3.6「起動前にメディア音量が小さい場合は開始を止め、試聴ボタンを表示する」。
 * 車内の走行騒音下で警告音が聞こえる必要があるため、3割を暫定下限とする(実地試験で調整可)。
 */
internal const val MIN_MEDIA_VOLUME_RATIO = 0.3f

/**
 * 前検査(PREFLIGHT)の主画面(docs/17 §3-2)。下段パネルの中身。
 *
 * ここにあるのは**運行のたびに見る・押す**もの: 状態2行、運行中の表示、運行開始、較正、副操作。
 * 毎回は触らない設定は[AdvancedPanel]。運行開始の処理(状態機械の作り直し・記録の開始)は
 * 呼び出し側([DayMvpScreen])の[onStartSession]で行い、ここは押されたことを伝えるだけにする。
 *
 * @param profile 較正値。nullなら未較正(運行開始ではなく較正開始を主操作にする)
 * @param message 較正結果などの案内文
 */
@Composable
internal fun ColumnScope.PreflightPanel(
    settings: DayMvpSettings,
    profile: CalibrationProfile?,
    frame: FeatureFrame?,
    volumeRatio: Float,
    volumeSufficient: Boolean,
    speedKph: Float?,
    locationPermissionGranted: Boolean,
    resolvedMountRotationDegrees: Int,
    message: String?,
    onPreviewAlertTone: () -> Unit,
    onSelectDisplayMode: (MonitoringDisplayMode) -> Unit,
    onStartCalibration: (CalibrationPhase) -> Unit,
    onStartSession: () -> Unit,
    /** カメラのズームを変える(較正を消す)。顔が小さいときのボタンから呼ぶ。 */
    onFrontZoomChange: (Float) -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenNotice: () -> Unit,
    onOpenAbout: () -> Unit,
    onExit: () -> Unit,
) {
    // 案内文が増えても主操作が画面外へ出ないよう、パネルの上限内でスクロールさせる。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // -- 状態は2行にまとめる。個別のTextを積み上げると画面が縦に伸びるため。 --
        val speedLabel = when {
            !locationPermissionGranted -> "位置情報 未許可"
            speedKph == null -> "速度 測位中"
            else -> "速度 ${speedKph.toInt()} km/h"
        }
        val calibrationLabel = when {
            profile == null -> "未較正"
            profile.hasEyeClosedCalibration -> {
                val closed = profile.eyeClosedNormalizedRatio
                val gap = closed?.let { 1f - it }
                // 差が小さいときは、閉眼判定が当てにならないことを前検査で見せる。
                // 2026-09-14: サングラス越しで差0.29。閉じても閾値を出入りして鳴らなかった。
                if (gap != null && gap < settings.eyeClosedCalibrationMarginalGap) {
                    "較正済み(閉眼閾値 %.2f・開閉の差%.2fと小さく不安定)".format(profile.eyeClosedOpenRatioThreshold, gap)
                } else {
                    "較正済み(閉眼閾値 %.2f)".format(profile.eyeClosedOpenRatioThreshold)
                }
            }
            else -> "較正済み(閉眼較正なし・既定 %.2f)".format(settings.eyeClosedOpenRatioThreshold)
        }
        Text(
            text = listOf(calibrationLabel, "音量 ${(volumeRatio * 100).toInt()}%").joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFCFD8DC),
        )
        Text(
            text = listOf(
                speedLabel,
                "取付 ${resolvedMountRotationDegrees}°",
            ).joinToString("  ·  "),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFCFD8DC),
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )

        // 開始を妨げる事情だけ文章で出す。それ以外は上の2行に畳む。
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = AccentAmber,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (profile != null && !volumeSufficient) {
            // 3.6: メディア音量が小さいと警告音が運転者へ届かないため、開始を止めて試聴を促す。
            Text(
                text = "メディア音量が小さいため開始できません" +
                    "(現在 ${(volumeRatio * 100).toInt()}%、" +
                    "${(MIN_MEDIA_VOLUME_RATIO * 100).toInt()}%以上必要)。",
                style = MaterialTheme.typography.bodyMedium,
                color = AccentAmber,
            )
            TextButton(onClick = onPreviewAlertTone) {
                Text("警告音を試聴", color = AccentAmber)
            }
        }

        // 取付位置の目安。顔が小さいと瞼の開閉が読めず、閉眼が成立しなくなる
        // (2026-09-02の実測。DayMvpSettings.recommendedFaceSizeRatioのKDoc参照)。
        // `FACE_TOO_SMALL`(0.20)は「顔が見えているか」の下限なので、それだけでは
        // 「顔サイズ:OK」のまま瞼が読めない状態に気づけない。開始は止めない。
        val faceSizeNow = frame?.takeIf { it.faceDetected }?.faceSizeRatio
        if (faceSizeNow != null && faceSizeNow < settings.recommendedFaceSizeRatio) {
            FaceSizeCard(
                faceSizePercent = (faceSizeNow * 100).toInt(),
                recommendedPercent = (settings.recommendedFaceSizeRatio * 100).toInt(),
                minPercent = (settings.minFaceSizeRatio * 100).toInt(),
                zoomChoices = DayMvpSettings.FRONT_ZOOM_CHOICES.filter { it > settings.frontCameraZoomRatio },
                onZoom = onFrontZoomChange,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // 運行中の表示(docs/17)。運行のたびに変えうる設定なので、詳細に埋めず主画面に置く。
        ChoiceRow(
            label = "運行中の表示(顔座標・情報のみは黒地で暗くなります)",
            options = MonitoringDisplayMode.entries,
            selected = settings.monitoringDisplayMode,
            optionLabel = { it.label() },
            onSelect = onSelectDisplayMode,
        )

        // -- 主操作。いま押すべきボタンを1つだけ大きく出す。 --
        if (profile == null) {
            Button(
                onClick = { onStartCalibration(CalibrationPhase.OPEN) },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(56.dp),
            ) {
                Text("較正開始(10秒 正面 + 3秒 閉眼)")
            }
        } else {
            // SES-001/003: 較正済みかつ音量が十分であれば運行を開始できる。
            Button(
                enabled = volumeSufficient,
                onClick = onStartSession,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(56.dp),
            ) {
                Text("運行開始", style = MaterialTheme.typography.titleMedium)
            }
            // 出発前の準備で鳴らさないため、一度20km/hを超えるまで警告を出さない。
            Text(
                text = "運行開始後、時速${settings.armingSpeedKph.toInt()}kmを超えるまで警告は出ません" +
                    "(位置情報が無いときは開始からすぐ出ます)。",
                style = MaterialTheme.typography.bodyMedium,
                color = SubtleText,
                modifier = Modifier.padding(top = 4.dp),
            )
            // 較正の2操作は横に並べ、主操作(運行開始)と区別できる琥珀色の枠にする。
            // 灰色の小ボタンにしていたら「再較正が無くなった」と受け取られた(2026-08-31)し、
            // 2026-09-19にも「見づらく押しづらい」と指摘された。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CalibrationButton(
                    label = "再較正(10秒+閉眼3秒)",
                    modifier = Modifier.weight(1f),
                ) { onStartCalibration(CalibrationPhase.OPEN) }
                // 閉眼較正だけをやり直せるようにする。開眼較正(10秒)まで戻す必要はない。
                // 失敗したとき(目を閉じ忘れた・サングラスを外し忘れた)の再試行が主な用途。
                CalibrationButton(
                    label = if (profile.hasEyeClosedCalibration) "閉眼較正をやり直す(3秒)" else "閉眼較正(3秒)",
                    modifier = Modifier.weight(1f),
                ) { onStartCalibration(CalibrationPhase.EYES_CLOSED) }
            }
            Text(
                text = "取付を変えたら再較正してください。",
                style = MaterialTheme.typography.bodySmall,
                color = SubtleText,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        // -- 副操作は1行に収める。縦に積むと主操作が埋もれるため。 --
        // 「閾値設定」は詳細の中へ移した(毎回は触らないため)。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryButton("詳細", Modifier.weight(1f), onClick = onOpenAdvanced)
            // ASV眠気5章/脇見5章: 目的・作動条件・音の意味・機能限界・使用上の注意の周知。
            SecondaryButton("注意事項", Modifier.weight(1f), onClick = onOpenNotice)
            // アプリ終了。運行中(MONITORING)には出さない -- 運行を閉じずに終了すると
            // LocalSessionが開いたまま残り、その復旧(SES-007)は未実装のため。
            // 運行中は既存の長押し「運行終了」で先に運行を閉じてもらう。
            SecondaryButton("終了", Modifier.weight(1f), onClick = onExit)
        }
        // 提供元・問い合わせ先・バージョン(2026-10-17)。
        SecondaryButton(
            "このアプリについて",
            Modifier.fillMaxWidth().padding(top = 8.dp),
            onClick = onOpenAbout,
        )
        // 位置付け。保安基準等への適合を謳わないことを、毎回の起動画面でも示す。
        Text(
            text = "保安基準・UN-R182/R183への適合を保証するものではありません(注意事項 6)。",
            style = MaterialTheme.typography.bodySmall,
            color = SubtleText,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
