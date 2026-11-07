package com.trypro.opendms.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * D0-011追加検証: スマホの物理的な取付回転(上下逆・横向き)対応のL0テスト。
 * 一次情報(developer.android.com、2026-08-29確認)に基づく仕様は[MountRotation]のKDoc参照。
 */
class MountRotationTest {

    // ---- quantizeTo90 ----

    @Test
    fun `ORIENTATION_UNKNOWN相当の負数はnullを返す`() {
        assertThat(MountRotation.quantizeTo90(-1)).isNull()
    }

    @Test
    fun `0度付近は0へ量子化される`() {
        assertThat(MountRotation.quantizeTo90(0)).isEqualTo(0)
        assertThat(MountRotation.quantizeTo90(10)).isEqualTo(0)
        assertThat(MountRotation.quantizeTo90(44)).isEqualTo(0)
        assertThat(MountRotation.quantizeTo90(359)).isEqualTo(0)
        assertThat(MountRotation.quantizeTo90(350)).isEqualTo(0)
    }

    @Test
    fun `90度付近(左側が上)は90へ量子化される`() {
        assertThat(MountRotation.quantizeTo90(45)).isEqualTo(90)
        assertThat(MountRotation.quantizeTo90(90)).isEqualTo(90)
        assertThat(MountRotation.quantizeTo90(134)).isEqualTo(90)
    }

    @Test
    fun `180度付近(上下逆)は180へ量子化される`() {
        assertThat(MountRotation.quantizeTo90(135)).isEqualTo(180)
        assertThat(MountRotation.quantizeTo90(180)).isEqualTo(180)
        assertThat(MountRotation.quantizeTo90(224)).isEqualTo(180)
    }

    @Test
    fun `270度付近(右側が上)は270へ量子化される`() {
        assertThat(MountRotation.quantizeTo90(225)).isEqualTo(270)
        assertThat(MountRotation.quantizeTo90(270)).isEqualTo(270)
        assertThat(MountRotation.quantizeTo90(314)).isEqualTo(270)
    }

    @Test
    fun `315度以上は0度側へ折り返して量子化される`() {
        assertThat(MountRotation.quantizeTo90(315)).isEqualTo(0)
        assertThat(MountRotation.quantizeTo90(358)).isEqualTo(0)
    }

    // ---- swapsDimensions ----

    @Test
    fun `0度と180度は幅と高さを入れ替えない`() {
        assertThat(MountRotation.swapsDimensions(0)).isFalse()
        assertThat(MountRotation.swapsDimensions(180)).isFalse()
    }

    @Test
    fun `90度と270度は幅と高さを入れ替える`() {
        assertThat(MountRotation.swapsDimensions(90)).isTrue()
        assertThat(MountRotation.swapsDimensions(270)).isTrue()
    }

    @Test
    fun `swapsDimensionsも360超の値を正規化してから判定する`() {
        assertThat(MountRotation.swapsDimensions(450)).isTrue() // 450 % 360 = 90
    }

    // ---- undoRotation ----

    @Test
    fun `取付回転0度は恒等変換`() {
        val (x, y) = MountRotation.undoRotation(0.3f, 0.7f, 0)
        assertThat(x).isWithin(1e-6f).of(0.3f)
        assertThat(y).isWithin(1e-6f).of(0.7f)
    }

    @Test
    fun `90度回転後の左上隅は生座標では右上隅に対応する`() {
        // composeで導出した通り、生の(0,0)(左上)は90度回転後は(1,0)(右上)になる。
        // そのためundoRotationは補正後の(1,0)を生の(0,0)へ戻すはず。
        val (x, y) = MountRotation.undoRotation(1f, 0f, 90)
        assertThat(x).isWithin(1e-6f).of(0f)
        assertThat(y).isWithin(1e-6f).of(0f)
    }

    @Test
    fun `180度回転は中心点対称の逆変換になる`() {
        val (x, y) = MountRotation.undoRotation(0.2f, 0.9f, 180)
        assertThat(x).isWithin(1e-6f).of(0.8f)
        assertThat(y).isWithin(1e-6f).of(0.1f)
    }

    @Test
    fun `270度回転後の左上隅は生座標では左下隅に対応する`() {
        // composeで導出した通り、生の(0,0)(左上)は270度回転(90度反時計回り)後は(0,1)(左下)になる。
        val (x, y) = MountRotation.undoRotation(0f, 1f, 270)
        assertThat(x).isWithin(1e-6f).of(0f)
        assertThat(y).isWithin(1e-6f).of(0f)
    }

    @Test
    fun `90度と270度は互いに逆変換になっている(往復で元に戻る)`() {
        val forward = MountRotation.undoRotation(0.25f, 0.6f, 90)
        val back = MountRotation.undoRotation(forward.first, forward.second, 270)
        assertThat(back.first).isWithin(1e-6f).of(0.25f)
        assertThat(back.second).isWithin(1e-6f).of(0.6f)
    }

}
