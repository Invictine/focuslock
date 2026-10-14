package com.focuslock.app.ui.strict

import android.app.DatePickerDialog
import android.app.TimePickerDialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import java.util.Calendar

private val accessMethods = listOf(
    "timer" to ("Timer" to "Keep settings locked until your chosen end time."),
    "schedules" to ("Follow schedules" to "Lock settings during your weekly focus windows."),
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
    var protectionsExpanded by rememberSaveable { mutableStateOf(false) }
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
        check(!active) { "Wait until your current commitment ends before changing access methods." }
        val needsService = draft.preventUninstall || draft.blockDeviceSettings || draft.blockRecents || draft.blockSplitScreen
        check(!needsService || (permissionCheck?.invoke() ?: kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.focuslock.app.ui.permissions.PermissionHelper.isAccessibilityServiceEnabled(context)
        })) { "Enable Accessibility in Permissions & protection before using device protections." }
        if (draft.method == "pin" && (pin.isNotBlank() || !draft.hasPin)) {
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
            check(draft.method != "approval") { "Sign in to set up an approval contact." }
            access.save(draft, pin.takeIf { it.isNotBlank() })
        }
        pin = ""; confirmPin = ""
    }
    suspend fun requestUnlock() {
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
            val response = connection.unlockStrictAccess(session, expectedEnd, unlockPin.takeIf { it.isNotBlank() }, access.isCharging())
                ?: error("Could not check your access method. Connect and try again.")
            check(expectedAccount == auth.getAccountId()) { "Account changed. Try again." }
            availableAt = response.optLong("availableAt", 0)
            if (response.optBoolean("released")) {
                check(settings.applyRemoteApprovedUnlock(expectedEnd, response.optLong("strictApprovedAt"))) { "The commitment changed. Sync to refresh it." }
                app.syncManager.syncNow(auth)
                notice = "Strict Mode ended. Your boundaries and permanent blocks stay in place."
            } else notice = response.optString("reason", "Your commitment is still active.")
        } else {
            availableAt = access.requestOfflineUnlock(expectedEnd, unlockPin)
            if (availableAt <= System.currentTimeMillis()) {
                check(settings.applyRemoteApprovedUnlock(expectedEnd, System.currentTimeMillis())) { "The commitment changed. Try again." }
                notice = "Strict Mode ended. Your boundaries and permanent blocks stay in place."
            } else notice = "Cooldown started. Return here when the waiting period ends."
        }
        unlockPin = ""; showUnlock = false
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .verticalScroll(rememberScrollState()).padding(horizontal = UiTokens.ScreenPadding).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenHeader("Strict Mode", "Make a commitment to the boundaries you already chose.")
        Surface(color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (active) "Your boundaries are protected" else "Set it up before you commit", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(if (timedActive) "Ends in ${formatLockdownRemaining((endsAt - now).coerceAtLeast(0))}"
                    else if (automatedActive) "A schedule or location commitment is active."
                    else "Choose an access method, review the protections, then start when you're ready.", style = MaterialTheme.typography.bodyMedium)
                if (timedActive) {
                    Text("Access: ${accessMethods.firstOrNull { it.first == config.method }?.second?.first ?: "Timer"}", style = MaterialTheme.typography.labelLarge)
                    if (config.method in listOf("pin", "charger", "cooldown")) {
                        Button(onClick = { if (config.method == "pin") showUnlock = true else perform { requestUnlock() } }, enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(if (availableAt > now) "Wait ${((availableAt - now) / 1000).coerceAtLeast(1)}s"
                                else if (config.method == "cooldown") "${if (availableAt > 0) "Finish" else "Start"} cooldown" else "Unlock with ${config.method}")
                        }
                    } else if (config.method != "approval") Text("Settings unlock when this commitment ends.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { perform { settings.extendLockdown(maxOf(endsAt, System.currentTimeMillis()) + 3_600_000L); app.syncManager.syncNow(auth) } }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Extend by one hour") }
                } else if (!active && draft.method != "schedules") {
                    Text("Access: ${accessMethods.first { it.first == draft.method }.second.first}", style = MaterialTheme.typography.labelLarge)
                    Button(onClick = { showReview = true }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Review and start") }
                }
            }
        }
        Text("Access method", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            accessMethods.forEach { (key, copy) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected = draft.method == key, enabled = !active && !busy,
                    role = Role.RadioButton, onClick = { draft = draft.copy(method = key) }).padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = draft.method == key, onClick = null, enabled = !active && !busy)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(copy.first, style = MaterialTheme.typography.titleSmall)
                        Text(copy.second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (!active && draft.method == "pin") {
            Text(if (draft.hasPin) "Leave these empty to keep your saved PIN." else "Ask someone you trust to choose a PIN if you want extra accountability.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(confirmPin, { confirmPin = it.filter(Char::isDigit).take(12) }, label = { Text("Confirm PIN") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        }
        if (draft.method == "cooldown") {
            Text("Wait ${draft.cooldownMinutes} minutes before unlocking", style = MaterialTheme.typography.titleSmall)
            Slider(draft.cooldownMinutes.toFloat(), { draft = draft.copy(cooldownMinutes = it.toInt()) }, valueRange = 2f..10f, steps = 7, enabled = !active && !busy)
        }
        if (draft.method == "approval") ApprovalUnlockCard(settings)
        HorizontalDivider()
        TextButton(onClick = { protectionsExpanded = !protectionsExpanded }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(if (protectionsExpanded) "Hide protections" else "Choose protections")
        }
        if (protectionsExpanded) {
            ProtectionOption("Lock boundary settings", "Always on. You can add stronger blocks; existing rules cannot be weakened.", true, false) {}
            ProtectionOption("Prevent uninstalling FocusLock", "Stops detected uninstall and device-admin removal prompts while Strict Mode is active.", draft.preventUninstall, !active && !busy) { draft = draft.copy(preventUninstall = it) }
            ProtectionOption("Block device settings", "Restricts Android Settings. Calls, emergency tools, keyboards and required system prompts remain available.", draft.blockDeviceSettings, !active && !busy) { draft = draft.copy(blockDeviceSettings = it) }
            ProtectionOption("Block recent apps", "Closes detected overview screens to reduce floating-window bypasses.", draft.blockRecents, !active && !busy) { draft = draft.copy(blockRecents = it) }
            ProtectionOption("Block split screen", "Returns to full screen when two apps share the display.", draft.blockSplitScreen, !active && !busy) { draft = draft.copy(blockSplitScreen = it) }
            ProtectionOption("Keep reinstalled apps blocked", "Saved app identities stay blocked after reinstalling. Reinstalling does not reset your boundaries.", true, false) {}
        }
        if (!active) {
            OutlinedButton(onClick = { perform { saveOptions(); notice = "Access method and protections saved." } }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (busy) "Saving…" else "Save setup") }
            if (draft.method == "schedules" && config.method == "schedules") StrictScheduleEditor()
            else if (draft.method == "schedules") Text("Save setup, then choose your weekly focus windows.", style = MaterialTheme.typography.bodyMedium)
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        if (draft.preventUninstall || draft.blockDeviceSettings || draft.blockRecents || draft.blockSplitScreen) {
            TextButton(onClick = onOpenPermissions, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Review protection permissions") }
        }
        Text("Location is separate", style = MaterialTheme.typography.titleSmall)
        Text("Choose home-only blocking and location commitments in Boundaries → Blocking location. A saved place does not grant location permission on another device.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text("Access: ${accessMethods.first { it.first == draft.method }.second.first}. PIN, charger, cooldown or approval can end this commitment early only if selected before starting.")
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
    }, confirmButton = { TextButton(enabled = !busy && unlockPin.length >= 4, onClick = { perform { requestUnlock() } }) { Text("Unlock") } }, dismissButton = { TextButton(onClick = { showUnlock = false; unlockPin = "" }) { Text("Cancel") } })
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
