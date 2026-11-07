package com.trypro.opendms.vision


/**
 * 判定に使えない(顔の点が緑でない)ときに、映像の上に出す原因と直し方(docs/19 §5、2026-10-17)。
 * 複数の原因があるときは、直す順番が先のもの1つだけを返す。
 */
object QualityAdvice {
    /**
     * @param zoomAvailable 前面カメラが主で、ズームをまだ上げられるか。顔が小さいときにズームを案内する。
     */
    fun of(quality: SignalQuality, faceSizeRatio: Float?, fps: Float, zoomAvailable: Boolean): String? {
        if (quality.isValidForDecision) return null
        val flags = quality.flags
        return when {
            QualityFlag.NO_FACE in flags -> "顔が見つかりません → 顔がカメラに映るようにしてください"
            QualityFlag.FACE_TOO_SMALL in flags -> {
                val size = faceSizeRatio?.let { "(${(it * 100).toInt()}%)" } ?: ""
                "顔が小さい$size → カメラを近づける" + if (zoomAvailable) "か、ズームを上げてください" else "か、顔が大きく写る角度にしてください"
            }
            QualityFlag.FACE_TOO_LARGE in flags -> "顔が近すぎます → カメラから少し離れてください"
            QualityFlag.LOW_LIGHT in flags -> "暗いです → 顔を明るくしてください"
            QualityFlag.LOW_FPS in flags ->
                "処理が遅い(${fps.toInt()}fps) → 使っていない外付けカメラを外し、端末を冷やしてください"
            else -> "判定できません → 顔をカメラに向けてください"
        }
    }
}
