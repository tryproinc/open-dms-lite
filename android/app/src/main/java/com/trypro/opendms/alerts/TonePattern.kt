package com.trypro.opendms.alerts

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 短い正弦波の連続(トーン+無音の並び)で1つの警告音を表す。
 * `frequencyHz == null`のセグメントは無音(セグメント間の間隔)。
 *
 * 11_DAY_SMARTPHONE_MVP_PLAN.md 3.6は「短い同梱音源を第一候補にする」としているが、
 * ライセンス的に問題のない音源素材が手元にないため、D0-008では実行時に正弦波を合成する
 * 方式で代替する。`AlertSink`のインターフェースは変えずに済むため、将来デザイン素材が
 * 用意できればこの合成部分だけ差し替えられる。
 */
data class ToneSegment(val frequencyHz: Int?, val durationMs: Int)

data class TonePattern(val segments: List<ToneSegment>) {
    val totalDurationMs: Int get() = segments.sumOf { it.durationMs }
}

object AlertTonePatterns {
    // ALT-002/ALT-006: 眠気(2段階)・脇見・監視不能を音の高さ・回数・長さで区別する。
    val EYE_CLOSURE_CAUTION = TonePattern(listOf(ToneSegment(880, 150)))
    val EYE_CLOSURE_WARNING = TonePattern(
        listOf(ToneSegment(1200, 150), ToneSegment(null, 100), ToneSegment(1200, 150)),
    )
    val DISTRACTION_WARNING = TonePattern(
        listOf(ToneSegment(700, 120), ToneSegment(null, 60), ToneSegment(1000, 120)),
    )
    /** こっくり: 眠気系(高め)だが閉眼2段階とは区別できる3連の短音。 */
    val DROWSY_NODDING = TonePattern(
        listOf(
            ToneSegment(950, 110), ToneSegment(null, 70),
            ToneSegment(950, 110), ToneSegment(null, 70),
            ToneSegment(950, 110),
        ),
    )
    /**
     * あくび: 眠気系だが閉眼注意より**弱い**単発の短音(ASV眠気2.4.2の「煩わしくない手段」)。
     * 閉眼注意(880Hz/150ms)より低く短くして、聴感上も軽い通知になるようにしている。
     */
    val DROWSY_YAWNING = TonePattern(listOf(ToneSegment(760, 90)))
    val UNAVAILABLE = TonePattern(listOf(ToneSegment(500, 300)))
    /** 高温: 監視不能とも眠気系とも区別できる低め1音(短いシステム音相当)。 */
    val THERMAL = TonePattern(listOf(ToneSegment(620, 200)))
    /** 較正の合図: 警告のどれとも違う、上がる2音。 */
    val CALIBRATION_CUE = TonePattern(
        listOf(ToneSegment(660, 90), ToneSegment(null, 40), ToneSegment(990, 120)),
    )
}

/** [TonePattern]から16bit PCM(モノラル)サンプル列を生成する。Android非依存の純粋関数。 */
object ToneSynthesizer {
    const val SAMPLE_RATE_HZ = 44_100
    private const val FADE_MS = 5

    fun synthesize(pattern: TonePattern, sampleRateHz: Int = SAMPLE_RATE_HZ): ShortArray {
        val totalSamples = pattern.segments.sumOf { samplesFor(it.durationMs, sampleRateHz) }
        val output = ShortArray(totalSamples)
        var offset = 0
        for (segment in pattern.segments) {
            val segmentSamples = samplesFor(segment.durationMs, sampleRateHz)
            val freq = segment.frequencyHz
            if (freq != null) {
                writeTone(output, offset, segmentSamples, freq, sampleRateHz)
            }
            // freq==nullの区間はShortArrayの既定値0(無音)のままでよい。
            offset += segmentSamples
        }
        return output
    }

    private fun samplesFor(durationMs: Int, sampleRateHz: Int): Int =
        (sampleRateHz.toLong() * durationMs / 1000L).toInt()

    private fun writeTone(output: ShortArray, offset: Int, count: Int, frequencyHz: Int, sampleRateHz: Int) {
        val fadeSamples = min(count / 4, sampleRateHz * FADE_MS / 1000)
        for (i in 0 until count) {
            val t = i.toDouble() / sampleRateHz
            var amplitude = 1.0
            if (fadeSamples > 0) {
                if (i < fadeSamples) amplitude = i.toDouble() / fadeSamples
                if (i >= count - fadeSamples) amplitude = min(amplitude, (count - i).toDouble() / fadeSamples)
            }
            val sample = sin(2.0 * PI * frequencyHz * t) * amplitude * Short.MAX_VALUE * VOLUME_SCALE
            output[offset + i] = sample.toInt().toShort()
        }
    }

    private const val VOLUME_SCALE = 0.6
}
