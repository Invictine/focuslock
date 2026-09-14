package com.focuslock.app.data.backup

import com.focuslock.app.data.model.BlockedApp
import com.focuslock.app.data.model.BlockedWebsite
import com.focuslock.app.data.repository.AppLimit
import com.focuslock.app.data.repository.AppLimitsRepository
import com.focuslock.app.data.repository.BlockSchedule
import com.focuslock.app.data.repository.BlockSchedulesRepository
import com.focuslock.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Serializable snapshot of everything the user configures in Settings.
 * Unknown keys are ignored on import; numeric fields are clamped on write via
 * the repositories, so a hand-edited file can never put invalid values in DataStore.
 */
@Serializable
private data class ConfigBackupPayload(
    val version: Int = ConfigBackupManager.CURRENT_VERSION,
    val blockedApps: List<BlockedApp> = emptyList(),
    val blockedWebsites: List<BlockedWebsite> = emptyList(),
    val appLimits: List<AppLimit> = emptyList(),
    val blockSchedules: List<BlockSchedule> = emptyList(),
    val goals: Goals = Goals(),
    val workRatio: Int = 4,
    val taskBonusMinutes: Int = 5
) {
    @Serializable
    data class Goals(
        val focusGoalMinutes: Int = SettingsRepository.DEFAULT_FOCUS_GOAL_MINUTES,
        val dailyTasksGoal: Int = SettingsRepository.DEFAULT_DAILY_TASKS_GOAL
    )
}

/** Counts of the records written by a successful import. */
data class ConfigImportResult(
    val blockedApps: Int,
    val blockedWebsites: Int,
    val appLimits: Int,
    val blockSchedules: Int
) {
    val total: Int get() = blockedApps + blockedWebsites + appLimits + blockSchedules
}

/**
 * Exports/imports the FocusLock configuration as a single JSON string.
 *
 * Import is a full overwrite: rows that exist locally but not in the backup are
 * removed (limits + schedules), while app/website lists are replaced wholesale.
 * Throws [IllegalArgumentException] on malformed JSON or an unsupported version;
 * the UI catches it and reports the failure.
 */
class ConfigBackupManager(
    private val settingsRepository: SettingsRepository,
    private val appLimitsRepository: AppLimitsRepository,
    private val blockSchedulesRepository: BlockSchedulesRepository
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    suspend fun exportToJson(): String {
        val payload = ConfigBackupPayload(
            version = CURRENT_VERSION,
            blockedApps = settingsRepository.getBlockedApps(),
            blockedWebsites = settingsRepository.getBlockedWebsites(),
            appLimits = appLimitsRepository.limitsFlow.first().values.sortedBy { it.packageName },
            blockSchedules = blockSchedulesRepository.schedulesFlow.first(),
            goals = ConfigBackupPayload.Goals(
                focusGoalMinutes = settingsRepository.focusGoalMinutesFlow.first(),
                dailyTasksGoal = settingsRepository.dailyTasksGoalFlow.first()
            ),
            workRatio = settingsRepository.workRatioFlow.first(),
            taskBonusMinutes = settingsRepository.taskBonusFlow.first()
        )
        return json.encodeToString(ConfigBackupPayload.serializer(), payload)
    }

    suspend fun importFromJson(raw: String): ConfigImportResult {
        val payload = try {
            json.decodeFromString(ConfigBackupPayload.serializer(), raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid FocusLock config file.", e)
        }
        if (payload.version < 1 || payload.version > CURRENT_VERSION) {
            throw IllegalArgumentException(
                "Unsupported config version ${payload.version} (this build supports $CURRENT_VERSION)."
            )
        }

        // Boundaries: replace wholesale.
        val apps = payload.blockedApps.filter { it.packageName.isNotBlank() }
        settingsRepository.updateBlockedApps(apps)

        val websites = payload.blockedWebsites
            .filter { it.domain.isNotBlank() }
            .map { site ->
                val cleaned = SettingsRepository.cleanDomain(site.domain)
                if (cleaned.isBlank()) site else site.copy(domain = cleaned)
            }
        settingsRepository.updateBlockedWebsites(websites)

        // App limits: replace, dropping local limits that are absent from the backup.
        val importedLimits = payload.appLimits
            .filter { it.packageName.isNotBlank() }
            .associateBy { it.packageName }
        appLimitsRepository.limitsFlow.first().keys
            .filter { it !in importedLimits }
            .forEach { appLimitsRepository.removeLimit(it) }
        importedLimits.values.forEach { appLimitsRepository.setLimit(it.packageName, it.dailyMinutes, it.enabled) }

        // Block schedules: replace, dropping local schedules that are absent from the backup.
        val schedules = payload.blockSchedules
            .filter { it.daysOfWeek.isNotEmpty() }
            .map { schedule ->
                schedule.copy(
                    id = schedule.id.ifBlank { UUID.randomUUID().toString() },
                    label = schedule.label.ifBlank { "Schedule" },
                    daysOfWeek = schedule.daysOfWeek.filter { it in 1..7 }.toSet(),
                    startMinuteOfDay = schedule.startMinuteOfDay.coerceIn(0, 1439),
                    endMinuteOfDay = schedule.endMinuteOfDay.coerceIn(0, 1439)
                )
            }
            .filter { it.daysOfWeek.isNotEmpty() }
        val importedScheduleIds = schedules.map { it.id }.toSet()
        blockSchedulesRepository.schedulesFlow.first()
            .filter { it.id !in importedScheduleIds }
            .forEach { blockSchedulesRepository.delete(it.id) }
        schedules.forEach { blockSchedulesRepository.upsert(it) }

        // Goals / ratio / bonus: repository setters clamp to their valid ranges.
        settingsRepository.setFocusGoalMinutes(payload.goals.focusGoalMinutes)
        settingsRepository.setDailyTasksGoal(payload.goals.dailyTasksGoal)
        settingsRepository.setWorkRatio(payload.workRatio)
        settingsRepository.setTaskBonus(payload.taskBonusMinutes)

        return ConfigImportResult(
            blockedApps = apps.size,
            blockedWebsites = websites.size,
            appLimits = importedLimits.size,
            blockSchedules = schedules.size
        )
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}
