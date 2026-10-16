package com.focuslock.app.reminder

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.focuslock.app.sync.RemovalReminderSyncStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Private offline media and account-scoped reminder preferences. */
class RemovalReminderStore(context: Context, private val storageNamespace: String? = null) : RemovalReminderSyncStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(storageNamespace?.let { "${PREFS}_$it" } ?: PREFS, Context.MODE_PRIVATE)
    private val mediaDirectory = File(appContext.noBackupFilesDir,
        if (storageNamespace == null) DIRECTORY else "$DIRECTORY-$storageNamespace")
    private val accountId: String? get() = prefs.getString(KEY_OWNER, null)
    private val legacyMedia = File(mediaDirectory, MEDIA_FILE)
    private fun mediaForAccount(owner: String? = accountId): File =
        if (owner == null) legacyMedia else File(mediaDirectory, "$MEDIA_FILE-${accountKey(owner)}")
    private val media: File get() = mediaForAccount()

    data class SyncState(
        val accountId: String,
        val enabled: Boolean,
        val enabledUpdatedAt: Long,
        val mediaUpdatedAt: Long,
        val mediaSha256: String?,
        val mediaName: String?,
        val mediaMimeType: String?,
        val mediaFile: File?,
        val dirtyEnabled: Boolean,
        val dirtyMedia: Boolean,
        val revision: Long,
    )

    /** Bind reminder data to the signed-in subject, retaining isolated offline edits per account. */
    fun bindAccount(owner: String) {
      synchronized(IMPORT_LOCK) {
        if (owner.isBlank() || accountId == owner) return@synchronized
        val hasOwner = prefs.getBoolean(KEY_HAS_CLAIMED_OWNER, false)
        if (!hasOwner) {
            // One-time upgrade: the pre-account local preference/media belongs to the first
            // signed-in owner. A fresh install has no dirty values and therefore pulls remote.
            val legacyEnabled = prefs.getBoolean(KEY_ENABLED, false)
            val legacyEnabledAt = prefs.getLong(KEY_ENABLED_AT, 0L)
            val legacyFile = legacyMedia.takeIf { it.isFile && it.length() > 0L }
            val target = mediaForAccount(owner)
            if (legacyFile != null && !target.exists()) {
                if (!mediaDirectory.exists()) mediaDirectory.mkdirs()
                legacyFile.copyTo(target, overwrite = true)
            }
            val edit = prefs.edit().putBoolean(KEY_HAS_CLAIMED_OWNER, true).putString(KEY_OWNER, owner)
            if (legacyEnabled || legacyEnabledAt > 0L) {
                edit.putBoolean(accountKey(owner, KEY_ENABLED), legacyEnabled)
                    .putLong(accountKey(owner, KEY_ENABLED_AT), legacyEnabledAt.takeIf { it > 0L } ?: System.currentTimeMillis())
                    .putBoolean(accountKey(owner, KEY_DIRTY_ENABLED), true)
            }
            if (legacyFile != null) {
                edit.putString(accountKey(owner, KEY_MIME), prefs.getString(KEY_MIME, null))
                    .putString(accountKey(owner, KEY_NAME), prefs.getString(KEY_NAME, legacyFile.name))
                    .putLong(accountKey(owner, KEY_MEDIA_AT), maxOf(System.currentTimeMillis(),
                        prefs.getLong(accountKey(owner, KEY_MEDIA_AT), 0L) + 1L))
                    .putString(accountKey(owner, KEY_MEDIA_HASH), sha256(target))
                    .putBoolean(accountKey(owner, KEY_DIRTY_MEDIA), true)
            }
            if (edit.commit()) legacyMedia.delete()
        } else {
            prefs.edit().putString(KEY_OWNER, owner).commit()
        }
      }
    }

    override fun syncState(owner: String): SyncState? = synchronized(IMPORT_LOCK) {
        if (accountId != owner) return null
        val file = mediaForAccount(owner).takeIf { it.isFile && it.length() > 0L }
        val sizeKey = accountKey(owner, KEY_MEDIA_LENGTH)
        val modifiedKey = accountKey(owner, KEY_MEDIA_MODIFIED)
        val cachedHash = prefs.getString(accountKey(owner, KEY_MEDIA_HASH), null)
        val hash = file?.let {
            if (cachedHash != null && prefs.getLong(sizeKey, -1L) == it.length() &&
                prefs.getLong(modifiedKey, -1L) == it.lastModified()) cachedHash else sha256(it)
        }
        if (hash != prefs.getString(accountKey(owner, KEY_MEDIA_HASH), null)) {
            prefs.edit().putString(accountKey(owner, KEY_MEDIA_HASH), hash).apply()
        }
        if (file != null) prefs.edit().putLong(sizeKey, file.length()).putLong(modifiedKey, file.lastModified()).apply()
        SyncState(owner,
            prefs.getBoolean(accountKey(owner, KEY_ENABLED), false),
            prefs.getLong(accountKey(owner, KEY_ENABLED_AT), 0L),
            prefs.getLong(accountKey(owner, KEY_MEDIA_AT), 0L), hash,
            if (file != null) prefs.getString(accountKey(owner, KEY_NAME), null) else null,
            if (file != null) prefs.getString(accountKey(owner, KEY_MIME), null) else null,
            file,
            prefs.getBoolean(accountKey(owner, KEY_DIRTY_ENABLED), false),
            prefs.getBoolean(accountKey(owner, KEY_DIRTY_MEDIA), false),
            prefs.getLong(accountKey(owner, KEY_REVISION), 0L))
    }

    override fun setRemoteEnabled(owner: String, enabled: Boolean, updatedAt: Long, revision: Long, expectedLocalUpdatedAt: Long): Boolean = synchronized(IMPORT_LOCK) {
        if (accountId != owner || prefs.getLong(accountKey(owner, KEY_ENABLED_AT), 0L) != expectedLocalUpdatedAt) return false
        prefs.edit().putBoolean(accountKey(owner, KEY_ENABLED), enabled)
            .putLong(accountKey(owner, KEY_ENABLED_AT), updatedAt).putLong(accountKey(owner, KEY_REVISION), revision)
            .putBoolean(accountKey(owner, KEY_DIRTY_ENABLED), false).commit()
    }

    override fun acknowledgeEnabled(owner: String, localUpdatedAt: Long, revision: Long) = synchronized(IMPORT_LOCK) {
        if (accountId == owner && prefs.getLong(accountKey(owner, KEY_ENABLED_AT), -1L) == localUpdatedAt)
            prefs.edit().putLong(accountKey(owner, KEY_REVISION), revision).putBoolean(accountKey(owner, KEY_DIRTY_ENABLED), false).commit()
    }

    override fun acknowledgeMedia(owner: String, localUpdatedAt: Long, revision: Long) = synchronized(IMPORT_LOCK) {
        if (accountId == owner && prefs.getLong(accountKey(owner, KEY_MEDIA_AT), -1L) == localUpdatedAt)
            prefs.edit().putLong(accountKey(owner, KEY_REVISION), revision).putBoolean(accountKey(owner, KEY_DIRTY_MEDIA), false).commit()
    }

    override fun applyRemoteMedia(owner: String, bytes: ByteArray?, sha256: String?, name: String?, mimeType: String?, updatedAt: Long, revision: Long,
        expectedLocalUpdatedAt: Long): Boolean = synchronized(IMPORT_LOCK) {
        if (accountId != owner || prefs.getLong(accountKey(owner, KEY_MEDIA_AT), 0L) != expectedLocalUpdatedAt) return false
        val target = mediaForAccount(owner)
        if (bytes == null || sha256 == null) target.delete() else {
            if (!mediaDirectory.exists()) mediaDirectory.mkdirs()
            val temporary = File(mediaDirectory, "$MEDIA_FILE-${accountKey(owner, "tmp")}")
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            check(sha256(temporary) == sha256) { "Downloaded reminder media failed its integrity check" }
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
        prefs.edit().putString(accountKey(owner, KEY_MEDIA_HASH), sha256)
            .putString(accountKey(owner, KEY_NAME), name).putString(accountKey(owner, KEY_MIME), mimeType)
            .putLong(accountKey(owner, KEY_MEDIA_AT), updatedAt).putLong(accountKey(owner, KEY_REVISION), revision)
            .putLong(accountKey(owner, KEY_MEDIA_LENGTH), bytes?.size?.toLong() ?: 0L)
            .putLong(accountKey(owner, KEY_MEDIA_MODIFIED), target.lastModified())
            .putBoolean(accountKey(owner, KEY_DIRTY_MEDIA), false).commit()
        true
    }

    /** One-time optimization for upgraded local clips; never runs while holding the import lock. */
    override suspend fun prepareMediaForUpload(owner: String, expectedMediaUpdatedAt: Long): SyncState? {
        val before = syncState(owner) ?: return null
        val input = before.mediaFile ?: return before
        val sourceHash = before.mediaSha256 ?: return before
        if (prefs.getString(accountKey(owner, KEY_OPTIMIZED_HASH), null) == sourceHash) return before
        val optimized = ReminderMediaOptimizer.optimize(appContext, input)
        try {
            synchronized(IMPORT_LOCK) {
                if (accountId != owner || prefs.getLong(accountKey(owner, KEY_MEDIA_AT), 0L) != expectedMediaUpdatedAt) return null
                val target = mediaForAccount(owner)
                if (!target.isFile || sha256(target) != sourceHash) return null
                if (optimized != input && optimized.isFile && optimized.length() in 1..ReminderMediaOptimizer.MAX_TRANSFER_BYTES &&
                    optimized.length() < target.length()) {
                    val mimeType = validatePlayable(optimized)
                        ?: throw IOException("Could not identify optimized reminder media")
                    try { Files.move(optimized.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                    catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                        Files.move(optimized.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    val digest = sha256(target)
                    check(prefs.edit().putString(accountKey(owner, KEY_MEDIA_HASH), digest)
                        .putLong(accountKey(owner, KEY_MEDIA_LENGTH), target.length())
                        .putLong(accountKey(owner, KEY_MEDIA_MODIFIED), target.lastModified())
                        .putString(accountKey(owner, KEY_MIME), mimeType)
                        .putString(accountKey(owner, KEY_OPTIMIZED_HASH), digest)
                        .putBoolean(accountKey(owner, KEY_DIRTY_MEDIA), true).commit())
                } else {
                    check(prefs.edit().putString(accountKey(owner, KEY_OPTIMIZED_HASH), sourceHash).commit())
                }
            }
        } finally {
            if (optimized != input) optimized.delete()
        }
        return syncState(owner)
    }

    private fun accountKey(owner: String, key: String) = "${key}_${accountKey(owner)}"
    private fun accountKey(owner: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(owner.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    var enabled: Boolean
        get() = if (accountId == null) prefs.getBoolean(KEY_ENABLED, false) else prefs.getBoolean(accountKey(accountId!!, KEY_ENABLED), false)
        set(value) = synchronized(IMPORT_LOCK) {
            val owner = accountId
            val at = maxOf(System.currentTimeMillis(), prefs.getLong(owner?.let { accountKey(it, KEY_ENABLED_AT) } ?: KEY_ENABLED_AT, 0L) + 1L)
            if (owner == null) prefs.edit().putBoolean(KEY_ENABLED, value).putLong(KEY_ENABLED_AT, at).apply()
            else prefs.edit().putBoolean(accountKey(owner, KEY_ENABLED), value).putLong(accountKey(owner, KEY_ENABLED_AT), at)
                .putBoolean(accountKey(owner, KEY_DIRTY_ENABLED), true).apply()
        }

    fun mediaFile(): File? = media.takeIf { it.isFile && it.length() > 0L }
    fun mediaMimeType(): String? = if (mediaFile() != null) prefs.getString(accountId?.let { accountKey(it, KEY_MIME) } ?: KEY_MIME, null) else null
    fun mediaName(): String? = if (mediaFile() != null) prefs.getString(accountId?.let { accountKey(it, KEY_NAME) } ?: KEY_NAME, null) else null

    suspend fun importMedia(uri: Uri): Unit = IMPORT_MUTEX.withLock { withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        importMediaLocked(uri, job, accountId)
    } }

    private suspend fun importMediaLocked(uri: Uri, job: Job?, owner: String?) {
        if (uri.scheme != "content" && uri.scheme != "file") throw IOException("Unsupported media location")
        if (!mediaDirectory.exists() && !mediaDirectory.mkdirs()) throw IOException("Could not create private media storage")
        val temporary = File(mediaDirectory, "${MEDIA_FILE}.importing")
        temporary.delete()
        var optimizedTemporary: File? = null
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
            val optimized = ReminderMediaOptimizer.optimize(appContext, temporary)
            if (optimized != temporary) optimizedTemporary = optimized
            job?.ensureActive()
            if (owner != accountId) throw IOException("The signed-in account changed while importing media")
            val committed = if (optimized.isFile && optimized.length() in 1..com.focuslock.app.reminder.ReminderMediaOptimizer.MAX_TRANSFER_BYTES) optimized else temporary
            if (!committed.isFile || committed.length() > MAX_BYTES) throw IOException("Media must be 50 MB or smaller")
            val detectedMimeType = validatePlayable(committed)
            val resolverMimeType = runCatching { resolver.getType(uri)?.lowercase() }.getOrNull()
            val mimeType = detectedMimeType ?: resolverMimeType?.takeIf { it.startsWith("audio/") || it.startsWith("video/") }
                ?: "application/octet-stream"
            val displayName = runCatching { queryDisplayName(uri) }.getOrNull()
                ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Reminder media"

            job?.ensureActive()
            synchronized(IMPORT_LOCK) {
                if (owner != accountId) throw IOException("The signed-in account changed while importing media")
                val target = mediaForAccount(owner)
                try {
                    Files.move(committed.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (unsupported: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(committed.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                val edit = prefs.edit().putString(owner?.let { accountKey(it, KEY_MIME) } ?: KEY_MIME, mimeType)
                    .putString(owner?.let { accountKey(it, KEY_NAME) } ?: KEY_NAME, displayName)
                if (owner != null) edit.putString(accountKey(owner, KEY_MEDIA_HASH), sha256(target))
                    .putLong(accountKey(owner, KEY_MEDIA_LENGTH), target.length())
                    .putLong(accountKey(owner, KEY_MEDIA_MODIFIED), target.lastModified())
                    .putString(accountKey(owner, KEY_OPTIMIZED_HASH), sha256(target))
                    .putLong(accountKey(owner, KEY_MEDIA_AT), maxOf(System.currentTimeMillis(),
                        prefs.getLong(accountKey(owner, KEY_MEDIA_AT), 0L) + 1L))
                    .putBoolean(accountKey(owner, KEY_DIRTY_MEDIA), true)
                edit.commit()
            }
        } catch (failure: Exception) {
            temporary.delete()
            throw failure
        } finally { optimizedTemporary?.delete(); temporary.delete() }
    }

    fun clearMedia() {
        synchronized(IMPORT_LOCK) {
            media.delete()
            val owner = accountId
            val edit = prefs.edit().remove(owner?.let { accountKey(it, KEY_MIME) } ?: KEY_MIME)
                .remove(owner?.let { accountKey(it, KEY_NAME) } ?: KEY_NAME)
            if (owner != null) edit.remove(accountKey(owner, KEY_MEDIA_HASH)).putLong(accountKey(owner, KEY_MEDIA_LENGTH), 0L)
                .putLong(accountKey(owner, KEY_MEDIA_MODIFIED), 0L)
                .putLong(accountKey(owner, KEY_MEDIA_AT), maxOf(System.currentTimeMillis(),
                    prefs.getLong(accountKey(owner, KEY_MEDIA_AT), 0L) + 1L))
                .putBoolean(accountKey(owner, KEY_DIRTY_MEDIA), true)
            edit.apply()
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
        const val KEY_OWNER = "owner_account"
        const val KEY_HAS_CLAIMED_OWNER = "has_claimed_owner"
        const val KEY_ENABLED_AT = "enabled_updated_at"
        const val KEY_MEDIA_AT = "media_updated_at"
        const val KEY_MEDIA_HASH = "media_sha256"
        const val KEY_DIRTY_ENABLED = "enabled_dirty"
        const val KEY_DIRTY_MEDIA = "media_dirty"
        const val KEY_REVISION = "revision"
        const val KEY_MEDIA_LENGTH = "media_length"
        const val KEY_MEDIA_MODIFIED = "media_modified"
        const val KEY_OPTIMIZED_HASH = "media_optimization_attempted_sha256"
        const val MAX_BYTES = 50L * 1024L * 1024L
        val IMPORT_LOCK = Any()
        val IMPORT_MUTEX = Mutex()
    }
}
