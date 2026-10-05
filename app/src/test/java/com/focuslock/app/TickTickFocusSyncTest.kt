package com.focuslock.app

import com.focuslock.app.data.model.WorkRecordSource
import com.focuslock.app.service.TickTickApiClient
import com.focuslock.app.service.TickTickFocusParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.OffsetDateTime

class TickTickFocusSyncTest {
    private val todayStart = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val now = System.currentTimeMillis()

    @Test
    fun parsesOnlyValidCompletedTodaySessionsAndFloorsExplicitSeconds() {
        val endMillis = now - 2_000
        val startMillis = endMillis - 25 * 60_000
        val body = """[
          {"id":"p1","type":0,"duration":1500,"startTime":"${iso(startMillis, "+0530")}","endTime":"${iso(endMillis, "+0530")}","pauseDuration":900},
          {"id":"running","type":0},
          {"id":"old","type":0,"duration":1500,"startTime":"${iso(todayStart - 3_000_000, "Z")}","endTime":"${iso(todayStart - 1_000, "Z")}"},
          {"id":"future","type":0,"duration":1500,"startTime":"${iso(now, "Z")}","endTime":"${iso(now + 10_000, "Z")}"},
          {"id":"too-long","type":0,"duration":28860,"startTime":"${iso(startMillis, "Z")}","endTime":"${iso(endMillis, "Z")}"}
        ]"""

        val parsed = TickTickFocusParser.parse(body, 0, todayStart, now)

        assertEquals(listOf("ticktick_focus_0_p1"), parsed.map { it.id })
        assertEquals(25, parsed.single().durationMinutes)
        assertEquals(endMillis, parsed.single().timestamp)
        assertEquals(WorkRecordSource.TICKTICK_FOCUS_API, parsed.single().source)
    }

    @Test
    fun overnightSessionCountsOnItsCompletionDay() {
        val midnight = java.time.OffsetDateTime.parse("2026-10-01T00:00:00+05:30").toInstant().toEpochMilli()
        val body = """[{"id":"overnight","type":1,"duration":1500,"startTime":"2026-09-30T23:45:00+0530","endTime":"2026-10-01T00:10:00+0530"}]"""
        val records = TickTickFocusParser.parse(body, 1, midnight, midnight + 60 * 60_000)
        assertEquals(25, records.single().durationMinutes)
    }

    @Test
    fun normalizesSecondsAndMillisecondsOnlyWhenTheyFitElapsedTime() {
        val end = now - 2_000
        fun entry(id: String, duration: Long, elapsedSeconds: Long): String {
            val start = end - elapsedSeconds * 1_000L
            return """{"id":"$id","type":0,"duration":$duration,"startTime":"${iso(start, "Z")}","endTime":"${iso(end, "Z")}"}"""
        }
        val body = """[${entry("actual-ms", 1_988_000, 1_988)},${entry("short-ms", 60_000, 60)},${entry("subminute-ms", 30_000, 30)},${entry("documented-seconds", 1_500, 1_500)},${entry("contradicted", 100_000, 50)},${entry("long-safe", 28_800_000, 28_800)}]"""

        val records = TickTickFocusParser.parse(body, 0, todayStart, now).associateBy { it.id }

        assertEquals(33, records.getValue("ticktick_focus_0_actual-ms").durationMinutes)
        assertEquals(1, records.getValue("ticktick_focus_0_short-ms").durationMinutes)
        assertEquals(25, records.getValue("ticktick_focus_0_documented-seconds").durationMinutes)
        assertEquals(480, records.getValue("ticktick_focus_0_long-safe").durationMinutes)
        assertTrue("ticktick_focus_0_subminute-ms" !in records)
        assertTrue("ticktick_focus_0_contradicted" !in records)
    }

