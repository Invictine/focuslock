package com.focuslock.app.service

import com.focuslock.app.data.model.TickTickWorkRecord
import com.focuslock.app.data.model.WorkRecordSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Strict mapper for the official TickTick `/open/v1/focus` response. */
internal object TickTickFocusParser {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Invalid JSON or malformed required fields fail the whole response. Sessions that
     * are unfinished, outside today's window, or otherwise ineligible are omitted.
     */
    fun parse(body: String, expectedType: Int, startOfDayMillis: Long, nowMillis: Long): List<TickTickWorkRecord> {
        require(expectedType == 0 || expectedType == 1) { "Unsupported TickTick focus type: $expectedType" }
        val sessions = try {
            json.parseToJsonElement(body) as? JsonArray
                ?: throw IllegalArgumentException("TickTick focus response must be a JSON array")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Unable to parse TickTick focus response", e)
        }

        return sessions.mapNotNull { element ->
            val obj = element as? JsonObject
                ?: throw IllegalArgumentException("TickTick focus entry must be an object")
            // Active sessions may not have measured duration or timestamps yet.
            // An absent endTime is sufficient to identify them as unfinished.
            val rawEnd = obj.string("endTime")
            if (rawEnd.isNullOrBlank()) return@mapNotNull null
            val id = obj.string("id")?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("TickTick focus entry is missing a valid id")
            val type = obj.integer("type")
                ?: throw IllegalArgumentException("TickTick focus entry is missing a valid type")
            require(type == expectedType) { "TickTick focus entry type $type did not match request type $expectedType" }
            val rawDuration = obj.long("duration")
                ?: throw IllegalArgumentException("TickTick focus entry is missing a valid duration")
            val start = obj.string("startTime")?.let(::parseMillis)
                ?: throw IllegalArgumentException("TickTick focus entry is missing a valid startTime")
            val end = parseMillis(rawEnd)
                ?: throw IllegalArgumentException("TickTick focus entry has an invalid endTime")

            if (rawDuration <= 0 || end <= start || end !in startOfDayMillis..nowMillis) return@mapNotNull null
            val elapsedSeconds = (end - start) / 1_000L
            val durationSeconds = normalizeDurationSeconds(rawDuration, elapsedSeconds) ?: return@mapNotNull null
            val durationMinutes = durationSeconds / 60L
            if (durationMinutes !in 1L..480L) return@mapNotNull null

            TickTickWorkRecord(
                id = "ticktick_focus_${type}_$id",
                title = "TickTick focus session",
                durationMinutes = durationMinutes.toInt(),
                timestamp = end,
                source = WorkRecordSource.TICKTICK_FOCUS_API
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.integer(key: String): Int? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.content.toIntOrNull()
    }

    private fun JsonObject.long(key: String): Long? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        return primitive.content.toLongOrNull()
    }

    /** Treat duration as seconds unless it only fits the elapsed wall time as milliseconds. */
    private fun normalizeDurationSeconds(rawDuration: Long, elapsedSeconds: Long): Long? {
        if (rawDuration <= 0 || elapsedSeconds <= 0) return null
        val elapsedWithRoundingAllowance = elapsedSeconds + 1
        return when {
            rawDuration <= elapsedWithRoundingAllowance -> rawDuration
            rawDuration / 1_000L in 1L..elapsedWithRoundingAllowance -> rawDuration / 1_000L
            else -> null
        }
    }

    private fun parseMillis(value: String): Long? {
        val normalized = value.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        return try {
            OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
