package com.trypro.opendms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.trypro.opendms.domain.DayMvpSettings
import com.trypro.opendms.domain.TunableItem
import com.trypro.opendms.domain.TunableSettings
import kotlin.math.roundToInt

/**
 * 閾値設定(10項目のスライダー)。項目の定義は[TunableSettings]。
 *
 * 各項目に、何を変えるのかの説明・いまの値・既定値・左右の端の意味(「敏感」など)を出す。
 * 値を動かすたびに項目どうしの整合([TunableSettings.normalize])を取るので、
 * 「強い警告が注意より短い」といった矛盾は作れない。
 */
@Composable
internal fun SettingsPanel(
    current: DayMvpSettings,
    onSave: (DayMvpSettings) -> Unit,
    onResetDefault: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf(TunableSettings.normalize(current)) }
    val defaults = DayMvpSettings.DAY_MVP0_DEFAULT

    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.95f))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("閾値設定", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text(
                "既定値のままでも使えます。敏感にするほど検知しやすくなる一方で、誤報も増えます。",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            TunableSettings.items.groupBy { it.group }.forEach { (group, groupItems) ->
                Text(
                    text = group,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF80D8FF),
                    modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
                )
                groupItems.forEach { item ->
                    TunableSlider(
                        item = item,
                        value = item.get(draft),
                        defaultValue = item.get(defaults),
                        onValueChange = { v -> draft = TunableSettings.normalize(item.set(draft, v)) },
                    )
                }
            }

            Row(
                modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { onSave(draft) }) { Text("保存") }
                Button(onClick = onResetDefault) { Text("既定値に戻す") }
                Button(onClick = onDismiss) { Text("閉じる") }
            }
        }
    }
}

@Composable
private fun TunableSlider(
    item: TunableItem,
    value: Float,
    defaultValue: Float,
    onValueChange: (Float) -> Unit,
) {
    val shown = value.coerceIn(item.range)
    val steps = (((item.range.endInclusive - item.range.start) / item.step).roundToInt() - 1).coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = format(item, shown),
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF80D8FF),
            )
        }
        Text(
            text = item.description,
            style = MaterialTheme.typography.bodySmall,
            color = SubtleText,
        )
        Slider(
            value = shown,
            onValueChange = { onValueChange(TunableSettings.snap(item, it)) },
            valueRange = item.range,
            steps = steps,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(item.lowLabel, style = MaterialTheme.typography.bodySmall, color = SubtleText, modifier = Modifier.weight(1f))
            Text(
                text = "既定 ${format(item, defaultValue)}",
                style = MaterialTheme.typography.bodySmall,
                color = SubtleText,
                modifier = Modifier.weight(1f),
            )
            Text(item.highLabel, style = MaterialTheme.typography.bodySmall, color = SubtleText)
        }
    }
}

private fun format(item: TunableItem, value: Float): String = "%.${item.decimals}f".format(value) + item.unit
