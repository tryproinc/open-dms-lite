package com.trypro.opendms.ui.theme

import androidx.compose.ui.graphics.Color

// DAY MVP-0の状態表示色。11_DAY_SMARTPHONE_MVP_PLAN.md 3.1: 大きい文字と色・アイコンで状態を表す。
val StatusNormal = Color(0xFF2E7D32)
val StatusCaution = Color(0xFFF9A825)
val StatusWarning = Color(0xFFC62828)
val StatusUnavailable = Color(0xFF616161)

val BackgroundDark = Color(0xFF0B1F3A)
val OnBackgroundLight = Color(0xFFF5F7FA)

// 運行中の黒ベース表示(docs/17)。OLEDで消灯する純黒を地にし、文字は低彩度・中輝度に抑える。
// 発報の全画面表示はこの配色を使わない(視認性を落とさない)。
val NightBlack = Color(0xFF000000)
/** 黒地の主文字(見出し・状態)。純白より一段落とす。 */
val NightText = Color(0xFFB0BEC5)
/** 黒地の副文字(経過・補足)。 */
val NightTextDim = Color(0xFF78909C)
/** 黒地での「監視中」。通常色より暗い緑。 */
val NightStatusNormal = Color(0xFF4E8A52)
/** 黒地での「品質低下」。通常色より暗い黄。 */
val NightStatusCaution = Color(0xFFB0891F)
/** 黒地での「監視できません」。 */
val NightStatusUnavailable = Color(0xFF6E6E6E)
