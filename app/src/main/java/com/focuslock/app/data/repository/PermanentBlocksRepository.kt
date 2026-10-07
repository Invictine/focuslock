package com.focuslock.app.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.focuslock.app.data.model.BlockedApp
import com.focuslock.app.data.model.BlockedWebsite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.IDN
import java.net.URI
import java.util.Collections

private val Context.permanentBlocksDataStore by preferencesDataStore(name = "focuslock_permanent_app_blocks")

/** Device-local append-only store for permanent app and website commitments. */
class PermanentBlocksRepository(private val context: Context) {
    private object Keys {
        val PACKAGES = stringSetPreferencesKey("packages")
        val DOMAINS = stringSetPreferencesKey("domains")
        val APP_NAMES = stringPreferencesKey("app_names_json")
        val OWNERS = stringPreferencesKey("owners_json")
        val ACTIVE_ACCOUNT = stringPreferencesKey("active_account")
    }

    private val packageMirror = Collections.synchronizedSet(mutableSetOf<String>())
    private val domainMirror = Collections.synchronizedSet(mutableSetOf<String>())
    private val namesMirror = Collections.synchronizedMap(mutableMapOf<String, String>())
    private val warmMutex = Mutex()
    private val mirrorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firstSnapshot = CompletableDeferred<Unit>()
    @Volatile private var mirrorJob: Job? = null

    /** Every projection updates all mirrors together, so collecting one flow cannot leave another cold. */
    private val snapshotFlow: Flow<Preferences> = context.permanentBlocksDataStore.data
        .map { prefs -> updateMirrors(prefs); prefs }
        .catch { emit(fallbackSnapshot()) }

    val packagesFlow: Flow<Set<String>> = snapshotFlow.map {
        synchronized(packageMirror) { packageMirror.toSet() }
    }

    /** Permanent sites are independently persisted so ordinary boundary and cloud writes cannot erase them. */
    val domainsFlow: Flow<Set<String>> = snapshotFlow.map {
        synchronized(domainMirror) { domainMirror.toSet() }
    }

    /** Saved labels remain available even after the target package is uninstalled. */
    val appNamesFlow: Flow<Map<String, String>> = snapshotFlow.map {
        synchronized(namesMirror) { namesMirror.toMap() }
    }

    /** Starts DataStore collection and warms synchronous enforcement mirrors. */
    suspend fun warm() {
        warmMutex.withLock {
            if (mirrorJob == null) {
                mirrorJob = mirrorScope.launch {
                    snapshotFlow.collect {
                        firstSnapshot.complete(Unit)
                    }
                }
            }
        }
        firstSnapshot.await()
    }

    fun isPermanentlyBlocked(packageName: String): Boolean =
        synchronized(packageMirror) { normalizePackage(packageName) in packageMirror }

    fun isPermanentlyBlockedDomain(urlOrDomain: String): Boolean {
        val host = PermanentWebsitePolicy.normalize(urlOrDomain) ?: return false
        return synchronized(domainMirror) { domainMirror.any { PermanentWebsitePolicy.matches(host, it) } }
    }

    /** Idempotent. Returns false when invalid or protected. [appName] is retained across uninstall. */
    suspend fun add(packageName: String, appName: String? = null): Boolean {
        val normalized = normalizePackage(packageName)
        if (normalized.isEmpty() || isProtectedPackage(context, normalized)) return false
        val label = appName?.trim()?.takeIf(String::isNotEmpty)
        context.permanentBlocksDataStore.edit { prefs ->
            prefs[Keys.PACKAGES] = prefs[Keys.PACKAGES].orEmpty() + normalized
            // Legacy Settings entries sometimes only know the package identifier. That
            // fallback must never replace a previously saved human-readable app label.
            if (label != null && label != normalized) {
                val names = parseNames(prefs[Keys.APP_NAMES]).toMutableMap()
                names[normalized] = label
                prefs[Keys.APP_NAMES] = namesToJson(names)
                synchronized(namesMirror) { namesMirror[normalized] = label }
            }
            synchronized(packageMirror) { packageMirror.add(normalized) }
            val owners = parseOwners(prefs[Keys.OWNERS]).toMutableMap()
            val account = prefs[Keys.ACTIVE_ACCOUNT].orEmpty()
            if (account.isNotBlank()) owners.putIfAbsent("android:$normalized", account)
            prefs[Keys.OWNERS] = ownersToJson(owners)
        }
        return true
    }

