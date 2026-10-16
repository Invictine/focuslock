package com.focuslock.app

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.reminder.ReminderMediaOptimizer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReminderMediaOptimizerTest {
    @Test fun compressesVideoOncePreservingAudioDurationAndOfflinePlayback() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File.createTempFile("reminder-source-", ".mp4", context.cacheDir)
        var output: File? = null
        try {
            instrumentation.context.assets.open("reminder-compression-test.mp4").use { source ->
                input.outputStream().use { source.copyTo(it) }
            }
            output = ReminderMediaOptimizer.optimize(context, input)
            assertNotEquals("Encoder should produce a smaller copy of the large source", input.absolutePath, output.absolutePath)
            assertTrue("Only the smaller result should be kept", output.length() < input.length())
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(output.absolutePath)
                assertEquals("yes", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertEquals("yes", metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                val duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("The complete clip must survive compression", duration in 1_800..2_250)
                assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt() <= 480)
                assertTrue(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt() <= 854)
            } finally { metadata.release() }
            assertTrue("Optimization never deletes the source during an import", input.isFile)
        } finally {
            if (output != input) output?.delete()
            input.delete()
        }
    }

    @Test fun avoidsReencodingAnAlreadyCompactVideo() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val input = File.createTempFile("reminder-efficient-", ".mp4", context.cacheDir)
        try {
            instrumentation.context.assets.open("reminder-test-video.mp4").use { source ->
                input.outputStream().use { source.copyTo(it) }
            }
            assertEquals(input, ReminderMediaOptimizer.optimize(context, input))
        } finally { input.delete() }
    }
}
