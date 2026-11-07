package com.trypro.opendms.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Open DMS Lite の注意事項(免責を含む)の文面を固定する。 */
class DriverNoticeTest {

    private val sections = DriverNotice.sections()

    @Test
    fun `最後の項目で位置付けと免責を示す`() {
        val last = sections.last()
        assertThat(last.title).contains("免責")
        assertThat(last.body).contains("保安基準")
        assertThat(last.body).contains("UN-R182")
        assertThat(last.body).contains("適合を保証するものではありません")
        assertThat(last.body).contains("無償")
        assertThat(last.body).contains("MIT")
    }

    @Test
    fun `適合や認定を主張する文言を含まない`() {
        val all = sections.joinToString("\n") { it.title + it.body }
        listOf("適合しています", "認定済み", "認証済み", "準拠しています").forEach {
            assertThat(all).doesNotContain(it)
        }
    }

    @Test
    fun `外付けカメラへの言及を含まない`() {
        val all = sections.joinToString("\n") { it.title + it.body }
        assertThat(all).doesNotContain("外付けカメラ")
        assertThat(all).doesNotContain("カメラが接続されていません")
    }
}