    /** Appends a validated website commitment. Existing domains cannot be removed in-app. */
    suspend fun addWebsite(raw: String): Boolean {
        val domain = PermanentWebsitePolicy.normalize(raw) ?: return false
        context.permanentBlocksDataStore.edit { prefs ->
            prefs[Keys.DOMAINS] = prefs[Keys.DOMAINS].orEmpty() + domain
            synchronized(domainMirror) { domainMirror.add(domain) }
            val owners = parseOwners(prefs[Keys.OWNERS]).toMutableMap()
            val account = prefs[Keys.ACTIVE_ACCOUNT].orEmpty()
            if (account.isNotBlank()) owners.putIfAbsent("website:$domain", account)
            prefs[Keys.OWNERS] = ownersToJson(owners)
        }
        return true
    }

    /** Restores append-only commitments from the account snapshot; never removes local entries. */
    suspend fun bindAccount(accountId: String) {
        val account = accountId.trim(); if (account.isEmpty()) return
        context.permanentBlocksDataStore.edit { prefs ->
            val owners = parseOwners(prefs[Keys.OWNERS]).toMutableMap()
            val active = prefs[Keys.ACTIVE_ACCOUNT].orEmpty()
            if (active.isBlank()) {
                val packages = prefs[Keys.PACKAGES].orEmpty().map(::normalizePackage)
                val domains = prefs[Keys.DOMAINS].orEmpty().mapNotNull(PermanentWebsitePolicy::normalize)
                packages.forEach { owners.putIfAbsent("android:$it", account) }
                domains.forEach { owners.putIfAbsent("website:$it", account) }
            }
            prefs[Keys.OWNERS] = ownersToJson(owners); prefs[Keys.ACTIVE_ACCOUNT] = account
        }
    }

    suspend fun mergeRemote(targets: List<com.focuslock.app.sync.RemotePermanentBlock>, accountId: String) {
        bindAccount(accountId)
        targets.forEach { target ->
            when (target.targetKind.trim().lowercase()) {
                "android" -> add(target.targetKey, target.targetLabel)
                "website" -> addWebsite(target.targetKey)
            }
        }
    }

    suspend fun remoteTargets(accountId: String): List<com.focuslock.app.sync.RemotePermanentBlock> {
        warm()
        val owners = parseOwners(readOwners())
        val apps = synchronized(packageMirror) { packageMirror.toList() }.filter { owners["android:$it"] == accountId }.map {
            com.focuslock.app.sync.RemotePermanentBlock("android", it, synchronized(namesMirror) { namesMirror[it] })
        }
        val sites = synchronized(domainMirror) { domainMirror.toList() }.filter { owners["website:$it"] == accountId }.map {
            com.focuslock.app.sync.RemotePermanentBlock("website", it, null)
        }
        return apps + sites
    }

