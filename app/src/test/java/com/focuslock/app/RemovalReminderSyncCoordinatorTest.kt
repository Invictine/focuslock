package com.focuslock.app

import com.focuslock.app.reminder.RemovalReminderStore
import com.focuslock.app.sync.RemovalReminderRemoteState
import com.focuslock.app.sync.RemovalReminderSyncCoordinator
import com.focuslock.app.sync.RemovalReminderSyncTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

class RemovalReminderSyncCoordinatorTest {
    @Test fun devicesShareSwitchAndMediaOnceThenSkipUnchangedTransfers() = runBlocking {
        val content = "compact reminder clip".toByteArray()
        val file = File.createTempFile("reminder-sync", ".mp4").apply { writeBytes(content) }
        try {
            val backend = FakeTransport()
            val a = FakeStore("account")
            a.update(enabled = true, enabledAt = 100, mediaAt = 101, bytes = content, name = "clip.mp4")
            val b = FakeStore("account")
            val aSync = RemovalReminderSyncCoordinator(a)
            val bSync = RemovalReminderSyncCoordinator(b)

            aSync.sync(backend, "account", { true })
            bSync.sync(backend, "account", { true })
            assertEquals(true, b.state.enabled)
            assertEquals("clip.mp4", b.state.mediaName)
            assertEquals(1, backend.uploads)
            assertEquals(1, backend.downloads)

            aSync.sync(backend, "account", { true })
            bSync.sync(backend, "account", { true })
            assertEquals(1, backend.uploads)
            assertEquals(1, backend.downloads)

            a.update(enabled = false, enabledAt = 200)
            aSync.sync(backend, "account", { true })
            assertFalse(backend.state.enabled)
            assertEquals(1, backend.uploads)

            a.update(mediaAt = 300, bytes = null)
            aSync.sync(backend, "account", { true })
            assertEquals(null, backend.state.mediaSha256)
            // A stale offline copy cannot overwrite a newer deletion tombstone.
            b.update(mediaAt = 101, bytes = content)
            bSync.sync(backend, "account", { true })
            assertEquals(null, b.state.mediaSha256)
            assertEquals(null, backend.state.mediaSha256)
            assertEquals(1, backend.uploads)
        } finally { file.delete() }
    }

    @Test fun accountChangeDuringMediaDownloadDiscardsResponse() = runBlocking {
        val bytes = "private clip".toByteArray()
        val backend = FakeTransport().apply { publish(bytes) }
        val local = FakeStore("account-A")
        var current = true
        backend.afterDownload = { current = false }
        RemovalReminderSyncCoordinator(local).sync(backend, "account-A", { current })
        assertEquals(null, local.state.mediaSha256)
    }

    @Test fun newerEqualValueIntentAndMediaClockBeatStaleOfflineEdits() = runBlocking {
        val bytes = "clip".toByteArray()
        val backend = FakeTransport().apply { publish(bytes) }
        backend.state = backend.state.copy(enabled = false, enabledUpdatedAt = 100, mediaUpdatedAt = 100)
        val current = FakeStore("account")
        current.update(enabled = false, enabledAt = 300, mediaAt = 300, bytes = bytes)
        RemovalReminderSyncCoordinator(current).sync(backend, "account", { true })
        assertEquals(false, backend.state.enabled)
        assertEquals(300L, backend.state.enabledUpdatedAt)
        assertEquals(300L, backend.state.mediaUpdatedAt)
        assertEquals(0, backend.uploads)

        val stale = FakeStore("account")
        stale.update(enabled = true, enabledAt = 200, mediaAt = 200, bytes = null)
        RemovalReminderSyncCoordinator(stale).sync(backend, "account", { true })
        assertEquals(false, backend.state.enabled)
        assertEquals(300L, backend.state.enabledUpdatedAt)
        assertEquals(300L, backend.state.mediaUpdatedAt)
        assertEquals(digest(bytes), backend.state.mediaSha256)
        assertEquals(0, backend.uploads)
    }

    @Test fun localEditDuringRemoteReadSurvivesAndIsUploadedOnRetry() = runBlocking {
        val backend = FakeTransport().apply { state = state.copy(revision = 1, enabled = true, enabledUpdatedAt = 100) }
        val local = FakeStore("account")
        backend.afterRead = { local.update(enabled = false, enabledAt = 200) }
        RemovalReminderSyncCoordinator(local).sync(backend, "account", { true })
        assertEquals(false, local.state.enabled)
        assertEquals(false, backend.state.enabled)
        assertEquals(200L, backend.state.enabledUpdatedAt)
    }

