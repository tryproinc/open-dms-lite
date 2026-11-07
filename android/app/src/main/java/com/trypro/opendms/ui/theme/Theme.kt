package com.trypro.opendms.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

// 運行中は車内で常時表示するため、暗色を既定にしてまぶしさを抑える。
private val OpenDmsDarkColors = darkColorScheme(
    primary = StatusNormal,
    // 既定のonPrimary(暗い紫)は緑の上でコントラスト2.4:1しかなく、「運行開始」の文字や
    // Switchのつまみが紫に見えていた(2026-09-20実機)。白なら5:1を超える。
    onPrimary = OnBackgroundLight,
    background = BackgroundDark,
    onBackground = OnBackgroundLight,
    surface = BackgroundDark,
    onSurface = OnBackgroundLight,
    error = StatusWarning,
)

private val OpenDmsLightColors = lightColorScheme(
    primary = StatusNormal,
    onPrimary = OnBackgroundLight,
    background = OnBackgroundLight,
    onBackground = BackgroundDark,
    error = StatusWarning,
)

@Composable
fun OpenDmsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) OpenDmsDarkColors else OpenDmsLightColors
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
