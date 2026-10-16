package com.focuslock.app

import com.focuslock.app.reminder.ReminderCompressionPolicy
import org.junit.Assert.*
import org.junit.Test

class ReminderCompressionPolicyTest {
    @Test fun boundsLandscapeAndPortraitWithoutUpscaling() {
        assertEquals(480, ReminderCompressionPolicy.outputHeight(1920, 1080))
        assertEquals(852, ReminderCompressionPolicy.outputHeight(1080, 1920))
        assertEquals(240, ReminderCompressionPolicy.outputHeight(320, 240))
        assertEquals(480, ReminderCompressionPolicy.outputHeight(0, 0))
    }
    @Test fun keepsAlreadyEfficientMediaButReencodesLargeFramesOrUnknownBitrate() {
        assertTrue(ReminderCompressionPolicy.alreadyCompact(640, 360, 300_000, true))
        assertFalse(ReminderCompressionPolicy.alreadyCompact(1920, 1080, 300_000, true))
        assertFalse(ReminderCompressionPolicy.alreadyCompact(640, 360, 0, true))
        assertTrue(ReminderCompressionPolicy.alreadyCompact(0, 0, 24_000, false))
        assertFalse(ReminderCompressionPolicy.alreadyCompact(0, 0, 256_000, false))
    }
}
