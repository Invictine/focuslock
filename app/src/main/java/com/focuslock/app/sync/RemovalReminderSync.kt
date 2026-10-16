package com.focuslock.app.sync

import com.focuslock.app.reminder.RemovalReminderStore

interface RemovalReminderSyncTransport {
    suspend fun getRemovalReminderState(): RemovalReminderRemoteState
    suspend fun saveRemovalReminderEnabled(expectedRevision: Long, enabled: Boolean, updatedAt: Long): Pair<Boolean, RemovalReminderRemoteState>
    suspend fun clearRemovalReminderMedia(expectedRevision: Long, updatedAt: Long): Pair<Boolean, RemovalReminderRemoteState>
    suspend fun touchRemovalReminderMedia(expectedRevision: Long, sha256: String, updatedAt: Long): Pair<Boolean, RemovalReminderRemoteState>
    suspend fun uploadRemovalReminderMedia(file: java.io.File, sha256: String, mimeType: String, name: String,
        expectedRevision: Long, updatedAt: Long): Pair<Boolean, RemovalReminderRemoteState>
    suspend fun downloadRemovalReminderMedia(sha256: String): ByteArray
}

interface RemovalReminderSyncStore {
    fun syncState(owner: String): RemovalReminderStore.SyncState?
    fun setRemoteEnabled(owner: String, enabled: Boolean, updatedAt: Long, revision: Long, expectedLocalUpdatedAt: Long): Boolean
    fun acknowledgeEnabled(owner: String, localUpdatedAt: Long, revision: Long)
    fun acknowledgeMedia(owner: String, localUpdatedAt: Long, revision: Long)
    fun applyRemoteMedia(owner: String, bytes: ByteArray?, sha256: String?, name: String?, mimeType: String?, updatedAt: Long,
        revision: Long, expectedLocalUpdatedAt: Long): Boolean
    suspend fun prepareMediaForUpload(owner: String, expectedMediaUpdatedAt: Long): RemovalReminderStore.SyncState?
}

