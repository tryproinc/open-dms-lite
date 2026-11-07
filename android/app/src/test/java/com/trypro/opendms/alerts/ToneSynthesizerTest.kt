package com.trypro.opendms.alerts

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class ToneSynthesizerTest {

    private val sampleRate = 8_000 // テストを軽くするため既定より低いレートを使う

    @Test
    fun `セグメントの合計時間分のサンプル数が生成される`() {
        val pattern = TonePattern(listOf(ToneSegment(880, 100), ToneSegment(null, 50), ToneSegment(1200, 100)))
        val pcm = ToneSynthesizer.synthesize(pattern, sampleRate)
        val expectedSamples = (sampleRate * 250L / 1000L).toInt()
        assertThat(pcm.size).isEqualTo(expectedSamples)
    }

    @Test
    fun `無音セグメントは全サンプルが0`() {
        val pattern = TonePattern(listOf(ToneSegment(null, 50)))
        val pcm = ToneSynthesizer.synthesize(pattern, sampleRate)
        assertThat(pcm.all { it == 0.toShort() }).isTrue()
    }

    @Test
    fun `音のあるセグメントは無音でない振幅を持つ`() {
        val pattern = TonePattern(listOf(ToneSegment(880, 100)))
        val pcm = ToneSynthesizer.synthesize(pattern, sampleRate)
        assertThat(pcm.any { abs(it.toInt()) > 1000 }).isTrue()
    }

    @Test
    fun `4種のアラートパターンは互いに異なる`() {
        val patterns = listOf(
            AlertTonePatterns.EYE_CLOSURE_CAUTION,
            AlertTonePatterns.EYE_CLOSURE_WARNING,
            AlertTonePatterns.DISTRACTION_WARNING,
            AlertTonePatterns.UNAVAILABLE,
        )
        val signatures = patterns.map { it.segments.map { seg -> seg.frequencyHz to seg.durationMs } }
        assertThat(signatures.toSet()).hasSize(4)
    }
}
