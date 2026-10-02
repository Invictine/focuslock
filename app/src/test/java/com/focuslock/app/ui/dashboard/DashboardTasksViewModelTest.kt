package com.focuslock.app.ui.dashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardTasksViewModelTest {

    @Test
    fun concurrentRoutineRefreshesShareTheInFlightLoad() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gate = CompletableDeferred<Unit>()
            var loads = 0
            val viewModel = DashboardTasksViewModel(
                tokenProvider = { "token-a" },
                taskLoader = { _, _ ->
                    loads++
                    gate.await()
                    loaded("current")
                },
            )

            val first = viewModel.refresh()
            runCurrent()
            val second = viewModel.refresh()
            assertSame(first, second)
            assertEquals(1, loads)

            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(TickTickTasksState.Loaded, viewModel.tasks.value.status)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun warmCacheSkipsRoutineLoadAndExplicitRefreshBypassesIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val bypassValues = mutableListOf<Boolean>()
            val viewModel = DashboardTasksViewModel(
                tokenProvider = { "token-a" },
                taskLoader = { _, bypass ->
                    bypassValues += bypass
                    loaded("task-${bypassValues.size}")
                },
            )

            viewModel.refresh()
            advanceUntilIdle()
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(listOf(false), bypassValues)

            viewModel.refresh(bypassCache = true)
            advanceUntilIdle()
            assertEquals(listOf(false, true), bypassValues)
            assertEquals("task-2", viewModel.tasks.value.nextTitle)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun tokenAndLocalDayChangesInvalidateCachedSnapshot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var token = "token-a"
            var day = LocalDate.of(2026, 9, 30)
            var loads = 0
            val viewModel = DashboardTasksViewModel(
                tokenProvider = { token },
                taskLoader = { requestedToken, _ ->
                    loads++
                    loaded("$requestedToken-$loads")
                },
                todayProvider = { day },
            )

            viewModel.refresh()
            advanceUntilIdle()
            token = "token-b"
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals("token-b-2", viewModel.tasks.value.nextTitle)

            day = day.plusDays(1)
            viewModel.refresh()
            advanceUntilIdle()
            assertEquals("token-b-3", viewModel.tasks.value.nextTitle)
            assertEquals(3, loads)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun accountChangedDuringLoadRetriesWithCurrentTokenBeforePublishing() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var token = "token-a"
            val firstLoad = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val seenTokens = mutableListOf<String>()
            val viewModel = DashboardTasksViewModel(
                tokenProvider = { token },
                taskLoader = { requestedToken, _ ->
                    seenTokens += requestedToken
                    if (requestedToken == "token-a") {
                        firstLoad.complete(Unit)
                        releaseFirst.await()
                    }
                    loaded(requestedToken)
                },
            )

            val job = viewModel.refresh()
            runCurrent()
            firstLoad.await()
            token = "token-b"
            releaseFirst.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("token-a", "token-b"), seenTokens)
            assertEquals("token-b", viewModel.tasks.value.nextTitle)
            assertTrue(job.isCompleted)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cancelledOlderLoadCannotOverwriteForcedRefresh() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val oldLoadStarted = CompletableDeferred<Unit>()
            val releaseOldLoad = CompletableDeferred<Unit>()
            var loads = 0
            val viewModel = DashboardTasksViewModel(
                tokenProvider = { "token-a" },
                taskLoader = { _, _ ->
                    loads++
                    if (loads == 1) {
                        oldLoadStarted.complete(Unit)
                        try {
                            releaseOldLoad.await()
                        } catch (_: CancellationException) {
                            // Simulate a loader that returns after cancellation; the
                            // request generation still has to prevent stale publication.
                        }
                        loaded("stale")
                    } else {
                        loaded("fresh")
                    }
                },
            )

            val oldJob = viewModel.refresh()
            runCurrent()
            oldLoadStarted.await()
            viewModel.refresh(bypassCache = true)
            advanceUntilIdle()
            releaseOldLoad.complete(Unit)
            advanceUntilIdle()

            assertEquals("fresh", viewModel.tasks.value.nextTitle)
            assertEquals(2, loads)
            assertTrue(oldJob.isCancelled)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failedRefreshDoesNotWarmCacheAndCanBeRetried() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var loads = 0
            val viewModel = DashboardTasksViewModel(
                tokenProvider = { "token-a" },
                taskLoader = { _, _ ->
                    loads++
                    if (loads == 1) error("temporary failure")
                    loaded("recovered")
                },
            )

            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(TickTickTasksState.Error, viewModel.tasks.value.status)

            viewModel.refresh()
            advanceUntilIdle()
            assertEquals(TickTickTasksState.Loaded, viewModel.tasks.value.status)
            assertEquals("recovered", viewModel.tasks.value.nextTitle)
            assertEquals(2, loads)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun loaded(title: String) = DashboardTasks(
        status = TickTickTasksState.Loaded,
        completed = 3,
        nextTitle = title,
    )
}
