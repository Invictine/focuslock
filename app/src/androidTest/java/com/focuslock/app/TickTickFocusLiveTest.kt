package com.focuslock.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.data.model.WorkRecordSource
import com.focuslock.app.service.TickTickApiClient
import com.focuslock.app.service.TickTickAuthConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in integration check using the account already connected in the installed app. */
@RunWith(AndroidJUnit4::class)
class TickTickFocusLiveTest {
    @Test
    fun importsRealSessionsAndDoesNotCreditThemTwice() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTickTick") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FocusLockApplication
        assertTrue("Connect TickTick in FocusLock before the live check", app.settingsRepository.tickTickTokenFlow.first().isNotBlank())
        val diagnostics = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.isSuccessful) {
                val items = Json.parseToJsonElement(response.peekBody(1_048_576).string()) as? JsonArray
                android.util.Log.i("TickTickFocusLiveTest", "Focus type ${chain.request().url.queryParameter("type")}: ${items?.size} raw API records, ${items?.count { (it as? JsonObject)?.containsKey("endTime") == true }} with endTime")
                items?.forEach { element ->
                    val obj = element as? JsonObject ?: return@forEach
                    fun epoch(key: String): Long? = try {
                        val raw = obj[key].toString().trim('"').replace(Regex("([+-]\\d{2})(\\d{2})$"), "$1:$2")
                        java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
                    } catch (_: Exception) { null }
                    val end = epoch("endTime")
                    val start = epoch("startTime")
                    android.util.Log.i("TickTickFocusLiveTest", "Eligibility: duration=${obj["duration"]}, elapsedSeconds=${if (end != null && start != null) (end-start)/1000 else null}, pauseDuration=${obj["pauseDuration"]}, endsToday=${end != null && end >= com.focuslock.app.data.repository.CreditBankRepository.startOfTodayMillis()}, futureEnd=${end != null && end > System.currentTimeMillis()}, chronological=${end != null && start != null && end > start}")
                }
            }
            response
        }.build()
        val todayRecords = TickTickApiClient(diagnostics).fetchFocusSessionsToday(TickTickAuthConfig.getValidAccessToken(app.settingsRepository)!!)
        val first = app.tickTickFocusSync.sync()
        assertNotNull("TickTick connection is unavailable or expired", first)
        first!!
        android.util.Log.i("TickTickFocusLiveTest", "Found ${first.sessionsFound} sessions, ${first.focusMinutes} focus minutes; imported ${first.newSessions}, earned ${first.earnedMinutes} minutes")
        assertTrue("TickTick returned no completed focus sessions today; live ingestion needs a real session", first.sessionsFound > 0)
        val history = app.creditBankRepository.fullHistoryFlow.first()
        assertTrue(history.any {
            it.source == WorkRecordSource.TICKTICK_FOCUS_API && it.durationMinutes > 0
        })
        todayRecords.forEach { providerRecord ->
            val stored = history.firstOrNull { it.id == providerRecord.id }
            assertNotNull("Today's provider session must be present in the credit bank", stored)
            assertEquals(providerRecord.durationMinutes, stored!!.durationMinutes)
            assertTrue("Today's session must carry a persisted credit award", stored.earnedMinutesCredited > 0)
        }
        val recentProviderHistory = history.filter { it.source == WorkRecordSource.TICKTICK_FOCUS_API }
        android.util.Log.i("TickTickFocusLiveTest", "Bank verification: today API=${todayRecords.sumOf { it.durationMinutes }} focus minutes; stored recent=${recentProviderHistory.sumOf { it.durationMinutes }} focus minutes, ${recentProviderHistory.sumOf { it.earnedMinutesCredited }} earned minutes; current balance=${app.creditBankRepository.getBalanceSeconds()} seconds")
        val stats = app.creditBankRepository.statsFlow.first()
        android.util.Log.i("TickTickFocusLiveTest", "Daily bank: focus=${stats.totalWorkMinutesToday} minutes, leisure used=${stats.totalDoomscrollMinutesToday} minutes, date=${stats.lastResetDate}")
        val second = app.tickTickFocusSync.sync()!!
        assertEquals("Repeated sync must not award duplicate credit", 0, second.newSessions)
        assertEquals(0, second.earnedMinutes)
    }
}
