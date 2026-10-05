package com.focuslock.app

import com.focuslock.app.data.repository.CreditBankRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TickTickCreditRetentionTest {
    @Test
    fun retainsAllTickTickFocusIdsAndOnlyTheNewestFiveHundredOtherIds() {
        val ids = linkedSetOf<String>().apply {
            (0 until 501).forEach { add("ticktick_focus_0_session_$it") }
            (0 until 501).forEach { add("manual_session_$it") }
        }

        val retained = CreditBankRepository.retainCreditedIds(ids)

        assertEquals(1_001, retained.size)
        assertTrue("ticktick_focus_0_session_0" in retained)
        assertTrue("ticktick_focus_0_session_500" in retained)
        assertFalse("manual_session_0" in retained)
        assertTrue("manual_session_1" in retained)
        assertTrue("manual_session_500" in retained)
    }
}
