package com.trypro.opendms.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * D0-001: CIでunit testが通ることを確認するための最小テスト。
 * 実質的な判定ロジックのテスト(DAY-UT-001等)はD0-007のDayDmsStateMachineTestで追加する。
 */
class DayMvpUiStateTest {

    @Test
    fun `4つの画面状態が定義されている`() {
        assertThat(DayMvpUiState.entries).containsExactly(
            DayMvpUiState.PREFLIGHT,
            DayMvpUiState.CALIBRATING,
            DayMvpUiState.MONITORING,
            DayMvpUiState.RESULT,
        )
    }
}
