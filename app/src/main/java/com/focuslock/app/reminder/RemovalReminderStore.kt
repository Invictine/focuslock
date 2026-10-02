package com.focuslock.app.reminder

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Stores the user's removal reminder media privately on this device. */
class RemovalReminderStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mediaDirectory = File(appContext.noBackupFilesDir, DIRECTORY)
    private val media = File(mediaDirectory, MEDIA_FILE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    fun mediaFile(): File? = media.takeIf { it.isFile && it.length() > 0L }
    fun mediaMimeType(): String? = if (mediaFile() != null) prefs.getString(KEY_MIME, null) else null
    fun mediaName(): String? = if (mediaFile() != null) prefs.getString(KEY_NAME, null) else null

    suspend fun importMedia(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        synchronized(IMPORT_LOCK) { importMediaLocked(uri, job) }
    }

    private fun importMediaLocked(uri: Uri, job: Job?) {
        if (uri.scheme != "content" && uri.scheme != "file") throw IOException("Unsupported media location")
        if (!mediaDirectory.exists() && !mediaDirectory.mkdirs()) throw IOException("Could not create private media storage")
        val temporary = File(mediaDirectory, "${MEDIA_FILE}.importing")
        temporary.delete()
        try {
            val resolver = appContext.contentResolver
            var copied = 0L
            val input = if (uri.scheme == "file") File(uri.path!!).inputStream() else resolver.openInputStream(uri)
                ?: throw IOException("Could not open selected media")
            input.use { source ->
                FileOutputStream(temporary).use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        job?.ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_BYTES) throw IOException("Media must be 50 MB or smaller")
                        target.write(buffer, 0, count)
                    }
                    target.fd.sync()
                }
            }
            if (copied == 0L) throw IOException("The selected file is empty")
            val detectedMimeType = validatePlayable(temporary)
            val resolverMimeType = runCatching { resolver.getType(uri)?.lowercase() }.getOrNull()
            val mimeType = detectedMimeType ?: resolverMimeType?.takeIf { it.startsWith("audio/") || it.startsWith("video/") }
                ?: "application/octet-stream"
            val displayName = runCatching { queryDisplayName(uri) }.getOrNull()
                ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Reminder media"

            job?.ensureActive()
            try {
                Files.move(temporary.toPath(), media.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), media.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            prefs.edit().putString(KEY_MIME, mimeType).putString(KEY_NAME, displayName).commit()
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        }
    }

    fun clearMedia() {
        synchronized(IMPORT_LOCK) {
            media.delete()
            prefs.edit().remove(KEY_MIME).remove(KEY_NAME).apply()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "file") return File(uri.path ?: return null).name
        return appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun validatePlayable(file: File): String? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            if (!hasAudio && !hasVideo) throw IOException("Choose a playable audio or video file")
            return retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.lowercase()
                ?.takeIf { it.startsWith("audio/") || it.startsWith("video/") }
                ?: if (hasVideo) "video/*" else "audio/*"
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Could not read playable audio or video from this file", failure)
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val PREFS = "removal_reminder_media"
        const val DIRECTORY = "removal_reminder"
        const val MEDIA_FILE = "reminder_media"
        const val KEY_ENABLED = "enabled"
        const val KEY_MIME = "mime_type"
        const val KEY_NAME = "display_name"
        const val MAX_BYTES = 50L * 1024L * 1024L
        val IMPORT_LOCK = Any()
    }
}
