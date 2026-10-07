package com.focuslock.app.ui.dashboard

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardRefreshTest {

    @Test
    fun refreshesTasksAndFocusTogetherThenStartsAccountAfterBothFinish() = runTest {
        val tasksStarted = CompletableDeferred<Unit>()
        val focusStarted = CompletableDeferred<Unit>()
        val tasksGate = CompletableDeferred<Boolean>()
        val focusGate = CompletableDeferred<Boolean>()
        var accountStarted = false

        val result = async {
            refreshDashboardSources(
                refreshTasks = { tasksStarted.complete(Unit); tasksGate.await() },
                refreshFocus = { focusStarted.complete(Unit); focusGate.await() },
                refreshAccount = { accountStarted = true; true },
            )
        }

        runCurrent()
        assertTrue(tasksStarted.isCompleted)
        assertTrue(focusStarted.isCompleted)
        assertFalse(accountStarted)

        tasksGate.complete(true)
        runCurrent()
        assertFalse(accountStarted)

        focusGate.complete(true)
        advanceUntilIdle()
        assertTrue(accountStarted)
        assertEquals(emptyList<String>(), result.await())
    }

    @Test
    fun oneSourceFailureDoesNotSkipOtherRefreshesOrAccountUpload() = runTest {
        var focusRefreshed = false
        var accountRefreshed = false

        val failures = refreshDashboardSources(
            refreshTasks = { throw IllegalStateException("tasks unavailable") },
            refreshFocus = { focusRefreshed = true; true },
            refreshAccount = { accountRefreshed = true; true },
        )

        assertTrue(focusRefreshed)
        assertTrue(accountRefreshed)
        assertEquals(listOf("TickTick tasks"), failures)
    }

    @Test
    fun cancellationPropagatesAndPreventsAccountUpload() = runTest {
        val taskStarted = CompletableDeferred<Unit>()
        var accountRefreshed = false
        val refresh = async {
            refreshDashboardSources(
                refreshTasks = { taskStarted.complete(Unit); awaitCancellation() },
                refreshFocus = { true },
                refreshAccount = { accountRefreshed = true; true },
            )
        }

        runCurrent()
        assertTrue(taskStarted.isCompleted)
        refresh.cancel()
        runCurrent()

        assertTrue(refresh.isCancelled)
        assertFalse(accountRefreshed)
    }
}
