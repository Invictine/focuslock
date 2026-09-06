package com.focuslock.app

import com.focuslock.app.service.TickTickNotificationListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TickTickParserTest {

    @Test
    fun testParseStandardPomodoro() {
        val text = "Pomodoro finished! Take a 5-minute break."
        val duration = TickTickNotificationListener.parseDurationFromNotification(text)
        assertEquals(25, duration)
    }

    @Test
    fun testParseExplicitMinutes() {
        val text = "Focus session completed: 50 mins on Coding"
        val duration = TickTickNotificationListener.parseDurationFromNotification(text)
        assertEquals(50, duration)
    }

    @Test
    fun testParseExplicitHours() {
        val text = "Great job! You focused for 1.5 hours on Project Architecture"
        val duration = TickTickNotificationListener.parseDurationFromNotification(text)
        assertEquals(90, duration)
    }

    @Test
    fun testTaskCompletionKeyword() {
        val text = "Task completed: Review client pull request"
        val isCompleted = TickTickNotificationListener.isTaskCompletionNotification(text)
        assertTrue(isCompleted)
    }

    @Test
    fun testDownloadCompletedIsNotATask() {
        // Regression: generic system notifications must not earn credits
        assertFalse(
            TickTickNotificationListener.isTaskCompletionNotification("Download completed: report.pdf")
        )
    }

    @Test
    fun testUpdateDoneIsNotATask() {
        assertFalse(
            TickTickNotificationListener.isTaskCompletionNotification("System update done. Restart required.")
        )
    }

    @Test
    fun testCheckedOffCountsAsTask() {
        assertTrue(
            TickTickNotificationListener.isTaskCompletionNotification("You checked off Buy groceries")
        )
    }

    @Test
    fun testUnrelatedNotificationParsesZeroMinutes() {
        assertEquals(
            0,
            TickTickNotificationListener.parseDurationFromNotification("Download completed: 3 files saved")
        )
    }

    @Test
    fun testAbsurdDurationIsRejected() {
        // 9999 hours must not credit 599940 minutes
        assertEquals(
            0,
            TickTickNotificationListener.parseDurationFromNotification("You focused for 9999 hours straight!")
        )
    }
}