/** Revision-aware sync runner. The owner predicate is checked after every network suspension. */
class RemovalReminderSyncCoordinator(private val store: RemovalReminderSyncStore) {
    suspend fun sync(
        transport: RemovalReminderSyncTransport,
        accountId: String,
        stillCurrent: suspend () -> Boolean,
        onApplied: () -> Unit = {},
    ) {
        repeat(2) {
            val local = store.syncState(accountId) ?: return
            var remote = transport.getRemovalReminderState()
            if (!stillCurrent()) return

            if (local.dirtyEnabled && local.enabled == remote.enabled && local.enabledUpdatedAt <= remote.enabledUpdatedAt) {
                store.acknowledgeEnabled(accountId, local.enabledUpdatedAt, remote.revision)
            } else if (local.dirtyEnabled && local.enabledUpdatedAt > remote.enabledUpdatedAt) {
                val saved = transport.saveRemovalReminderEnabled(remote.revision, local.enabled, local.enabledUpdatedAt)
                if (!stillCurrent()) return
                if (saved.first) {
                    store.acknowledgeEnabled(accountId, local.enabledUpdatedAt, saved.second.revision)
                    remote = saved.second
                } else {
                    remote = saved.second
                    if (local.enabledUpdatedAt > remote.enabledUpdatedAt) return@repeat
                    if (!store.setRemoteEnabled(accountId, remote.enabled, remote.enabledUpdatedAt, remote.revision,
                            local.enabledUpdatedAt)) return@repeat
                }
            } else if (!local.dirtyEnabled || remote.enabledUpdatedAt >= local.enabledUpdatedAt) {
                if (!store.setRemoteEnabled(accountId, remote.enabled, remote.enabledUpdatedAt, remote.revision,
                        local.enabledUpdatedAt)) return@repeat
            }
            if (!stillCurrent()) return

            val current = store.syncState(accountId) ?: return
            if (current.enabledUpdatedAt != local.enabledUpdatedAt && current.enabled != remote.enabled) return@repeat
            if (current.dirtyMedia) {
                if (remote.mediaUpdatedAt > current.mediaUpdatedAt ||
                    (remote.mediaUpdatedAt == current.mediaUpdatedAt && current.mediaSha256 != remote.mediaSha256)) {
                    val bytes = if (remote.mediaSha256 != null && current.enabled &&
                        remote.mediaBytes <= com.focuslock.app.reminder.ReminderMediaOptimizer.MAX_TRANSFER_BYTES) {
                        transport.downloadRemovalReminderMedia(remote.mediaSha256).also {
                            if (!stillCurrent()) return
                            if (it.size.toLong() != remote.mediaBytes) throw IllegalStateException("Downloaded reminder media size did not match")
                        }
                    } else null
                    if (remote.mediaSha256 != null && current.enabled && bytes == null) {
                        throw IllegalStateException("Server reminder media exceeds the transfer limit")
                    }
                    if (!stillCurrent()) return
                    if (!store.applyRemoteMedia(accountId, bytes, remote.mediaSha256, remote.mediaName, remote.mediaMimeType,
                            remote.mediaUpdatedAt, remote.revision, current.mediaUpdatedAt)) return@repeat
                } else if (current.mediaSha256 == null) {
                    if (remote.mediaSha256 == null) {
                        if (current.mediaUpdatedAt > remote.mediaUpdatedAt) {
                            val cleared = transport.clearRemovalReminderMedia(remote.revision, current.mediaUpdatedAt)
                            if (!stillCurrent()) return
                            if (cleared.first) {
                                store.acknowledgeMedia(accountId, current.mediaUpdatedAt, cleared.second.revision)
                                remote = cleared.second
                            } else return@repeat
                        } else store.acknowledgeMedia(accountId, current.mediaUpdatedAt, remote.revision)
                    } else {
                        val cleared = transport.clearRemovalReminderMedia(remote.revision, current.mediaUpdatedAt)
                        if (!stillCurrent()) return
                        if (cleared.first) {
                            store.acknowledgeMedia(accountId, current.mediaUpdatedAt, cleared.second.revision)
                            remote = cleared.second
                        } else {
                            return@repeat
                        }
                    }
                } else if (current.mediaSha256 == remote.mediaSha256) {
                    if (current.mediaUpdatedAt > remote.mediaUpdatedAt) {
                        val touched = transport.touchRemovalReminderMedia(remote.revision, current.mediaSha256, current.mediaUpdatedAt)
                        if (!stillCurrent()) return
                        if (touched.first) {
                            store.acknowledgeMedia(accountId, current.mediaUpdatedAt, touched.second.revision)
                            remote = touched.second
                        } else return@repeat
                    } else store.acknowledgeMedia(accountId, current.mediaUpdatedAt, remote.revision)
                } else if (current.enabled || remote.enabled) {
                    val prepared = store.prepareMediaForUpload(accountId, current.mediaUpdatedAt) ?: return@repeat
                    if (!stillCurrent()) return
                    val file = prepared.mediaFile ?: throw IllegalStateException("Selected reminder media is missing")
                    val digest = prepared.mediaSha256 ?: throw IllegalStateException("Selected reminder media is missing")
                    if (digest == remote.mediaSha256) {
                        if (prepared.mediaUpdatedAt > remote.mediaUpdatedAt) {
                            val touched = transport.touchRemovalReminderMedia(remote.revision, digest, prepared.mediaUpdatedAt)
                            if (!stillCurrent()) return
                            if (touched.first) store.acknowledgeMedia(accountId, prepared.mediaUpdatedAt, touched.second.revision)
                            else return@repeat
                        } else store.acknowledgeMedia(accountId, prepared.mediaUpdatedAt, remote.revision)
                    } else {
                        val uploaded = transport.uploadRemovalReminderMedia(file, digest,
                            prepared.mediaMimeType ?: "application/octet-stream",
                            prepared.mediaName ?: "Reminder media", remote.revision, prepared.mediaUpdatedAt)
                        if (!stillCurrent()) return
                        if (uploaded.first) {
                            store.acknowledgeMedia(accountId, prepared.mediaUpdatedAt, uploaded.second.revision)
                            remote = uploaded.second
                        } else return@repeat
                    }
                }
            } else if (remote.mediaSha256 == null) {
                if (current.mediaSha256 != null && !store.applyRemoteMedia(accountId, null, null, null, null,
                        remote.mediaUpdatedAt, remote.revision, current.mediaUpdatedAt)) return@repeat
            } else if (current.enabled && current.mediaSha256 != remote.mediaSha256) {
                if (remote.mediaBytes > com.focuslock.app.reminder.ReminderMediaOptimizer.MAX_TRANSFER_BYTES) {
                    throw IllegalStateException("Server reminder media exceeds the transfer limit")
                }
                val bytes = transport.downloadRemovalReminderMedia(remote.mediaSha256)
                if (!stillCurrent()) return
                if (bytes.size.toLong() != remote.mediaBytes) throw IllegalStateException("Downloaded reminder media size did not match")
                if (!store.applyRemoteMedia(accountId, bytes, remote.mediaSha256, remote.mediaName, remote.mediaMimeType,
                        remote.mediaUpdatedAt, remote.revision, current.mediaUpdatedAt)) return@repeat
            }
            if (!stillCurrent()) return
            onApplied()
            return
        }
        throw IllegalStateException("Removal reminder changed repeatedly during sync")
    }
}
