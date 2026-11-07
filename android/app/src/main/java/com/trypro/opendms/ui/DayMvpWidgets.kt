package com.trypro.opendms.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trypro.opendms.domain.MonitoringDisplayMode

/*
 * DAY MVP-0の画面で共用する小さな部品(docs/17 §3)。
 *
 * 車内で押せる下限として、ボタンは44dp以上・文字は14sp以上に揃える。
 * 色の意味づけ:
 *   - 主操作(運行開始・較正開始)は塗り(primary)。呼び出し側がMaterialの`Button`をそのまま使う
 *   - [CalibrationButton]: 琥珀色の枠線。較正の操作だけに使う
 *   - [SecondaryButton]: 灰の塗り。副操作(詳細・注意事項・終了・閉じる など)
 *   - [ChoiceButton]: 選択肢。選択中は塗り、未選択は薄い枠線
 */

/** 詳細パネルの説明文などに使う、控えめな灰色。 */
internal val SubtleText = Color(0xFFB0BEC5)

/** 較正まわりの案内・注意に使う琥珀色。 */
internal val AccentAmber = Color(0xFFFFCC80)

/** 判定に使えないときの帯の地色。白文字とのコントラスト比は約7:1。 */
internal val AdviceBannerRed = Color(0xFFB71C1C)

/**
 * 副操作用の控えめなボタン。主操作(較正開始/運行開始)と見分けがつくよう塗りを弱くする。
 * 高さは車内で押せる下限として44dp以上にする(docs/17 §3)。
 */
@Composable
internal fun SecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF37474F),
            contentColor = Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        modifier = modifier.heightIn(min = 44.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * 較正の操作ボタン(docs/17 §3-2)。主操作(運行開始=塗り)とも副操作(灰)とも違う、琥珀色の枠線にする。
 * 灰色の小ボタンにしていたら「再較正が無くなった」と受け取られた(2026-08-31)。
 */
@Composable
internal fun CalibrationButton(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber),
        border = BorderStroke(1.5.dp, AccentAmber),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * 横並びの選択肢1つ。選択中は塗り、未選択は薄い枠線。
 *
 * 取付回転・fps・解像度・タブ・カメラ選択・表示モードなど、「n択から1つ」はすべてこれで揃える。
 * 未選択に枠が無いと選択肢がただの文字に見え、どこを押せるのか分からなかった(2026-09-20実機)。
 */
@Composable
internal fun ChoiceButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // 5個を1行に並べるため、既定の余白では収まらない。文字も折り返させない
    // (2026-08-31に実機で「270°」が縦積みになるのを確認)。
    val contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
    val sized = modifier.heightIn(min = 44.dp)
    if (selected) {
        Button(onClick = onClick, contentPadding = contentPadding, modifier = sized) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
            contentPadding = contentPadding,
            modifier = sized,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/**
 * 「短い説明 + 横並びの選択肢」の1組。
 *
 * 詳細パネルの項目はほぼこの形なので、余白と字色をここで揃える
 * (以前は項目ごとにpaddingが4/6/8/12dpとばらついていて、それが雑然として見える原因だった)。
 */
@Composable
internal fun ChoiceRow(
    label: String,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = SubtleText,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/**
 * [ChoiceRow]の定型: 選択肢の一覧から1つを選ぶ。
 *
 * `forEach { ChoiceButton(...) }` の繰り返しを1行にまとめる。選択肢の並び順は[options]の順。
 * [selected]は保存値が選択肢に無いとき(手で入れた値など)を許すため nullable。その場合はどれも選択中にならない。
 */
@Composable
internal fun <T : Any> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    ChoiceRow(label) {
        options.forEach { option ->
            ChoiceButton(
                label = optionLabel(option),
                selected = option == selected,
                modifier = Modifier.weight(1f),
            ) { onSelect(option) }
        }
    }
}

/**
 * 「題 + 説明 + Switch」の1行。詳細パネルのスイッチ類はすべてこの形にして余白と字の大きさを揃える。
 */
@Composable
internal fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = SubtleText,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 表示モードの短い日本語。選択行と運行中の切替ボタンで共用する。 */
internal fun MonitoringDisplayMode.label(): String = when (this) {
    MonitoringDisplayMode.CAMERA -> "映像"
    MonitoringDisplayMode.FACE_OVERLAY -> "顔座標"
    MonitoringDisplayMode.INFO_ONLY -> "情報のみ"
}

/**
 * 判定に使えない(顔の点が緑でない)ときに、映像の下端に出す原因と直し方(docs/19 §5、2026-10-17)。
 * 点の色だけでは理由が分からず、顔が小さいのか処理が遅いのかを運転者が見分けられなかった。
 */
@Composable
internal fun QualityAdviceBanner(text: String, modifier: Modifier = Modifier, dim: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = if (dim) 0.7f else 1f),
        modifier = modifier
            .fillMaxWidth()
            .background(AdviceBannerRed.copy(alpha = if (dim) 0.6f else 0.92f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * 顔が推奨より小さいときの案内(2026-10-17)。文だけでは見落とされたので、枠で囲み、
 * 前面カメラが主ならその場でズームを上げるボタンを出す。
 *
 * @param zoomChoices 押せるズーム(いまより大きいもの)。空ならボタンを出さない。
 */
@Composable
internal fun FaceSizeCard(
    faceSizePercent: Int,
    recommendedPercent: Int,
    minPercent: Int,
    zoomChoices: List<Float>,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(2.dp, AccentAmber), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Text(
            text = "顔が小さめです(${faceSizePercent}%、推奨${recommendedPercent}%以上)",
            style = MaterialTheme.typography.titleMedium,
            color = AccentAmber,
        )
        Text(
            text = "このままだと瞼の開閉が読めず、目を閉じたことを検知しにくくなります。" +
                "${minPercent}%未満になると判定が止まり、顔の点が赤くなります。" +
                if (zoomChoices.isNotEmpty()) "カメラを近づけるか、ズームを上げてください(上げたら較正し直します)。"
                else "カメラを近づけるか、顔が正面に大きく写る角度にしてください。",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (zoomChoices.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                zoomChoices.forEach { ratio ->
                    CalibrationButton(
                        label = "ズーム ${"%.2f".format(ratio).trimEnd('0').trimEnd('.')}倍にする",
                        modifier = Modifier.weight(1f),
                    ) { onZoom(ratio) }
                }
            }
        }
    }
}

