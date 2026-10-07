package com.focuslock.app.ui.dashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import com.focuslock.app.service.TickTickTaskItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardTasksViewModelTest {

    @Test
    fun refreshAndAwaitFollowsForcedReplacementUntilItFinishes() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val firstLoad = CompletableDeferred<Unit>()
            val replacementLoad = CompletableDeferred<Unit>()
            val firstStarted = CompletableDeferred<Unit>()
            val replacementStarted = CompletableDeferred<Unit>()
            var loads = 0
            val viewModel = DashboardTasksViewModel(
                tokenProvider = { "token-a" },
                openTaskCacheWriter = {},
                taskLoader = { _, _ ->
                    loads++
                    if (loads == 1) {
                        firstStarted.complete(Unit)
                        firstLoad.await()
                        loaded("superseded")
                    } else {
                        replacementStarted.complete(Unit)
                        replacementLoad.await()
                        loaded("replacement")
                    }
                },
            )
            val result = CompletableDeferred<DashboardTasks>()
            val waiter = launch { result.complete(viewModel.refreshAndAwait(bypassCache = true)) }

            runCurrent()
            firstStarted.await()
            viewModel.refresh(bypassCache = true)
            runCurrent()
            replacementStarted.await()

            assertFalse(waiter.isCompleted)
            replacementLoad.complete(Unit)
            advanceUntilIdle()

            assertEquals("replacement", result.await().nextTitle)
            assertTrue(waiter.isCompleted)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cancellingRefreshAndAwaitCallerPropagatesCancellation() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val loadStarted = CompletableDeferred<Unit>()
            val releaseLoad = CompletableDeferred<Unit>()
            val viewModel = DashboardTasksViewModel(
                tokenProvider = { "token-a" },
                openTaskCacheWriter = {},
                taskLoader = { _, _ ->
                    loadStarted.complete(Unit)
                    releaseLoad.await()
                    loaded("done")
                },
            )
            val waiter = launch { viewModel.refreshAndAwait(bypassCache = true) }

            runCurrent()
            loadStarted.await()
            waiter.cancel()
            runCurrent()

            assertTrue(waiter.isCancelled)
            releaseLoad.complete(Unit)
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun concurrentRoutineRefreshesShareTheInFlightLoad() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gate = CompletableDeferred<Unit>()
            var loads = 0
            val viewModel = DashboardTasksViewModel(
                tokenProvider = { "token-a" },
                openTaskCacheWriter = {},
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
                openTaskCacheWriter = {},
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
                openTaskCacheWriter = {},
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
            val writtenTaskIds = mutableListOf<String>()
            val viewModel = DashboardTasksViewModel(
                tokenProvider = { token },
                taskLoader = { requestedToken, _ ->
                    seenTokens += requestedToken
                    if (requestedToken == "token-a") {
                        firstLoad.complete(Unit)
                        releaseFirst.await()
                    }
                    loaded(requestedToken, listOf(task(requestedToken)))
                },
                openTaskCacheWriter = { tasks -> writtenTaskIds += tasks.map { it.id } },
            )

            val job = viewModel.refresh()
            runCurrent()
            firstLoad.await()
            token = "token-b"
            releaseFirst.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("token-a", "token-b"), seenTokens)
            assertEquals("token-b", viewModel.tasks.value.nextTitle)
            assertEquals(listOf("token-b"), writtenTaskIds)
            assertEquals("token-b", viewModel.tasks.value.openTasks.single().id)
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
                openTaskCacheWriter = {},
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
                openTaskCacheWriter = {},
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

    @Test
    fun forcedRefreshWritesFreshOpenTasksToFrogCache() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val written = mutableListOf<List<TickTickTaskItem>>()
            var loads = 0
            val viewModel = DashboardTasksViewModel(
                tokenProvider = { "token-a" },
                taskLoader = { _, _ ->
                    loads++
                    loaded("task-$loads", listOf(task("fresh-$loads")))
                },
                openTaskCacheWriter = { written += it },
            )

            viewModel.refresh()
            advanceUntilIdle()
            viewModel.refresh(bypassCache = true)
            advanceUntilIdle()

            assertEquals(listOf("fresh-1", "fresh-2"), written.map { it.single().id })
            assertEquals("fresh-2", viewModel.tasks.value.openTasks.single().id)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleRequestAndDisconnectedAccountCannotWriteOrPublishTasks() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var token: String? = "token-a"
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val written = mutableListOf<String>()
            var loads = 0
            val viewModel = DashboardTasksViewModel(
                tokenProvider = { token },
                taskLoader = { _, _ ->
                    loads++
                    if (loads == 1) {
                        firstStarted.complete(Unit)
                        try {
                            releaseFirst.await()
                        } catch (_: CancellationException) {
                            // Deliberately return after cancellation to exercise generation checks.
                        }
                        loaded("stale", listOf(task("stale")))
                    } else {
                        loaded("fresh", listOf(task("fresh")))
                    }
                },
                openTaskCacheWriter = { tasks -> written += tasks.map { it.id } },
            )

            val oldJob = viewModel.refresh()
            runCurrent()
            firstStarted.await()
            viewModel.refresh(bypassCache = true)
            advanceUntilIdle()
            releaseFirst.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("fresh"), written)
            assertEquals("fresh", viewModel.tasks.value.openTasks.single().id)
            assertTrue(oldJob.isCancelled)

            val disconnectedStarted = CompletableDeferred<Unit>()
            val releaseDisconnected = CompletableDeferred<Unit>()
            val disconnected = DashboardTasksViewModel(
                tokenProvider = { token },
                taskLoader = { _, _ ->
                    disconnectedStarted.complete(Unit)
                    releaseDisconnected.await()
                    loaded("signed-out stale", listOf(task("signed-out-stale")))
                },
                openTaskCacheWriter = { tasks -> written += tasks.map { it.id } },
            )
            token = "token-b"
            val disconnectedJob = disconnected.refresh()
            runCurrent()
            disconnectedStarted.await()
            token = null
            releaseDisconnected.complete(Unit)
            advanceUntilIdle()

            assertEquals(TickTickTasksState.NoAccount, disconnected.tasks.value.status)
            assertEquals(listOf("fresh"), written)
            assertTrue(disconnectedJob.isCompleted)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun signOutDuringCacheWriteClearsOptionsWrittenByTheInFlightRequest() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var token: String? = "token-a"
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            val cachedIds = mutableListOf<String>()
            val viewModel = DashboardTasksViewModel(
                tokenProvider = { token },
                taskLoader = { _, _ -> loaded("task", listOf(task("stale-after-signout"))) },
                openTaskCacheWriter = { tasks ->
                    if (tasks.isEmpty()) {
                        cachedIds.clear()
                    } else {
                        writeStarted.complete(Unit)
                        releaseWrite.await()
                        cachedIds.clear()
                        cachedIds += tasks.map { it.id }
                    }
                },
            )

            val job = viewModel.refresh()
            runCurrent()
            writeStarted.await()
            token = null
            releaseWrite.complete(Unit)
            advanceUntilIdle()

            assertTrue(cachedIds.isEmpty())
            assertEquals(TickTickTasksState.NoAccount, viewModel.tasks.value.status)
            assertTrue(job.isCompleted)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun cacheWriterFailureRetainsPreviouslyWrittenCacheAndReportsError() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val cachedIds = mutableListOf<String>()
            var loads = 0
            val viewModel = DashboardTasksViewModel(
                tokenProvider = { "token-a" },
                taskLoader = { _, _ ->
                    loads++
                    loaded("task-$loads", listOf(task("id-$loads")))
                },
                openTaskCacheWriter = { tasks ->
                    if (tasks.single().id == "id-2") error("cache write failed")
                    cachedIds.clear()
                    cachedIds += tasks.map { it.id }
                },
            )

            viewModel.refresh()
            advanceUntilIdle()
            viewModel.refresh(bypassCache = true)
            advanceUntilIdle()

            assertEquals(listOf("id-1"), cachedIds)
            assertEquals(TickTickTasksState.Error, viewModel.tasks.value.status)
            assertEquals("task-1", viewModel.tasks.value.nextTitle)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun loaded(title: String, openTasks: List<TickTickTaskItem> = emptyList()) = DashboardTasks(
        status = TickTickTasksState.Loaded,
        completed = 3,
        nextTitle = title,
        openTasks = openTasks,
    )

    private fun task(id: String) = TickTickTaskItem(id = id, projectId = "project", title = id)
}
