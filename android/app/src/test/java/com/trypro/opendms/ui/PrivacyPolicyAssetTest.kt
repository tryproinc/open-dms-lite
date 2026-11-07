package com.trypro.opendms.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * アプリに入れるプライバシーポリシー(公開前レビュー R-05)。
 * `tools/make_privacy_html.py` が docs/store/PRIVACY_POLICY.md から作る。原稿だけ直して作り直し忘れると、ここで落ちる。
 */
class PrivacyPolicyAssetTest {

    // ユニットテストの作業ディレクトリは android/app。
    private val asset = File("src/main/assets/privacy_policy.txt")
    private val source = File("../../docs/store/PRIVACY_POLICY.md")

    @Test
    fun `全文がアプリに入っていて原稿と同じ節がある`() {
        val text = asset.readText()
        val headings = source.readLines().filter { it.startsWith("## ") }.map { it.removePrefix("## ") }
        assertThat(headings).isNotEmpty()
        headings.forEach { assertThat(text).contains("■ $it") }
        assertThat(text).contains("ネットワーク通信をしません")
        assertThat(text).contains("info@trypro.co.jp")
    }

    @Test
    fun `Markdownの記号と公開前の作業メモを残さない`() {
        val text = asset.readText()
        listOf("**", "](", "|---", "公開前に確認").forEach { assertThat(text).doesNotContain(it) }
    }
}
