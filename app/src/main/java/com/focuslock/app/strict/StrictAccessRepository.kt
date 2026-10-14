package com.focuslock.app.strict

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Context.strictAccessStore by preferencesDataStore("focuslock_strict_access")

@Serializable
data class StrictAccessConfig(
    val method: String = "timer",
    val cooldownMinutes: Int = 5,
    val preventUninstall: Boolean = false,
    val blockDeviceSettings: Boolean = false,
    val blockRecents: Boolean = false,
    val blockSplitScreen: Boolean = false,
    val blockReinstalledApps: Boolean = true,
    val hasPin: Boolean = false,
    val revision: Long = 0,
) {
    companion object {
        val methods = listOf("timer", "schedules", "pin", "charger", "cooldown", "approval")
        fun fromJson(raw: JSONObject) = StrictAccessConfig(
            method = raw.optString("method", "timer"), cooldownMinutes = raw.optInt("cooldownMinutes", 5),
            preventUninstall = raw.optBoolean("preventUninstall"), blockDeviceSettings = raw.optBoolean("blockDeviceSettings"),
            blockRecents = raw.optBoolean("blockRecents"), blockSplitScreen = raw.optBoolean("blockSplitScreen"),
            blockReinstalledApps = raw.optBoolean("blockReinstalledApps", true), hasPin = raw.optBoolean("hasPin"), revision = raw.optLong("revision"),
        )
    }
    fun toJson() = JSONObject().put("method", method).put("cooldownMinutes", cooldownMinutes)
        .put("preventUninstall", preventUninstall).put("blockDeviceSettings", blockDeviceSettings)
        .put("blockRecents", blockRecents).put("blockSplitScreen", blockSplitScreen).put("blockReinstalledApps", blockReinstalledApps)
        .put("expectedRevision", revision)
}

@Serializable
private data class SavedAccess(
    val config: StrictAccessConfig = StrictAccessConfig(),
    val accountId: String? = null,
    val pinSalt: String? = null,
    val pinHash: String? = null,
    val cooldownEndsAt: Long = 0,
    val commitmentEndsAt: Long = 0,
    val failures: Int = 0,
    val blockedUntil: Long = 0,
)

/** Durable local options; an account commitment retains its owner through sign-out. */
class StrictAccessRepository(
    context: Context,
    preferencesStore: DataStore<Preferences>? = null,
    private val active: suspend () -> Boolean = { false },
) {
    private val appContext = context.applicationContext
    private val store = preferencesStore ?: appContext.strictAccessStore
    private val key = stringPreferencesKey("state")
    private val json = Json { ignoreUnknownKeys = true }
    private fun decode(raw: String?) = runCatching { raw?.let { json.decodeFromString<SavedAccess>(it) } }.getOrNull() ?: SavedAccess()
    val configFlow: Flow<StrictAccessConfig> = store.data.map { decode(it[key]).config }
    suspend fun getConfig(): StrictAccessConfig = configFlow.first()
    suspend fun ownerAccountId(): String? = decode(store.data.first()[key]).accountId

    suspend fun save(config: StrictAccessConfig, pin: String? = null, accountId: String? = null) {
        check(!active()) { "Access methods and protections are fixed until Strict Mode ends." }
        require(config.method in StrictAccessConfig.methods && config.cooldownMinutes in 2..10)
        val salt = pin?.let { require(it.matches(Regex("[0-9]{4,12}"))) { "Use a PIN with 4 to 12 digits." }; ByteArray(24).also(SecureRandom()::nextBytes).joinToString("") { b -> "%02x".format(b) } }
        val hash = if (pin != null && salt != null) withContext(Dispatchers.Default) { digest(pin, salt) } else null
        store.edit { prefs ->
            check(!active()) { "Access methods and protections are fixed until Strict Mode ends." }
            val current = decode(prefs[key])
            val sameOwner = current.accountId == accountId
            val next = SavedAccess(config.copy(hasPin = config.hasPin || hash != null || (sameOwner && current.config.hasPin)), accountId,
                salt ?: current.pinSalt.takeIf { sameOwner }, hash ?: current.pinHash.takeIf { sameOwner })
            require(next.config.method != "pin" || next.config.hasPin) { "Set a PIN first." }
            prefs[key] = json.encodeToString(next)
        }
    }

    suspend fun applyRemote(config: StrictAccessConfig, accountId: String) {
        val current = decode(store.data.first()[key])
        // Account switching must not replace the method protecting a retained commitment.
        if (active() && (current.accountId != accountId || config != current.config)) return
        store.edit { prefs ->
            val previous = decode(prefs[key])
            if (active() && (previous.accountId != accountId || config != previous.config)) return@edit
            if (previous.accountId == accountId && config.revision < previous.config.revision) return@edit
            prefs[key] = json.encodeToString(SavedAccess(config, accountId,
                previous.pinSalt.takeIf { previous.accountId == accountId }, previous.pinHash.takeIf { previous.accountId == accountId },
                previous.cooldownEndsAt, previous.commitmentEndsAt, previous.failures, previous.blockedUntil))
        }
    }

    /** Offline-only installations can use device checks; synced accounts use server release markers. */
    suspend fun requestOfflineUnlock(endsAt: Long, pin: String = ""): Long {
        check(ownerAccountId() == null) { "Connect to sync this account's unlock across devices." }
        var availableAt = Long.MAX_VALUE
        val now = System.currentTimeMillis()
        store.edit { prefs ->
            val state = decode(prefs[key])
            val config = state.config
            when (config.method) {
                "charger" -> { check(isCharging()) { "Connect this device to a charger first." }; availableAt = now }
                "pin" -> {
                    check(now >= state.blockedUntil) { "Too many PIN attempts. Wait five minutes." }
                    val matches = state.pinSalt != null && state.pinHash != null && MessageDigest.isEqual(digest(pin, state.pinSalt).toByteArray(), state.pinHash.toByteArray())
                    if (matches) { availableAt = now; prefs[key] = json.encodeToString(state.copy(failures = 0, blockedUntil = 0)) }
                    else {
                        val failures = state.failures + 1
                        prefs[key] = json.encodeToString(state.copy(failures = if (failures >= 5) 0 else failures, blockedUntil = if (failures >= 5) now + 300_000 else 0))
                    }
                }
                "cooldown" -> {
                    availableAt = if (state.commitmentEndsAt == endsAt && state.cooldownEndsAt > 0) state.cooldownEndsAt else now + config.cooldownMinutes * 60_000L
                    prefs[key] = json.encodeToString(state.copy(commitmentEndsAt = endsAt, cooldownEndsAt = availableAt))
                }
                else -> error("Wait until the timer or schedule ends, or use your approval contact.")
            }
        }
        check(availableAt != Long.MAX_VALUE) { "Incorrect PIN." }
        return availableAt
    }

    fun isCharging(): Boolean {
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }
    private fun digest(pin: String, salt: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt.toByteArray(), 100_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.joinToString("") { "%02x".format(it) } }
        finally { spec.clearPassword() }
    }
}
