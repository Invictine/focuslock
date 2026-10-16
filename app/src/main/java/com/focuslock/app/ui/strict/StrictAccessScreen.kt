package com.focuslock.app.ui.strict

import android.app.DatePickerDialog
import android.app.TimePickerDialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focuslock.app.BuildConfig
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.auth.AuthViewModel
import com.focuslock.app.auth.FocusAuthState
import com.focuslock.app.strict.StrictAccessConfig
import com.focuslock.app.sync.ConvexSyncClient
import com.focuslock.app.ui.components.ScreenHeader
import com.focuslock.app.ui.components.UiTokens
import com.focuslock.app.ui.components.AdaptiveContentFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.Calendar

private val accessMethods = listOf(
    "timer" to ("Timer" to "Keep settings locked until your chosen end time."),
    "pin" to ("PIN" to "Enter your saved PIN to end a commitment early."),
    "charger" to ("Charger" to "Connect this device to power before unlocking."),
    "cooldown" to ("Cooldown" to "Start a waiting period before you can unlock."),
    "approval" to ("Approval contact" to "A trusted person approves an early exit by email."),
)

@Composable
internal fun StrictAccessScreen(onOpenPermissions: () -> Unit = {}, permissionCheck: (suspend () -> Boolean)? = null) {
    val context = LocalContext.current
    val app = FocusLockApplication.instance
    val settings = app.settingsRepository
    val access = app.strictAccessRepository
    val auth: AuthViewModel = viewModel()
    val authState by auth.state.collectAsStateWithLifecycle()
    val accountId by auth.accountId.collectAsStateWithLifecycle()
    val signedIn = authState is FocusAuthState.SignedIn
    val config by access.configFlow.collectAsStateWithLifecycle(StrictAccessConfig())
    val timedActive by settings.lockdownModeFlow.collectAsStateWithLifecycle(false)
    val automatedActive by app.strictModeAutomationRepository.activationActiveFlow.collectAsStateWithLifecycle(false)
    val endsAt by settings.lockdownEndsAtFlow.collectAsStateWithLifecycle(0L)
    val active = timedActive || automatedActive
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(config) }
    // PINs stay in memory only, never in saved-instance state.
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var unlockPin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showReview by rememberSaveable { mutableStateOf(false) }
    var showUnlock by remember { mutableStateOf(false) }
    var hours by rememberSaveable { mutableStateOf(2) }
    var untilDate by rememberSaveable { mutableStateOf(false) }
    var year by rememberSaveable { mutableStateOf(Calendar.getInstance().get(Calendar.YEAR)) }
    var month by rememberSaveable { mutableStateOf(Calendar.getInstance().get(Calendar.MONTH)) }
    var day by rememberSaveable { mutableStateOf(Calendar.getInstance().get(Calendar.DAY_OF_MONTH)) }
    var hour by rememberSaveable { mutableStateOf(22) }
    var minute by rememberSaveable { mutableStateOf(0) }
    var availableAt by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var protectionsExpanded by rememberSaveable { mutableStateOf(true) }
    var locationExpanded by rememberSaveable { mutableStateOf(false) }
    var unlockMethod by remember { mutableStateOf("pin") }
    LaunchedEffect(config) { draft = config }
    LaunchedEffect(accountId) { pin = ""; confirmPin = ""; unlockPin = ""; notice = null }
    LaunchedEffect(active, endsAt, availableAt) {
        while (active) {
            now = System.currentTimeMillis()
            settings.expireLockdownIfNeeded(now)
            delay(if (availableAt > now) 1_000L else 30_000L)
        }
    }

    suspend fun client(): ConvexSyncClient {
        val token = auth.getConvexToken() ?: error("Connect to the internet and sign in to continue.")
        return ConvexSyncClient(BuildConfig.CONVEX_URL, tokenProvider = { token })
    }
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true; notice = null
        val requestedAccount = accountId
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = e.message ?: "Could not save. Try again." }
            finally {
                if (requestedAccount != auth.getAccountId()) notice = "Account changed. Reload before continuing."
                busy = false
            }
        }
    }
    suspend fun saveOptions() {
        val needsService = draft.preventUninstall || draft.blockDeviceSettings || draft.blockRecents || draft.blockSplitScreen
        check(!needsService || (permissionCheck?.invoke() ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.focuslock.app.ui.permissions.PermissionHelper.isAccessibilityServiceEnabled(context)
        })) { "Enable Accessibility in Permissions & protection before using device protections." }
        if ("pin" in draft.selectedMethods && (pin.isNotBlank() || !draft.hasPin)) {
            check(pin.matches(Regex("[0-9]{4,12}")) && pin == confirmPin) { "Enter and confirm the same PIN (4 to 12 digits)." }
        }
        val owner = accountId
        if (signedIn) {
            val payload = draft.toJson()
            if (pin.isNotBlank()) payload.put("pin", pin)
            val response = client().configureStrictAccess(payload) ?: error("Could not save your access method. Connect and try again.")
            check(owner != null && owner == auth.getAccountId()) { "Account changed. Try again." }
            access.save(StrictAccessConfig.fromJson(response), pin.takeIf { it.isNotBlank() }, owner)
        } else {
            check("approval" !in draft.selectedMethods) { "Sign in to set up an approval contact." }
            access.save(draft, pin.takeIf { it.isNotBlank() })
        }
        pin = ""; confirmPin = ""
    }
    suspend fun requestUnlock(method: String) {
        val expectedAccount = auth.getAccountId()
        val expectedEnd = settings.lockdownEndsAtFlow.firstValue()
        if (signedIn) {
            val owner = access.ownerAccountId()
            check(owner == null || owner == accountId) { "Sign back into the account that started this commitment." }
            app.syncManager.syncNow(auth)
            val connection = client()
            val state = connection.getStrictApprovalState() ?: error("Could not load your active commitment.")
            val session = state.optString("strictSessionId")
            check(session.isNotBlank() && state.optLong("strictEndsAt") == expectedEnd) { "Sync this commitment and try again." }
            val response = connection.unlockStrictAccess(session, expectedEnd, unlockPin.takeIf { it.isNotBlank() }, access.isCharging(), method)
                ?: error("Could not check your access method. Connect and try again.")
            check(expectedAccount == auth.getAccountId()) { "Account changed. Try again." }
            availableAt = response.optLong("availableAt", 0)
            if (response.optBoolean("released")) {
                check(settings.applyRemoteApprovedUnlock(expectedEnd, response.optLong("strictApprovedAt"))) { "The commitment changed. Sync to refresh it." }
                app.syncManager.syncNow(auth)
                notice = "Strict Mode ended. Your boundaries and permanent blocks stay in place."
            } else notice = response.optString("reason", "Your commitment is still active.")
        } else {
            availableAt = access.requestOfflineUnlock(expectedEnd, unlockPin, method)
            if (availableAt <= System.currentTimeMillis()) {
                check(settings.applyRemoteApprovedUnlock(expectedEnd, System.currentTimeMillis())) { "The commitment changed. Try again." }
                notice = "Strict Mode ended. Your boundaries and permanent blocks stay in place."
            } else notice = "Cooldown started. Return here when the waiting period ends."
        }
        unlockPin = ""; showUnlock = false
    }

    AdaptiveContentFrame(modifier = Modifier.fillMaxSize(), maxContentWidth = 840.dp) {
      Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .verticalScroll(rememberScrollState()).padding(horizontal = UiTokens.ScreenPadding).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenHeader("Strict Mode", "Your focus. Your rules. A commitment that holds.")
        Surface(color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (active) "COMMITMENT ACTIVE" else "BUILD YOUR COMMITMENT", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(if (active) "Your boundaries are protected" else "Make distraction harder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (timedActive) "Ends in ${formatLockdownRemaining((endsAt - now).coerceAtLeast(0))}"
                    else if (automatedActive) "A schedule or location commitment is active."
                    else "Combine unlock options, add protections, and choose how long to commit.", style = MaterialTheme.typography.bodyMedium)
                if (timedActive) {
                    Text("Unlock options: ${methodSummary(config)}", style = MaterialTheme.typography.labelLarge)
                    config.selectedMethods.filter { it in listOf("pin", "charger", "cooldown") }.forEach { method ->
                        OutlinedButton(onClick = {
                            unlockMethod = method
                            if (method == "pin") showUnlock = true else perform { requestUnlock(method) }
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(if (method == "cooldown" && availableAt > now) "Cooldown | ${((availableAt - now) / 1000).coerceAtLeast(1)}s remaining"
                                else if (method == "cooldown") "${if (availableAt > 0) "Finish" else "Start"} cooldown"
                                else "Unlock with ${method.replaceFirstChar { it.uppercase() }}")
                        }
                    }
                    Text("The timer also ends this commitment automatically.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { perform { settings.extendLockdown(maxOf(endsAt, System.currentTimeMillis()) + 3_600_000L); app.syncManager.syncNow(auth) } }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Extend by one hour") }
                } else if (!active) {
                    Text("Unlock options: ${methodSummary(draft)}", style = MaterialTheme.typography.labelLarge)
                    Button(onClick = { showReview = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Review and start") }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("01  |  Unlock options", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Mix and match. Any selected option can end a timed commitment. The timer always expires automatically.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                accessMethods.forEachIndexed { index, (key, copy) ->
                    val selected = key in draft.selectedMethods
                    val enabled = !active && !busy && key != "timer"
                    Row(Modifier.fillMaxWidth().heightIn(min = 80.dp)
                        .toggleable(value = selected || key == "timer", enabled = enabled, role = Role.Checkbox, onValueChange = { checked ->
                            val methods = draft.selectedMethods.filter { it != "schedules" }.toMutableSet().apply {
                                add("timer")
                                if (checked) add(key) else remove(key)
                            }.toList()
                            draft = draft.copy(method = "timer", methods = methods)
                        }).padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(copy.first, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (key == "timer") "Always included | ends at your chosen time" else copy.second,
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Checkbox(checked = selected || key == "timer", onCheckedChange = null, enabled = enabled)
                    }
                    if (index < accessMethods.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        if (active) Text("Unlock options stay fixed during a commitment. You can still add protections below.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!active && "pin" in draft.selectedMethods) {
            Text(if (draft.hasPin) "Leave these empty to keep your saved PIN." else "Ask someone you trust to choose a PIN if you want extra accountability.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(confirmPin, { confirmPin = it.filter(Char::isDigit).take(12) }, label = { Text("Confirm PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        }
        if ("cooldown" in draft.selectedMethods) {
            Text("Wait ${draft.cooldownMinutes} minutes before unlocking", style = MaterialTheme.typography.titleSmall)
            Slider(draft.cooldownMinutes.toFloat(), { draft = draft.copy(cooldownMinutes = it.toInt()) }, valueRange = 2f..10f, steps = 7, enabled = !active && !busy)
        }
        if ("approval" in draft.selectedMethods) ApprovalUnlockCard(settings)
        HorizontalDivider()
        TextButton(onClick = { protectionsExpanded = !protectionsExpanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (protectionsExpanded) "02  |  Protections" else "Choose protections")
        }
        if (protectionsExpanded) {
            Text("Turn on extra safeguards at any time. Active protections can only become stricter.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProtectionOption("Lock boundary settings", "Always on. You can add stronger blocks; existing rules cannot be weakened.", true, false) {}
            ProtectionOption("Prevent uninstalling FocusLock", "Stops detected uninstall and device-admin removal prompts while Strict Mode is active.", draft.preventUninstall, !busy && (!active || !config.preventUninstall)) { draft = draft.copy(preventUninstall = it) }
            ProtectionOption("Block device settings", "Restricts Android Settings. Calls, emergency tools, keyboards and required system prompts remain available.", draft.blockDeviceSettings, !busy && (!active || !config.blockDeviceSettings)) { draft = draft.copy(blockDeviceSettings = it) }
            ProtectionOption("Block recent apps", "Closes detected overview screens to reduce floating-window bypasses.", draft.blockRecents, !busy && (!active || !config.blockRecents)) { draft = draft.copy(blockRecents = it) }
            ProtectionOption("Block split screen", "Returns to full screen when two apps share the display.", draft.blockSplitScreen, !busy && (!active || !config.blockSplitScreen)) { draft = draft.copy(blockSplitScreen = it) }
            ProtectionOption("Keep reinstalled apps blocked", "Saved app identities stay blocked after reinstalling. Reinstalling does not reset your boundaries.", true, false) {}
        }
        run {
            OutlinedButton(onClick = { perform { saveOptions(); notice = "Access method and protections saved." } }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (busy) "Saving…" else if (active) "Save stronger protections" else "Save setup") }
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        if (draft.preventUninstall || draft.blockDeviceSettings || draft.blockRecents || draft.blockSplitScreen) {
            TextButton(onClick = onOpenPermissions, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Review protection permissions") }
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("03  |  Location", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Automatically protect settings at a saved place. Location commitments run alongside your timer; leaving a place does not cancel a timed commitment.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { locationExpanded = !locationExpanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (locationExpanded) "Hide location options" else "Set up location")
        }
        if (locationExpanded) StrictLocationAutomationEditor()

      }
    }
    if (showReview) AlertDialog(onDismissRequest = { if (!busy) showReview = false }, title = { Text("Review your commitment") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StrictEnableOptions(hours, { hours = it }, untilDate, { untilDate = it }, hour, minute,
                onPickTime = { TimePickerDialog(context, { _, h, m -> hour = h; minute = m }, hour, minute,
                    android.text.format.DateFormat.is24HourFormat(context)).show() },
                year, month, day,
                onPickDate = {
                    val picker = DatePickerDialog(context, { _, y, m, d -> year = y; month = m; day = d }, year, month, day)
                    picker.datePicker.minDate = System.currentTimeMillis()
                    picker.datePicker.maxDate = System.currentTimeMillis() + 30 * 86_400_000L
                    picker.show()
                })
            Text("Unlock options: ${methodSummary(draft)}. Any selected option can end this timed commitment; the timer also ends it automatically.")
            Text("You can add blocks. Existing limits, schedules and blocked targets stay protected. Permanent blocks remain permanent.")
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = { perform {
        saveOptions()
        val end = if (untilDate) Calendar.getInstance().apply { set(year, month, day, hour, minute, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            else System.currentTimeMillis() + hours * 3_600_000L
        check(end > System.currentTimeMillis() && end <= System.currentTimeMillis() + 30 * 86_400_000L) { "Choose an end time within the next 30 days." }
        settings.setLockdownMode(true, end)
        check(settings.isLockdownModeEnabled()) { "Could not start Strict Mode." }
        showReview = false
        if (signedIn) app.syncManager.syncNow(auth)
        notice = "Commitment started."
    } }) { Text(if (busy) "Starting…" else "Start Strict Mode") } }, dismissButton = { TextButton(enabled = !busy, onClick = { showReview = false }) { Text("Back") } })
    if (showUnlock) AlertDialog(onDismissRequest = { showUnlock = false; unlockPin = "" }, title = { Text("Enter your PIN") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(unlockPin, { unlockPin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }, confirmButton = { TextButton(enabled = !busy && unlockPin.length >= 4, onClick = { perform { requestUnlock(unlockMethod) } }) { Text("Unlock") } }, dismissButton = { TextButton(onClick = { showUnlock = false; unlockPin = "" }) { Text("Cancel") } })
}

private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstValue(): T = first()

@Composable
private fun ProtectionOption(title: String, summary: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun methodSummary(config: StrictAccessConfig): String =
    (listOf("timer") + config.selectedMethods).distinct().mapNotNull { key ->
        accessMethods.firstOrNull { it.first == key }?.second?.first
    }.joinToString(" + ")
