package com.focuslock.app.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.targetGroupsDataStore by preferencesDataStore(name = "focuslock_target_groups")

/** One member of a merged group: an app package or a website domain. */
@Serializable
data class TargetGroupMember(
    val targetKind: String, // "app" | "website"
    val targetKey: String,
    val targetLabel: String,
)

/**
 * A user-defined merge bucket (e.g. YouTube app + the Morphe client + youtube.com).
 * Every member's tracked time is combined into one cumulative total and a single
 * daily limit applies to that combined total.
 *
 * @param dailyLimitMinutes null or <= 0 means no limit (mirrors the server, which
 *   normalizes 0 away). Clamped to 1..1440 on write.
 * @param limitEnabled lets callers park a limit without deleting it.
 * @param updatedAt informational per-group stamp (the sync/watch clock lives in the
 *   separate [TargetGroupsRepository.Keys.TARGET_GROUPS_UPDATED_AT] key).
 */
@Serializable
data class TargetGroup(
    val groupId: String,
    val name: String,
    val category: String? = null,
    val members: List<TargetGroupMember>,
    val dailyLimitMinutes: Int? = null,
    val limitEnabled: Boolean = true,
    val updatedAt: Long = 0L,
)

/**
 * Outcome of [TargetGroupsRepository.upsertGroup], so the UI can tell a committed save
 * apart from a failed write and from normalization-induced drops.
 *
 * @param saved true when the group list was committed to DataStore. false means the
 *   write failed and the on-disk list is unchanged.
 * @param droppedMemberLabels labels of members this group wanted that [normalize]
 *   dropped because another group already claims them (first group wins).
 * @param groupDiscarded true when the group itself was not written because it ended up
 *   with fewer than two usable members (including "every member was claimed elsewhere").
 */
data class UpsertResult(
    val saved: Boolean,
    val droppedMemberLabels: List<String> = emptyList(),
    val groupDiscarded: Boolean = false,
)

/**
 * Persists user-defined target groups (merged apps + websites).
 *
 * Hot-path note: the accessibility service checks a foreground app's group limits on
 * every app entry, so the repository mirrors the decoded list into an in-memory
 * membership index (`"kind:key"` -> groups) fed by [groups]. Reads fall back to a
 * single DataStore read until the mirror is warm; after that no I/O happens on the
 * enforcement path.
 *
 * Read/write normalization mirrors the server's `groups:saveGroups` rules so local
 * enforcement never sees a group the backend would reject: member keys are lowercased
 * (website keys additionally drop a leading `www.`, matching the server's
 * `normalizeMemberKey` and the desktop's `normalizeSiteKey`), a target belongs to at
 * most one group (first group wins), groups with fewer than two members are dropped,
 * and the limit is clamped to 1..1440 minutes.
 */
class TargetGroupsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    object Keys {
        val GROUPS_JSON = stringPreferencesKey("target_groups_json")
        val TARGET_GROUPS_UPDATED_AT = longPreferencesKey("target_groups_updated_at")
    }

    private val _groups = MutableStateFlow<List<TargetGroup>>(emptyList())
    private val groupsLoaded = MutableStateFlow(false)

    /** Warm in-memory membership index: "targetKind:targetKey" -> groups containing it. */
    @Volatile
    private var membershipIndex: Map<String, List<TargetGroup>> = emptyMap()

    /** Decoded group list; also keeps the in-memory index warm. Corruption-safe. */
    val groups: Flow<List<TargetGroup>> = context.targetGroupsDataStore.data
        .map { prefs -> decodeGroups(prefs[Keys.GROUPS_JSON]) }
        .onEach { list -> publish(list) }
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w("TargetGroupsRepo", "target-groups DataStore read failed; using empty list", e)
            publish(emptyList())
            emit(emptyList())
        }

    /** Cached group list; falls back to DataStore only until the cache is warm. */
    suspend fun currentGroups(): List<TargetGroup> =
        if (groupsLoaded.value) _groups.value else groups.first()

    /**
     * Whole-list writer used when the remote clock wins the LWW round. The list is
     * normalized with the same rules as the server and written together with [updatedAt]
     * in one DataStore edit, then the in-memory mirror is refreshed.
     */
    suspend fun replaceAll(groups: List<TargetGroup>, updatedAt: Long) {
        val normalized = normalize(groups).map {
            if (it.updatedAt <= 0L) it.copy(updatedAt = updatedAt) else it
        }
        val committed = editGroups { prefs ->
            prefs[Keys.GROUPS_JSON] = json.encodeToString(normalized)
            prefs[Keys.TARGET_GROUPS_UPDATED_AT] = updatedAt
        }
        if (committed) publish(normalized)
    }

    /** LWW clock for the group list (independent of the blocked-apps/websites clocks). */
    suspend fun getUpdatedAt(): Long = try {
        context.targetGroupsDataStore.data.first()[Keys.TARGET_GROUPS_UPDATED_AT] ?: 0L
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("TargetGroupsRepo", "target-groups updated-at read failed", e)
        0L
    }

    /**
     * Adds or replaces one group and stamps [Keys.TARGET_GROUPS_UPDATED_AT] to now, so
     * the next sync cycle pushes the rewritten full list. Atomic read-modify-write inside
     * the DataStore edit (concurrent setters cannot drop each other's changes).
     *
     * Returns an [UpsertResult]: [UpsertResult.saved] false means the DataStore write
     * failed and nothing changed; otherwise [UpsertResult.droppedMemberLabels] /
     * [UpsertResult.groupDiscarded] report what [normalize] had to drop (members already
     * claimed by another group, or the whole group when fewer than two members survived).
     */
    suspend fun upsertGroup(group: TargetGroup): UpsertResult {
        val now = System.currentTimeMillis()
        val candidate = group.copy(updatedAt = if (group.updatedAt > 0L) group.updatedAt else now)
        val candidateId = candidate.groupId.trim()
        var written: List<TargetGroup>? = null
        var droppedLabels: List<String> = emptyList()
        var groupDiscarded = false
        val committed = editGroups { prefs ->
            val current = decodeGroups(prefs[Keys.GROUPS_JSON])
            val merged = current.filterNot { it.groupId == candidateId } + candidate
            // Keys every other group already owns. The candidate is appended last, so
            // normalize()'s first-group-wins always resolves in the others' favour.
            val claimedElsewhere = HashSet<String>()
            for (existing in merged) {
                if (existing.groupId == candidateId) continue
                for (member in existing.members) {
                    memberKey(member.targetKind, member.targetKey)?.let(claimedElsewhere::add)
                }
            }
            // First human label per normalized key, so a dropped member is named once.
            val labelsByKey = LinkedHashMap<String, String>()
            for (member in candidate.members) {
                val key = memberKey(member.targetKind, member.targetKey) ?: continue
                labelsByKey.putIfAbsent(key, member.targetLabel.trim().ifEmpty { member.targetKey.trim() })
            }
            val normalized = normalize(merged)
            val savedGroup = normalized.firstOrNull { it.groupId == candidateId }
            val savedKeys = savedGroup?.members
                ?.mapNotNull { memberKey(it.targetKind, it.targetKey) }
                ?.toSet()
                .orEmpty()
            droppedLabels = labelsByKey
                .filterKeys { it in claimedElsewhere && it !in savedKeys }
                .values
                .toList()
            groupDiscarded = savedGroup == null
            prefs[Keys.GROUPS_JSON] = json.encodeToString(normalized)
            prefs[Keys.TARGET_GROUPS_UPDATED_AT] = now
            written = normalized
        }
        if (!committed) return UpsertResult(saved = false)
        written?.let { publish(it) }
        return UpsertResult(
            saved = true,
            droppedMemberLabels = droppedLabels,
            groupDiscarded = groupDiscarded,
        )
    }

    /**
     * Removes one group by id and stamps the updated-at clock so the removal syncs.
     * Returns false when the DataStore write failed (the group is still there).
     */
    suspend fun removeGroup(groupId: String): Boolean {
        val now = System.currentTimeMillis()
        var written: List<TargetGroup>? = null
        val committed = editGroups { prefs ->
            val current = decodeGroups(prefs[Keys.GROUPS_JSON])
            val kept = current.filterNot { it.groupId == groupId }
            prefs[Keys.GROUPS_JSON] = json.encodeToString(kept)
            prefs[Keys.TARGET_GROUPS_UPDATED_AT] = now
            written = kept
        }
        if (committed) written?.let { publish(it) }
        return committed
    }

    /**
     * Groups containing the given target. Served from the in-memory index when warm;
     * otherwise one DataStore read warms the mirror first. Never throws (empty on error).
     */
    suspend fun groupsForTarget(targetKind: String, targetKey: String): List<TargetGroup> {
        val key = memberKey(targetKind, targetKey) ?: return emptyList()
        if (!groupsLoaded.value) {
            try {
                groups.first()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("TargetGroupsRepo", "groupsForTarget warm-up read failed", e)
                return emptyList()
            }
        }
        return membershipIndex[key] ?: emptyList()
    }

    /**
     * Non-suspending membership read for already-warm hot paths (accessibility service).
     * Empty until the mirror warms up — callers must treat that as "no group limit".
     */
    fun cachedGroupsForTarget(targetKind: String, targetKey: String): List<TargetGroup> {
        val key = memberKey(targetKind, targetKey) ?: return emptyList()
        return membershipIndex[key] ?: emptyList()
    }

    /** Convenience for the enforcement fast path: groups containing an app package. */
    fun cachedGroupsForPackage(packageName: String): List<TargetGroup> =
        cachedGroupsForTarget("app", packageName)

    private suspend fun editGroups(transform: (MutablePreferences) -> Unit): Boolean =
        try {
            context.targetGroupsDataStore.edit(transform)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("TargetGroupsRepo", "target-groups DataStore write failed; change skipped", e)
            false
        }

    private fun decodeGroups(raw: String?): List<TargetGroup> = try {
        if (raw.isNullOrBlank()) emptyList()
        else normalize(json.decodeFromString<List<TargetGroup>>(raw))
    } catch (e: Exception) {
        Log.w("TargetGroupsRepo", "target-groups JSON decode failed; using empty list", e)
        emptyList()
    }

    /** Refreshes the cached list + membership index after a committed write/emission. */
    private fun publish(list: List<TargetGroup>) {
        _groups.value = list
        groupsLoaded.value = true
        val index = HashMap<String, MutableList<TargetGroup>>(list.size * 4)
        for (group in list) {
            for (member in group.members) {
                val key = memberKey(member.targetKind, member.targetKey) ?: continue
                index.getOrPut(key) { mutableListOf() }.add(group)
            }
        }
        membershipIndex = index
    }

    /**
     * Lowercased, non-blank target key. Website keys additionally drop a leading
     * `www.`, the same rule the server (`normalizeMemberKey`) and the desktop
     * (`normalizeSiteKey`) apply, so a local `www.` row and a stored bare-domain
     * row resolve to the same identity everywhere.
     */
    private fun normalizedTargetKey(targetKind: String, targetKey: String): String? {
        val kind = targetKind.trim().lowercase()
        val key = targetKey.trim().lowercase()
        if (kind.isEmpty() || key.isEmpty()) return null
        val normalized = if (kind == "website") key.removePrefix("www.") else key
        return normalized.ifEmpty { null }
    }

    private fun memberKey(targetKind: String, targetKey: String): String? {
        val kind = targetKind.trim().lowercase()
        if (kind.isEmpty()) return null
        val key = normalizedTargetKey(kind, targetKey) ?: return null
        return "$kind:$key"
    }

    /**
     * Mirrors the server's `canonicalize` rules: valid target kinds only, lowercased
     * keys (websites without a leading `www.`), one group per target (first occurrence
     * wins), >= 2 distinct members, and the limit clamped to 1..1440 minutes
     * (0/negative/absent = no limit).
     */
    private fun normalize(groups: List<TargetGroup>): List<TargetGroup> {
        val emittedMembers = HashSet<String>(groups.size * 2)
        val emittedIds = HashSet<String>(groups.size)
        val out = ArrayList<TargetGroup>(groups.size)
        for (group in groups) {
            val groupId = group.groupId.trim()
            if (groupId.isEmpty() || !emittedIds.add(groupId)) continue
            val name = group.name.trim()
            if (name.isEmpty()) continue
            val members = ArrayList<TargetGroupMember>(group.members.size)
            for (member in group.members) {
                val kind = member.targetKind.trim().lowercase()
                if (kind != "app" && kind != "website") continue
                val key = normalizedTargetKey(kind, member.targetKey) ?: continue
                if (!emittedMembers.add("$kind:$key")) continue
                members += TargetGroupMember(
                    targetKind = kind,
                    targetKey = key,
                    targetLabel = member.targetLabel.trim().ifEmpty { key }.take(160),
                )
            }
            // Fewer than two members is not a merge; treat it as "ungrouped" (server rule).
            if (members.size < 2) continue
            out += group.copy(
                groupId = groupId,
                name = name.take(80),
                category = group.category?.trim()?.ifEmpty { null }?.take(80),
                members = members,
                dailyLimitMinutes = group.dailyLimitMinutes?.takeIf { it > 0 }?.coerceAtMost(1440),
            )
        }
        return out
    }
}
