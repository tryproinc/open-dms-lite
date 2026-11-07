package com.trypro.opendms.domain

/**
 * 運行中(`MONITORING`)の画面の見せ方(docs/17 §2)。
 *
 * 夜間運行でスマートフォンの画面が車内の光源になるのを抑えるために選べるようにした。
 * どのモードでもカメラのプレビューViewそのものは消さず、上に黒を被せるだけにする
 * (CameraXの`Preview`はSurfaceを要求するため)。解析経路は変わらない。
 *
 * 発報の全画面表示と、故障・動作確認モードのバナーは全モード共通で出す。
 */
enum class MonitoringDisplayMode {
    /** 従来どおり。映像 + 顔の点 + 監視状態。 */
    CAMERA,

    /** 黒地に顔座標のオーバーレイ(landmark点)だけを描く。 */
    FACE_OVERLAY,

    /** 黒地に「DMS起動中」と監視状態・経過だけを出す。顔座標も描かない。 */
    INFO_ONLY,
    ;

    /** 運行中の切替ボタンで巡回する順。 */
    fun next(): MonitoringDisplayMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * 画面に実際に適用するモード。**運行中以外は常に[CAMERA]**
         * (前検査・較正・結果では取付位置の調整と較正に映像が要る)。
         */
        fun effective(selected: MonitoringDisplayMode, monitoring: Boolean): MonitoringDisplayMode =
            if (monitoring) selected else CAMERA
    }
}
