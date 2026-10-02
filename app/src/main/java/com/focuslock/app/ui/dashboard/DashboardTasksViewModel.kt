package com.focuslock.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.service.TickTickApiClient
import com.focuslock.app.service.TickTickAuthConfig
import com.focuslock.app.service.TickTickTaskItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class TickTickTasksState { Loading, NoAccount, Loaded, Error }

internal data class DashboardTasks(
    val status: TickTickTasksState = TickTickTasksState.Loading,
    val completed: Int = 0,
    val nextTitle: String? = null,
    val nextDetail: String? = null,
)

/** Activity-owned display data survives tab changes; explicit refresh always reloads. */
internal class DashboardTasksViewModel(
    private val tokenProvider: suspend () -> String? = {
        TickTickAuthConfig.getValidAccessToken(FocusLockApplication.instance.settingsRepository)
    },
    private val taskLoader: suspend (token: String, bypassCache: Boolean) -> DashboardTasks =
        ::fetchDashboardTasks,
    private val todayProvider: () -> LocalDate = LocalDate::now,
    private val monotonicNanos: () -> Long = System::nanoTime,
) : ViewModel() {
    private val snapshot = MutableStateFlow(DashboardTasks())
    val tasks = snapshot.asStateFlow()
    private var fetchJob: Job? = null
    private var requestGeneration = 0L
    private var cachedToken: String? = null
    private var cachedDay: LocalDate? = null
    private var fetchedAtNanos = 0L

    @Synchronized
    fun refresh(bypassCache: Boolean = false): Job {
        fetchJob?.takeIf { it.isActive && !bypassCache }?.let { return it }
        requestGeneration++
        val generation = requestGeneration
        fetchJob?.cancel()
        return viewModelScope.launch {
            var requestedToken: String? = null
            var requestedDay: LocalDate? = null
            try {
                val firstToken = tokenProvider()
                var today = todayProvider()
                if (firstToken.isNullOrBlank()) {
                    if (generation != requestGeneration) return@launch
                    clearCachedAccount()
                    snapshot.value = DashboardTasks(status = TickTickTasksState.NoAccount)
                    return@launch
                }
                var token: String = firstToken
                var forceFetch = bypassCache

                while (generation == requestGeneration) {
                    requestedToken = token
                    requestedDay = today
                    if (!forceFetch && snapshot.value.status == TickTickTasksState.Loaded &&
                        token == cachedToken && today == cachedDay &&
                        monotonicNanos() - fetchedAtNanos < CACHE_TTL_NANOS
                    ) return@launch

                    // Retain the display during routine refreshes, but clear it as soon
                    // as the account or local day changes.
                    if (token != cachedToken || today != cachedDay) snapshot.value = DashboardTasks()

                    val result = taskLoader(token, forceFetch).copy(status = TickTickTasksState.Loaded)
                    if (generation != requestGeneration) return@launch

                    // A sign-out/account swap or midnight rollover can happen during
                    // the network fan-out. Never publish that request under new state.
                    val latestToken = tokenProvider()
                    val latestDay = todayProvider()
                    if (generation != requestGeneration) return@launch
                    if (latestToken != token || latestDay != today) {
                        today = latestDay
                        if (latestToken.isNullOrBlank()) {
                            clearCachedAccount()
                            snapshot.value = DashboardTasks(status = TickTickTasksState.NoAccount)
                            return@launch
                        }
                        token = latestToken
                        forceFetch = false
                        snapshot.value = DashboardTasks()
                        continue
                    }

                    cachedToken = token
                    cachedDay = today
                    fetchedAtNanos = monotonicNanos()
                    snapshot.value = result
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == requestGeneration) {
                    // If credentials changed during a failed request, do not leave the
                    // previous account's task names/count visible in an error state.
                    val latestToken = try {
                        tokenProvider()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        requestedToken
                    }
                    if (latestToken != requestedToken || todayProvider() != requestedDay) {
                        if (latestToken.isNullOrBlank()) {
                            clearCachedAccount()
                            snapshot.value = DashboardTasks(status = TickTickTasksState.NoAccount)
                        } else {
                            clearCachedAccount()
                            snapshot.value = DashboardTasks()
                        }
                    } else {
                        snapshot.value = snapshot.value.copy(status = TickTickTasksState.Error)
                    }
                }
            }
        }.also { fetchJob = it }
    }

    private fun clearCachedAccount() {
        cachedToken = null
        cachedDay = null
        fetchedAtNanos = 0L
    }

    private companion object {
        const val CACHE_TTL_NANOS = 3L * 60L * 1_000_000_000L
    }
}

private suspend fun fetchDashboardTasks(token: String, bypassCache: Boolean): DashboardTasks {
    val client = TickTickApiClient()
    return coroutineScope {
        val completed = async {
            client.fetchCompletedTaskTitlesToday(token, bypassCache = bypassCache).size
        }
        val open = async { client.fetchOpenTasksStrict(token) }
        val next = open.await().minWithOrNull(
            compareBy<TickTickTaskItem> { it.dueDate.isNullOrBlank() && it.startDate.isNullOrBlank() }
                .thenBy { it.dueDate?.takeIf(String::isNotBlank) ?: it.startDate.orEmpty() }
        )
        DashboardTasks(
            status = TickTickTasksState.Loaded,
            completed = completed.await(),
            nextTitle = next?.title,
            nextDetail = next?.dueDate?.takeIf(String::isNotBlank)?.let { "Due ${formatTaskDate(it)}" }
                ?: next?.startDate?.takeIf(String::isNotBlank)?.let { "Starts ${formatTaskDate(it)}" }
                ?: next?.let { "Next up in TickTick" },
        )
    }
}

private fun formatTaskDate(raw: String): String {
    val instant = runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        ?: return raw.substringBefore('T').ifBlank { raw }
    return DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
        .withZone(ZoneId.systemDefault()).format(instant)
}
