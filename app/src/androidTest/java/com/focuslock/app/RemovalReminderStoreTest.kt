package com.focuslock.app

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.reminder.RemovalReminderStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class RemovalReminderStoreTest {
    private lateinit var context: android.content.Context
    private lateinit var store: RemovalReminderStore
    private lateinit var backup: File
    private var priorName: String? = null
    private var priorMime: String? = null
    private var priorEnabled = false
    private var hadPriorMedia = false

    @Before
    fun preserveExistingReminderSettings() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        store = RemovalReminderStore(context)
        backup = File(context.cacheDir, "removal-reminder-store-restore.tmp")
        backup.delete()
        priorEnabled = store.enabled
        priorName = store.mediaName()
        priorMime = store.mediaMimeType()
        store.mediaFile()?.let { original ->
            original.copyTo(backup, overwrite = true)
            hadPriorMedia = true
        }
    }

    @After
    fun restoreExistingReminderSettings() = runBlocking<Unit> {
        store.clearMedia()
        if (hadPriorMedia && backup.isFile) {
            store.importMedia(Uri.fromFile(backup))
            // Keep the pre-test metadata exactly as it was, including provider-specific MIME.
            context.getSharedPreferences("removal_reminder_media", android.content.Context.MODE_PRIVATE)
                .edit().putString("display_name", priorName).putString("mime_type", priorMime).commit()
        }
        store.enabled = priorEnabled
        backup.delete()
    }

    @Test
    fun rejectsInvalidAndOversizedMediaWithoutReplacingExistingClipAndClearKeepsEnabledIntent() = runBlocking<Unit> {
        val wav = File(context.cacheDir, "removal-reminder-test.wav")
        val invalid = File(context.cacheDir, "removal-reminder-invalid.txt")
        val oversized = File(context.cacheDir, "removal-reminder-oversized.bin")
        writeSilentWav(wav)
        invalid.writeText("This is not audio or video")
        RandomAccessFile(oversized, "rw").use { it.setLength(50L * 1024L * 1024L + 1L) }

        try {
            store.enabled = false
            store.importMedia(Uri.fromFile(wav))
            assertValidImportedWav(wav)
            val priorBytes = store.mediaFile()!!.readBytes()
            val priorImportedName = store.mediaName()
            val priorImportedMime = store.mediaMimeType()

            assertImportRejected(Uri.fromFile(invalid))
            assertCurrentMedia(priorBytes, priorImportedName, priorImportedMime)

            assertImportRejected(Uri.fromFile(oversized))
            assertCurrentMedia(priorBytes, priorImportedName, priorImportedMime)

            store.enabled = true
            store.clearMedia()
            assertEquals("Clearing media must preserve the explicit enabled preference", true, store.enabled)
            assertEquals(null, store.mediaFile())
            assertEquals(null, store.mediaName())
            assertEquals(null, store.mediaMimeType())
        } finally {
            wav.delete()
            invalid.delete()
            oversized.delete()
        }
    }

    private suspend fun assertImportRejected(uri: Uri) {
        var rejected = false
        try {
            store.importMedia(uri)
        } catch (_: Exception) {
            rejected = true
        }
        assertTrue("Invalid or oversized media should be rejected", rejected)
    }

    private fun assertValidImportedWav(wav: File) {
        val saved = store.mediaFile()
        assertNotNull(saved)
        assertEquals(wav.length(), saved!!.length())
        assertEquals("removal-reminder-test.wav", store.mediaName())
        assertTrue(store.mediaMimeType().orEmpty().startsWith("audio/"))
        assertFalse(store.enabled)
    }

    private fun assertCurrentMedia(bytes: ByteArray, name: String?, mime: String?) {
        assertEquals(bytes.toList(), store.mediaFile()!!.readBytes().toList())
        assertEquals(name, store.mediaName())
        assertEquals(mime, store.mediaMimeType())
        assertFalse(store.enabled)
    }

    private fun writeSilentWav(file: File) {
        val sampleRate = 8_000
        val seconds = 1
        val dataBytes = sampleRate * seconds * 2 // 16-bit mono PCM
        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray(Charsets.US_ASCII))
            writeLe32(out, 36 + dataBytes)
            out.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            writeLe32(out, 16)
            writeLe16(out, 1) // PCM
            writeLe16(out, 1) // mono
            writeLe32(out, sampleRate)
            writeLe32(out, sampleRate * 2)
            writeLe16(out, 2)
            writeLe16(out, 16)
            out.write("data".toByteArray(Charsets.US_ASCII))
            writeLe32(out, dataBytes)
            out.write(ByteArray(dataBytes))
        }
    }

    private fun writeLe16(out: FileOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
    }

    private fun writeLe32(out: FileOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
        out.write((value shr 16) and 0xff)
        out.write((value shr 24) and 0xff)
    }
}