    @Test fun newerNullTombstoneClockDefeatsIntermediateOfflineUpload() = runBlocking {
        val bytes = "stale offline clip".toByteArray()
        val backend = FakeTransport().apply { publish(bytes); state = state.copy(mediaUpdatedAt = 200) }
        val current = FakeStore("account").apply { update(mediaAt = 300, bytes = null) }
        RemovalReminderSyncCoordinator(current).sync(backend, "account", { true })
        assertEquals(null, backend.state.mediaSha256)
        assertEquals(300L, backend.state.mediaUpdatedAt)

        val stale = FakeStore("account").apply { update(mediaAt = 250, bytes = bytes, name = "old.mp4") }
        RemovalReminderSyncCoordinator(stale).sync(backend, "account", { true })
        assertEquals(null, backend.state.mediaSha256)
        assertEquals(null, stale.state.mediaSha256)
        assertEquals(0, backend.uploads)
    }

    @Test fun failedClearWithNewerRemoteClipRetriesAndDownloadsIt() = runBlocking {
        val oldClip = "old clip".toByteArray()
        val newClip = "newer remote clip".toByteArray()
        val backend = FakeTransport().apply { publish(oldClip) }
        val current = FakeStore("account").apply { update(mediaAt = 300, bytes = null) }
        backend.beforeClear = {
            backend.media = newClip
            backend.state = backend.state.copy(revision = backend.state.revision + 1, mediaUpdatedAt = 400,
                mediaSha256 = digest(newClip), mediaName = "new.mp4", mediaBytes = newClip.size.toLong())
        }
        RemovalReminderSyncCoordinator(current).sync(backend, "account", { true })
        assertEquals(digest(newClip), current.state.mediaSha256)
        assertEquals("new.mp4", current.state.mediaName)
        assertEquals(1, backend.downloads)
        assertEquals(0, backend.uploads)
    }

    @Test fun sameValueEnabledCasConflictStillAdvancesNewerLocalClock() = runBlocking {
        val backend = FakeTransport().apply { state = state.copy(revision = 1, enabled = false, enabledUpdatedAt = 100) }
        val current = FakeStore("account").apply { update(enabled = false, enabledAt = 300) }
        backend.beforeEnabledSave = {
            backend.state = backend.state.copy(revision = backend.state.revision + 1, enabled = false, enabledUpdatedAt = 200)
        }
        RemovalReminderSyncCoordinator(current).sync(backend, "account", { true })
        assertEquals(false, backend.state.enabled)
        assertEquals(300L, backend.state.enabledUpdatedAt)
        assertEquals(false, current.state.dirtyEnabled)
    }

    private class FakeStore(override var owner: String) : RemovalReminderStoreSyncFake {
        var state = RemovalReminderStore.SyncState(owner, false, 0, 0, null, null, null, null, false, false, 0)

        fun update(enabled: Boolean? = null, enabledAt: Long? = null, mediaAt: Long? = null,
            bytes: ByteArray? = UNSET, name: String? = null) {
            val nextBytes = if (bytes === UNSET) state.mediaFile?.readBytes() else bytes
            val mediaFile = nextBytes?.let { File.createTempFile("reminder-fake", ".mp4").apply { writeBytes(it) } }
            state = state.copy(
                enabled = enabled ?: state.enabled,
                enabledUpdatedAt = enabledAt ?: state.enabledUpdatedAt,
                mediaUpdatedAt = mediaAt ?: state.mediaUpdatedAt,
                mediaSha256 = nextBytes?.let(::digest), mediaName = if (nextBytes == null) null else name ?: state.mediaName ?: "clip.mp4",
                mediaMimeType = if (nextBytes == null) null else "video/mp4", mediaFile = mediaFile,
                dirtyEnabled = enabledAt != null || state.dirtyEnabled,
                dirtyMedia = bytes !== UNSET || state.dirtyMedia,
            )
        }

