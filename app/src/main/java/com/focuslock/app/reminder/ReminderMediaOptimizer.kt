package com.focuslock.app.reminder

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/** Produces a compact local playback copy; never uploads, truncates a clip or deletes the input. */
@OptIn(UnstableApi::class)
object ReminderMediaOptimizer {
    const val MAX_TRANSFER_BYTES = 12L * 1024L * 1024L
    private const val AUDIO_BITRATE = 32_000

    /** The caller owns the returned temporary file when it differs from [input]. */
    suspend fun optimize(context: Context, input: File): File {
        val info = withContext(Dispatchers.IO) { inspect(input) } ?: return input
        if (ReminderCompressionPolicy.alreadyCompact(info.width, info.height, info.bitrate, info.hasVideo)) {
            return input
        }
        val output = File.createTempFile("reminder-compact-", ".mp4", context.cacheDir)
        output.delete() // Transformer requires an absent destination.
        try {
            val success = withTimeoutOrNull(180_000L) {
                withContext(Dispatchers.Main.immediate) {
                    transcode(context.applicationContext, input, output, info)
                }
            } == true
            // Re-encoding a small, efficient source can make it larger. Keep the smaller playable file.
            val valid = success && output.length() in 1 until input.length() &&
                withContext(Dispatchers.IO) { inspect(output) != null }
            if (valid) return output
        } catch (cancelled: CancellationException) {
            output.delete()
            throw cancelled
        } catch (_: Exception) {
            // Some OEM codecs reject a supported format at runtime. Local playback remains available.
        }
        output.delete()
        return input
    }

    private data class MediaInfo(val width: Int, val height: Int, val bitrate: Long, val hasVideo: Boolean)

    private fun inspect(file: File): MediaInfo? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val video = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val audio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            if (!video && !audio) return null
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) { val previous = width; width = height; height = previous }
            MediaInfo(width, height,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L, video)
        } catch (_: Exception) { null }
        finally { retriever.release() }
    }

    private suspend fun transcode(context: Context, input: File, output: File, info: MediaInfo): Boolean =
        suspendCancellableCoroutine { continuation ->
            val hevc = info.hasVideo && MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
                it.isEncoder && it.supportedTypes.any { mime -> mime.equals(MimeTypes.VIDEO_H265, true) }
            }
            val encoder = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder()
                    .setBitrate(if (hevc) 320_000 else 480_000).build())
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(AUDIO_BITRATE).build())
                .build()
            val effects = if (info.hasVideo) listOf(
                Presentation.createForHeight(ReminderCompressionPolicy.outputHeight(info.width, info.height)),
                FrameDropEffect.createDefaultFrameDropEffect(24f),
            ) else emptyList()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setEffects(Effects(emptyList(), effects)).build()
            val builder = Transformer.Builder(context).setEncoderFactory(encoder)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }
                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                })
            if (info.hasVideo) builder.setVideoMimeType(if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
            val transformer = builder.build()
            continuation.invokeOnCancellation {
                android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel(); output.delete() }
            }
            try { transformer.start(item, output.absolutePath) }
            catch (_: Exception) { if (continuation.isActive) continuation.resume(false) }
        }
}

/** Shared geometry/bandwidth decisions, independent of device encoders. */
internal object ReminderCompressionPolicy {
    fun alreadyCompact(width: Int, height: Int, bitrate: Long, hasVideo: Boolean): Boolean =
        bitrate in 1..(if (hasVideo) 352_000L else 32_000L) &&
            (!hasVideo || (maxOf(width, height) <= 854 && minOf(width, height) <= 480))

    fun outputHeight(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 480
        val scale = minOf(1.0, 854.0 / maxOf(width, height), 480.0 / minOf(width, height))
        return ((height * scale).toInt() / 2 * 2).coerceAtLeast(2)
    }
}