    @Test
    fun malformedBodiesAndTypeMismatchesFailInsteadOfLookingLikeNoSessions() {
        assertThrows(IllegalArgumentException::class.java) {
            TickTickFocusParser.parse("not json", 0, todayStart, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TickTickFocusParser.parse("""[{"id":"x","type":1,"duration":1500,"startTime":"${iso(now - 100_000, "Z")}","endTime":"${iso(now, "Z")}"}]""", 0, todayStart, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TickTickFocusParser.parse("""[{"id":"x","type":0,"startTime":"${iso(now - 100_000, "Z")}","endTime":"${iso(now, "Z")}"}]""", 0, todayStart, now)
        }
    }

    @Test
    fun fetchRequestsBothTypesWithEncodedOffsetAndDeduplicatesReturnedIds() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val end = System.currentTimeMillis() - 1_000
        val start = end - 1_500_000
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            val type = request.url.queryParameter("type")!!.toInt()
            val one = """{"id":"same","type":$type,"duration":1500,"startTime":"${iso(start, "+05:30")}","endTime":"${iso(end, "+05:30")}"}"""
            val body = if (type == 0) "[$one,$one]" else "[$one]"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()

        val records = TickTickApiClient(client).fetchFocusSessionsToday("secret")

        assertEquals(listOf("0", "1"), requests.map { it.url.queryParameter("type") })
        assertTrue(requests.all { it.url.encodedQuery!!.contains("%2B") })
        assertTrue(requests.all { it.header("Authorization") == "Bearer secret" })
        assertEquals(listOf("ticktick_focus_0_same", "ticktick_focus_1_same"), records.map { it.id })
    }

    @Test
    fun fetchFocusSessionsIncludesDelayedPriorDaySessionsAndUsesInclusiveCompletionCutoff() = runBlocking {
        val since = OffsetDateTime.parse("2026-10-03T00:00:00+05:30").toInstant().toEpochMilli()
        val fixedNow = OffsetDateTime.parse("2026-10-04T12:00:00+05:30").toInstant().toEpochMilli()
        val includedEnd = since + 12 * 60 * 60_000L
        val todayEnd = since + 36 * 60 * 60_000L
        val excludedEnd = since - 1L
        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            val type = request.url.queryParameter("type")!!.toInt()
            fun entry(id: String, end: Long) =
                """{"id":"$id","type":$type,"duration":60,"startTime":"${iso(end - 60_000, "+0530")}","endTime":"${iso(end, "+0530")}"}"""
            val body = "[${entry("prior-day", includedEnd)},${entry("today", todayEnd)},${entry("before-cutoff", excludedEnd)}]"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build()

        val records = TickTickApiClient(client).fetchFocusSessions("secret", since, fixedNow)

        assertEquals(listOf("ticktick_focus_0_prior-day", "ticktick_focus_0_today", "ticktick_focus_1_prior-day", "ticktick_focus_1_today"), records.map { it.id })
        assertEquals(2, requests.size)
        assertTrue(requests.all { parseBoundary(it.url.queryParameter("from")!!) == since - 24L * 60L * 60L * 1_000L })
        assertTrue(requests.all { parseBoundary(it.url.queryParameter("to")!!) == fixedNow })
    }

    @Test
    fun fetchFocusSessionsTodayKeepsTodayOnlyCompletionWindow() = runBlocking {
        val localTodayStart = java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val previousEnd = localTodayStart - 1_000L
        val todayEnd = System.currentTimeMillis() - 1_000L
        val requests = mutableListOf<okhttp3.Request>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            requests += request
            val type = request.url.queryParameter("type")!!.toInt()
            val yesterday = """{"id":"yesterday","type":$type,"duration":60,"startTime":"${iso(previousEnd - 60_000, "Z")}","endTime":"${iso(previousEnd, "Z")}"}"""
            val today = """{"id":"today","type":$type,"duration":60,"startTime":"${iso(todayEnd - 60_000, "Z")}","endTime":"${iso(todayEnd, "Z")}"}"""
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("[$yesterday,$today]".toResponseBody("application/json".toMediaType())).build()
        }).build()

        val records = TickTickApiClient(client).fetchFocusSessionsToday("secret")

        assertEquals(listOf("ticktick_focus_0_today", "ticktick_focus_1_today"), records.map { it.id })
        assertTrue(requests.all { parseBoundary(it.url.queryParameter("from")!!) == localTodayStart - 24L * 60L * 60L * 1_000L })
    }

    @Test
    fun fetchFocusSessionsRejectsInvalidAndOverMonthWindows() {
        val client = TickTickApiClient(OkHttpClient())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.fetchFocusSessions("secret", -1L, now) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.fetchFocusSessions("secret", now + 1L, now) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.fetchFocusSessions("secret", now - 31L * 24L * 60L * 60L * 1_000L, now) }
        }
    }

    @Test
    fun httpFailureIsPropagated() {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Unavailable")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }).build()

        assertThrows(java.io.IOException::class.java) {
            runBlocking { TickTickApiClient(client).fetchFocusSessionsToday("secret") }
        }
    }

    private fun iso(millis: Long, offset: String): String {
        val instant = Instant.ofEpochMilli(millis)
        val zone = when (offset) {
            "Z" -> ZoneId.of("UTC")
            "+0530", "+05:30" -> ZoneId.of("Asia/Kolkata")
            else -> ZoneId.systemDefault()
        }
        val adjusted = instant.atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"))
        return adjusted + offset
    }

    private fun parseBoundary(value: String): Long {
        val normalized = value.replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
        return OffsetDateTime.parse(normalized).toInstant().toEpochMilli()
    }
}