        override fun syncState(owner: String) = state.takeIf { this.owner == owner }
        override fun setRemoteEnabled(owner: String, enabled: Boolean, updatedAt: Long, revision: Long, expectedLocalUpdatedAt: Long): Boolean {
            if (this.owner != owner || state.enabledUpdatedAt != expectedLocalUpdatedAt) return false
            state = state.copy(enabled = enabled, enabledUpdatedAt = updatedAt, revision = revision, dirtyEnabled = false)
            return true
        }
        override fun acknowledgeEnabled(owner: String, localUpdatedAt: Long, revision: Long) {
            if (this.owner == owner && state.enabledUpdatedAt == localUpdatedAt) state = state.copy(revision = revision, dirtyEnabled = false)
        }
        override fun acknowledgeMedia(owner: String, localUpdatedAt: Long, revision: Long) {
            if (this.owner == owner && state.mediaUpdatedAt == localUpdatedAt) state = state.copy(revision = revision, dirtyMedia = false)
        }
        override fun applyRemoteMedia(owner: String, bytes: ByteArray?, sha256: String?, name: String?, mimeType: String?, updatedAt: Long,
            revision: Long, expectedLocalUpdatedAt: Long): Boolean {
            if (this.owner != owner || state.mediaUpdatedAt != expectedLocalUpdatedAt) return false
            val mediaFile = bytes?.let { File.createTempFile("reminder-fake-remote", ".mp4").apply { writeBytes(it) } }
            state = state.copy(mediaSha256 = sha256, mediaName = name, mediaMimeType = mimeType, mediaFile = mediaFile,
                mediaUpdatedAt = updatedAt, revision = revision, dirtyMedia = false)
            return true
        }
        override suspend fun prepareMediaForUpload(owner: String, expectedMediaUpdatedAt: Long) =
            state.takeIf { this.owner == owner && it.mediaUpdatedAt == expectedMediaUpdatedAt }

        companion object { private val UNSET = ByteArray(0) }
    }

    /** Local test fake can satisfy the same repository methods without Android storage. */
    private interface RemovalReminderStoreSyncFake : com.focuslock.app.sync.RemovalReminderSyncStore {
        var owner: String
    }

    private class FakeTransport : RemovalReminderSyncTransport {
        var state = RemovalReminderRemoteState(0, false, 0, 0, null, null, null, 0)
        var media: ByteArray? = null
        var uploads = 0
        var downloads = 0
        var afterDownload: (() -> Unit)? = null
        var afterRead: (() -> Unit)? = null
        var beforeClear: (() -> Unit)? = null
        var beforeEnabledSave: (() -> Unit)? = null

        override suspend fun getRemovalReminderState(): RemovalReminderRemoteState {
            afterRead?.also { afterRead = null; it() }
            return state
        }
        override suspend fun saveRemovalReminderEnabled(expectedRevision: Long, enabled: Boolean, updatedAt: Long): Pair<Boolean, RemovalReminderRemoteState> {
            beforeEnabledSave?.also { beforeEnabledSave = null; it() }
            if (expectedRevision != state.revision) return false to state
            if (updatedAt <= state.enabledUpdatedAt) return false to state
            state = state.copy(revision = state.revision + 1, enabled = enabled, enabledUpdatedAt = updatedAt)
            return true to state
        }
        override suspend fun clearRemovalReminderMedia(expectedRevision: Long, updatedAt: Long): Pair<Boolean, RemovalReminderRemoteState> {
            beforeClear?.also { beforeClear = null; it() }
            if (expectedRevision != state.revision) return false to state
            if (updatedAt <= state.mediaUpdatedAt) return false to state
            state = state.copy(revision = state.revision + 1, mediaUpdatedAt = updatedAt, mediaSha256 = null,
                mediaMimeType = null, mediaName = null, mediaBytes = 0)
            media = null
            return true to state
        }
        override suspend fun touchRemovalReminderMedia(expectedRevision: Long, sha256: String, updatedAt: Long): Pair<Boolean, RemovalReminderRemoteState> {
            if (expectedRevision != state.revision || sha256 != state.mediaSha256 || updatedAt <= state.mediaUpdatedAt) return false to state
            state = state.copy(revision = state.revision + 1, mediaUpdatedAt = updatedAt)
            return true to state
        }
        override suspend fun uploadRemovalReminderMedia(file: File, sha256: String, mimeType: String, name: String,
            expectedRevision: Long, updatedAt: Long): Pair<Boolean, RemovalReminderRemoteState> {
            if (expectedRevision != state.revision) return false to state
            uploads++
            media = file.readBytes()
            state = state.copy(revision = state.revision + 1, mediaUpdatedAt = updatedAt, mediaSha256 = digest(media!!),
                mediaMimeType = mimeType, mediaName = name, mediaBytes = media!!.size.toLong())
            return true to state
        }
        override suspend fun downloadRemovalReminderMedia(sha256: String): ByteArray {
            downloads++
            val result = media!!.copyOf()
            afterDownload?.invoke()
            return result
        }
        fun publish(bytes: ByteArray) {
            media = bytes
            state = state.copy(revision = 1, enabled = true, enabledUpdatedAt = 1, mediaUpdatedAt = 2,
                mediaSha256 = digest(bytes), mediaMimeType = "video/mp4", mediaName = "remote.mp4", mediaBytes = bytes.size.toLong())
        }
    }

    companion object {
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