    private suspend fun readOwners(): String = context.permanentBlocksDataStore.data.map { it[Keys.OWNERS].orEmpty() }.first()
    private fun parseOwners(raw: String?): Map<String, String> = try {
        val json = JSONObject(raw ?: "{}"); buildMap { val keys = json.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, json.optString(key)) } }
    } catch (_: Exception) { emptyMap() }
    private fun ownersToJson(owners: Map<String, String>) = JSONObject(owners).toString()

    /** Migrates legacy flags once and on every warm; the independent sets make this idempotent. */
    suspend fun migrateLegacy(apps: List<BlockedApp>, websites: List<BlockedWebsite>) {
        apps.filter { it.isPermanent }.forEach { add(it.packageName, it.appName) }
        websites.filter { it.isPermanent }.forEach { addWebsite(it.domain) }
    }

    /** Permanent blocks intentionally cannot be removed by the app UI. */
    fun canRemoveInApp(): Boolean = false

    private fun normalizePackage(value: String): String = value.trim().lowercase()

    private fun updateMirrors(prefs: Preferences) {
        val packages = prefs[Keys.PACKAGES].orEmpty().map(::normalizePackage).filter(String::isNotEmpty).toSet()
        val domains = prefs[Keys.DOMAINS].orEmpty().mapNotNull(PermanentWebsitePolicy::normalize).toSet()
        val names = parseNames(prefs[Keys.APP_NAMES])
        // Independent collectors can process an older snapshot just after an add.
        // Permanent commitments are monotonic; an older read must never clear one.
        synchronized(packageMirror) { packageMirror.addAll(packages) }
        synchronized(domainMirror) { domainMirror.addAll(domains) }
        synchronized(namesMirror) { namesMirror.putAll(names) }
        firstSnapshot.complete(Unit)
    }

    private fun fallbackSnapshot(): Preferences = mutablePreferencesOf(
        Keys.PACKAGES to synchronized(packageMirror) { packageMirror.toSet() },
        Keys.DOMAINS to synchronized(domainMirror) { domainMirror.toSet() },
        Keys.APP_NAMES to namesToJson(synchronized(namesMirror) { namesMirror.toMap() }),
    )

    private fun parseNames(raw: String?): Map<String, String> = try {
        val json = JSONObject(raw ?: "{}")
        buildMap {
            val keys = json.keys()
            while (keys.hasNext()) {
                val rawKey = keys.next()
                val key = normalizePackage(rawKey)
                val value = json.optString(rawKey).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) put(key, value)
            }
        }
    } catch (_: Exception) { emptyMap() }

    private fun namesToJson(names: Map<String, String>): String = JSONObject(names).toString()

    companion object {
        /** Packages which must never be permanently blocked because they are recovery paths. */
        fun isProtectedPackage(context: Context, packageName: String): Boolean {
            if (com.focuslock.app.service.AppUpdateAccessPolicy.isUpdateApp(packageName)) return true
            val own = context.packageName
            if (packageName == own || packageName == "android" || packageName == "com.android.systemui" ||
                packageName == "com.android.settings" || packageName == "com.android.dialer" ||
                packageName == "com.google.android.dialer" || packageName == "com.samsung.android.dialer") return true
            val pm = context.packageManager
            val launcher = pm.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
            if (packageName == launcher) return true
            val defaultDialer = try {
                (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
            } catch (_: SecurityException) { null }
            if (packageName == defaultDialer) return true
            val enabledIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
            return packageName == enabledIme || packageName == "com.android.packageinstaller" ||
                packageName == "com.google.android.packageinstaller"
        }
    }
}

/** Validation and matching shared by the permanent-site UI and enforcement. */
object PermanentWebsitePolicy {
    fun normalize(raw: String): String? {
        val input = raw.trim()
        if (input.isEmpty() || input.any(Char::isWhitespace)) return null
        val candidate = try {
            val uri = URI(if (input.contains("://")) input else "https://$input")
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.rawUserInfo != null) return null
            uri.host ?: return null
        } catch (_: Exception) { return null }
        val ascii = try { IDN.toASCII(candidate.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase() }
        catch (_: Exception) { return null }
        // Keep normalization aligned with the existing website boundary helper.
        val cleaned = SettingsRepository.cleanDomain(ascii)
        if (cleaned.length !in 1..253 || cleaned.startsWith('.') || cleaned.endsWith('.')) return null
        val labels = cleaned.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-') }) return null
        if (labels.any { label -> label.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-') } }) return null
        return cleaned
    }

    fun matches(host: String, domain: String): Boolean =
        host.equals(domain, ignoreCase = true) || host.endsWith(".$domain", ignoreCase = true)
}

/** Pure enforcement rule used by the service and JVM tests. */
internal object PermanentBlockPolicy {
    fun shouldEnforce(permanent: Boolean, protected: Boolean): Boolean = permanent && !protected

    fun shouldEnforce(packageName: String?, permanentPackages: Set<String>, protectedPackages: Set<String>): Boolean {
        val normalized = packageName?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return false
        return shouldEnforce(
            permanent = normalized in permanentPackages.map { it.trim().lowercase() }.toSet(),
            protected = normalized in protectedPackages.map { it.trim().lowercase() }.toSet(),
        )
    }
}
