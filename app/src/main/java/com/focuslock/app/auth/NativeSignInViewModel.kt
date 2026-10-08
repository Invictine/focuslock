package com.focuslock.app.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clerk.api.Clerk
import com.clerk.api.credentials.shouldSuppressCredentialFlowError
import com.clerk.api.network.model.error.ClerkErrorResponse
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.network.serialization.errorMessage
import com.clerk.api.session.Session.SessionStatus
import com.clerk.api.session.SessionTaskKey
import com.clerk.api.session.pendingTaskKey
import com.clerk.api.signin.SignIn
import com.clerk.api.signin.attemptFirstFactor
import com.clerk.api.signin.attemptSecondFactor
import com.clerk.api.signin.authenticateWithPasskey
import com.clerk.api.signin.resetPassword
import com.clerk.api.signin.sendEmailCode
import com.clerk.api.signin.sendMfaEmailCode
import com.clerk.api.signin.sendMfaPhoneCode
import com.clerk.api.signup.SignUp
import com.clerk.api.signup.attemptVerification
import com.clerk.api.signup.sendEmailCode
import com.clerk.api.signup.update
import com.clerk.api.sso.OAuthResult
import com.focuslock.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NativeSignInStep { Options, Email, Code, Password, Profile, Complete }

data class NativeSignInUiState(
    val step: NativeSignInStep = NativeSignInStep.Options,
    val busy: Boolean = false,
    val error: String? = null,
    val email: String = "",
    val codePrompt: String = "",
    val requiredFields: List<String> = emptyList(),
    val secondFactors: List<String> = emptyList(),
    val googleAvailable: Boolean = true,
    val emailAvailable: Boolean = true,
    val verificationKind: String = "email_code",
    val canResendCode: Boolean = true,
    val passwordIsNew: Boolean = false,
)

/** App-owned Clerk auth flow. Google Credential Manager is invoked only from the explicit button. */
class NativeSignInViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(NativeSignInUiState(googleAvailable = false, emailAvailable = false))
    val uiState = _uiState.asStateFlow()

    private var signIn: SignIn? = null
    private var signUp: SignUp? = null
    private var emailCodeMode: EmailCodeMode? = null
    private var secondFactorStrategy: String? = null
    private var activeSecondFactor: String? = null
    private var newPasswordRequired = false
    private var requestJob: Job? = null
    private var requestVersion = 0L

    init {
        refreshAvailability()
        viewModelScope.launch { Clerk.isInitialized.collect { refreshAvailability() } }
    }

    fun signInWithGoogle(activity: Activity) = launchRequest {
        clearAttempt()
        when (val result = withContext(Dispatchers.IO) { nativeGoogleSignIn(activity) }) {
            is ClerkResult.Success<OAuthResult> -> handleOAuthResult(result.value)
            is ClerkResult.Failure<ClerkErrorResponse> -> {
                when {
                    nativeGoogleFailureDisposition(
                        cancelled = result.throwable is NativeGoogleSignInCancelled,
                        accountUnavailable = result.throwable is NativeGoogleSignInUnavailable,
                        suppress = result.shouldSuppressCredentialFlowError,
                    ) == NativeGoogleFailureDisposition.SILENT ->
                        update { it.copy(step = NativeSignInStep.Options, error = null) }
                    nativeGoogleFailureDisposition(
                        cancelled = result.throwable is NativeGoogleSignInCancelled,
                        accountUnavailable = result.throwable is NativeGoogleSignInUnavailable,
                        suppress = result.shouldSuppressCredentialFlowError,
                    ) == NativeGoogleFailureDisposition.ACCOUNT_UNAVAILABLE ->
                        update { it.copy(step = NativeSignInStep.Options, error = (result.throwable as NativeGoogleSignInUnavailable).userMessage) }
                    else -> showFailure(result)
                }
            }
        }
    }

    fun showEmail() {
        if (_uiState.value.busy) return
        clearAttempt()
        update { it.copy(step = NativeSignInStep.Email, error = null) }
    }

    fun submitEmail(email: String) = launchRequest {
        val normalized = email.trim()
        if (!normalized.isValidEmailAddress()) {
            fail("Enter a valid email address.")
            return@launchRequest
        }
        clearAttempt()
        update { it.copy(email = normalized, error = null) }

        when (
            val result =
                withContext(Dispatchers.IO) {
                    SignIn.create(SignIn.CreateParams.Strategy.Identifier(identifier = normalized))
                }
        ) {
            is ClerkResult.Success -> handleSignIn(result.value)
            is ClerkResult.Failure -> {
                if (result.error?.errors?.any { it.code == FORM_IDENTIFIER_NOT_FOUND } == true) {
                    startEmailSignUp(normalized)
                } else {
                    showFailure(result)
                }
            }
        }
    }

    fun submitPassword(password: String) = launchRequest {
        if (password.isBlank()) {
            fail("Enter your password.")
            return@launchRequest
        }
        val current = signIn
        if (newPasswordRequired && current != null) {
            when (val result = withContext(Dispatchers.IO) { current.resetPassword(password) }) {
                is ClerkResult.Success -> handleSignIn(result.value)
                is ClerkResult.Failure -> showFailure(result)
            }
            return@launchRequest
        }
        if (current == null) {
            fail("Start again by entering your email address.")
            return@launchRequest
        }
        when (
            val result =
                withContext(Dispatchers.IO) {
                    current.attemptFirstFactor(SignIn.AttemptFirstFactorParams.Password(password))
                }
        ) {
            is ClerkResult.Success -> handleSignIn(result.value)
            is ClerkResult.Failure -> showFailure(result)
        }
    }

    fun submitCode(code: String) = launchRequest {
        val normalized = code.trim()
        if (normalized.isBlank()) {
            fail("Enter the verification code.")
            return@launchRequest
        }
        when (emailCodeMode) {
            EmailCodeMode.SignUp -> {
                val current = signUp ?: return@launchRequest fail("Restart sign-up to continue.")
                when (
                    val result =
                        withContext(Dispatchers.IO) {
                            current.attemptVerification(SignUp.AttemptVerificationParams.EmailCode(normalized))
                        }
                ) {
                    is ClerkResult.Success -> handleSignUp(result.value)
                    is ClerkResult.Failure -> showFailure(result)
                }
            }
            EmailCodeMode.SignIn -> {
                val current = signIn ?: return@launchRequest fail("Restart sign-in to continue.")
                when (
                    val result =
                        withContext(Dispatchers.IO) {
                            current.attemptFirstFactor(SignIn.AttemptFirstFactorParams.EmailCode(normalized))
                        }
                ) {
                    is ClerkResult.Success -> handleSignIn(result.value)
                    is ClerkResult.Failure -> showFailure(result)
                }
            }
            EmailCodeMode.SecondFactor -> submitSecondFactorCode(normalized)
            null -> fail("Restart sign-in to request a new verification code.")
        }
    }

    fun submitProfile(fields: Map<String, String>) = launchRequest {
        val current = signUp ?: return@launchRequest fail("Restart sign-up to continue.")
        val normalized = fields.mapValues { (field, value) -> if (field == "password") value else value.trim() }
        val missing = current.missingFields.filter { current.requiredFields.contains(it) }
        val unfilled = missing.filter { normalized[it].isNullOrBlank() }
        if (unfilled.isNotEmpty()) {
            update { it.copy(step = NativeSignInStep.Profile, error = "Complete the required fields.") }
            return@launchRequest
        }
        val resultFields = SignUp.SignUpUpdateParams.Standard(
            emailAddress = normalized["email_address"] ?: normalized["email"] ?: current.emailAddress,
            password = normalized["password"],
            firstName = normalized["first_name"],
            lastName = normalized["last_name"],
            username = normalized["username"],
            phoneNumber = normalized["phone_number"],
        )
        when (val result = withContext(Dispatchers.IO) { current.update(resultFields) }) {
            is ClerkResult.Success -> handleSignUp(result.value)
            is ClerkResult.Failure -> showFailure(result)
        }
    }

    fun resendCode() = launchRequest {
        when (emailCodeMode) {
            EmailCodeMode.SignUp -> {
                val current = signUp ?: return@launchRequest fail("Restart sign-up to continue.")
                when (val result = withContext(Dispatchers.IO) { current.sendEmailCode() }) {
                    is ClerkResult.Success -> {
                        signUp = result.value
                        showCode("A new code was sent to ${result.value.emailAddress.orEmpty()}.", EmailCodeMode.SignUp)
                    }
                    is ClerkResult.Failure -> showFailure(result)
                }
            }
            EmailCodeMode.SignIn -> {
                val current = signIn ?: return@launchRequest fail("Restart sign-in to continue.")
                when (val result = withContext(Dispatchers.IO) { current.sendEmailCode() }) {
                    is ClerkResult.Success -> {
                        signIn = result.value
                        showCode("A new code was sent to ${uiState.value.email}.", EmailCodeMode.SignIn)
                    }
                    is ClerkResult.Failure -> showFailure(result)
                }
            }
            EmailCodeMode.SecondFactor -> {
                val current = signIn ?: return@launchRequest fail("Restart sign-in to continue.")
                val strategy = activeSecondFactor ?: return@launchRequest fail("Choose a verification method.")
                dispatchSecondFactor(current, strategy, resend = true)
            }
            null -> fail("There is no code to resend.")
        }
    }

    fun chooseSecondFactor(strategy: String) = launchRequest {
        val current = signIn ?: return@launchRequest fail("Restart sign-in to continue.")
        dispatchSecondFactor(current, strategy)
    }

    private suspend fun dispatchSecondFactor(current: SignIn, strategy: String, resend: Boolean = false) {
        val factor = current.supportedSecondFactors.orEmpty().firstOrNull { it.strategy == strategy }
        if (factor == null) {
            fail("That verification method is no longer available. Choose another method.")
            return
        }
        secondFactorStrategy = strategy
        activeSecondFactor = strategy
        when (strategy) {
            FACTOR_EMAIL_CODE -> when (val result = withContext(Dispatchers.IO) { current.sendMfaEmailCode(factor.emailAddressId) }) {
                is ClerkResult.Success -> {
                    signIn = result.value
                    showCode("${if (resend) "A new code was sent to" else "Enter the code sent to"} ${factor.safeIdentifier ?: uiState.value.email}.", EmailCodeMode.SecondFactor)
                }
                is ClerkResult.Failure -> showFailure(result)
            }
            FACTOR_PHONE_CODE -> when (val result = withContext(Dispatchers.IO) { current.sendMfaPhoneCode(factor.phoneNumberId) }) {
                is ClerkResult.Success -> {
                    signIn = result.value
                    showCode("${if (resend) "A new code was sent to" else "Enter the code sent to"} ${factor.safeIdentifier ?: "your phone"}.", EmailCodeMode.SecondFactor)
                }
                is ClerkResult.Failure -> showFailure(result)
            }
            FACTOR_PASSKEY -> when (val result = withContext(Dispatchers.IO) { current.authenticateWithPasskey() }) {
                is ClerkResult.Success -> handleSignIn(result.value)
                is ClerkResult.Failure -> showFailure(result)
            }
            FACTOR_TOTP, FACTOR_BACKUP_CODE -> {
                emailCodeMode = EmailCodeMode.SecondFactor
                update {
                    it.copy(
                        step = NativeSignInStep.Code,
                        codePrompt = if (strategy == FACTOR_TOTP) "Enter your authenticator app code." else "Enter one of your backup codes.",
                        verificationKind = strategy,
                        canResendCode = false,
                        secondFactors = current.supportedSecondFactors.orEmpty()
                            .filter { it.strategy != strategy }
                            .map { it.strategy },
                        error = null,
                    )
                }
            }
            else -> fail("This verification method needs a supported app credential or a different factor.")
        }
    }

    fun backToOptions() {
        requestVersion++
        requestJob?.cancel()
        requestJob = null
        clearAttempt()
        refreshAvailability()
        update { it.copy(step = NativeSignInStep.Options, busy = false, error = null, email = "", codePrompt = "", requiredFields = emptyList(), secondFactors = emptyList(), passwordIsNew = false, verificationKind = FACTOR_EMAIL_CODE, canResendCode = true) }
    }

    private suspend fun startEmailSignUp(email: String) {
        when (
            val result =
                withContext(Dispatchers.IO) {
                    SignUp.create(SignUp.CreateParams.Standard(emailAddress = email))
                }
        ) {
            is ClerkResult.Success -> handleSignUp(result.value)
            is ClerkResult.Failure -> showFailure(result)
        }
    }

    private suspend fun handleOAuthResult(result: OAuthResult) {
        when {
            result.signIn != null -> handleSignIn(requireNotNull(result.signIn))
            result.signUp != null -> handleSignUp(requireNotNull(result.signUp))
            else -> fail("Google sign-in returned an incomplete response. Try again.")
        }
    }

    private suspend fun handleSignIn(value: SignIn) {
        signIn = value
        signUp = null
        emailCodeMode = null
        newPasswordRequired = false
        when (value.status) {
            SignIn.Status.COMPLETE -> activate(value.createdSessionId)
            SignIn.Status.NEEDS_FIRST_FACTOR -> {
                val factors = value.supportedFirstFactors.orEmpty()
                val emailCode = factors.firstOrNull { it.strategy == FACTOR_EMAIL_CODE }
                if (emailCode != null) {
                    when (val result = withContext(Dispatchers.IO) { value.sendEmailCode(emailCode.emailAddressId) }) {
                        is ClerkResult.Success -> {
                            signIn = result.value
                            showCode("Enter the code sent to ${value.identifier ?: uiState.value.email}.", EmailCodeMode.SignIn)
                        }
                        is ClerkResult.Failure -> showFailure(result)
                    }
                    return
                }
                if (factors.any { it.strategy == FACTOR_PASSWORD }) {
                    update { it.copy(step = NativeSignInStep.Password, error = null, secondFactors = emptyList(), passwordIsNew = false) }
                } else {
                    fail("This account has no supported email sign-in method on this device.")
                }
            }
            SignIn.Status.NEEDS_SECOND_FACTOR, SignIn.Status.NEEDS_CLIENT_TRUST -> {
                val factors = value.supportedSecondFactors.orEmpty()
                val offered = factors.map { it.strategy }.distinct()
                update {
                    it.copy(
                        step = NativeSignInStep.Code,
                        codePrompt = if (value.status == SignIn.Status.NEEDS_CLIENT_TRUST) "Verify this device to continue." else "Complete your second-step verification.",
                        secondFactors = offered,
                        error = null,
                    )
                }
                val defaultFactor = factors.firstOrNull { it.default == true }
                    ?: factors.firstOrNull { it.strategy == FACTOR_TOTP }
                    ?: factors.firstOrNull { it.strategy == FACTOR_EMAIL_CODE }
                    ?: factors.firstOrNull { it.strategy == FACTOR_PHONE_CODE }
                    ?: factors.firstOrNull { it.strategy == FACTOR_BACKUP_CODE }
                    ?: factors.firstOrNull { it.strategy == FACTOR_PASSKEY }
                if (defaultFactor != null) dispatchSecondFactor(value, defaultFactor.strategy)
                else fail("No supported verification method is available for this account.")
            }
            SignIn.Status.NEEDS_NEW_PASSWORD -> {
                newPasswordRequired = true
                update { it.copy(step = NativeSignInStep.Password, error = null, secondFactors = emptyList(), passwordIsNew = true) }
            }
            SignIn.Status.NEEDS_IDENTIFIER -> fail("Enter your email address to continue.")
            SignIn.Status.UNKNOWN -> fail("We could not finish signing you in. Please try again.")
        }
    }

    private suspend fun handleSignUp(value: SignUp) {
        signUp = value
        signIn = null
        emailCodeMode = null
        when (value.status) {
            SignUp.Status.COMPLETE -> activate(value.createdSessionId)
            SignUp.Status.ABANDONED -> fail("This sign-up expired. Start again with your email address.")
            SignUp.Status.UNKNOWN -> fail("We could not finish creating your account. Please try again.")
            SignUp.Status.MISSING_REQUIREMENTS -> {
                val toCollect = value.missingFields.filter { value.requiredFields.contains(it) }
                val fieldsToVerify = value.unverifiedFields
                when {
                    toCollect.isNotEmpty() -> update {
                        it.copy(step = NativeSignInStep.Profile, requiredFields = toCollect, error = null, secondFactors = emptyList())
                    }
                    "email_address" in fieldsToVerify && value.emailAddress != null -> {
                        when (val result = withContext(Dispatchers.IO) { value.sendEmailCode() }) {
                            is ClerkResult.Success -> {
                                signUp = result.value
                                showCode("Enter the code sent to ${result.value.emailAddress}.", EmailCodeMode.SignUp)
                            }
                            is ClerkResult.Failure -> showFailure(result)
                        }
                    }
                    fieldsToVerify.isNotEmpty() -> fail("Additional verification is required for ${fieldsToVerify.joinToString()}.")
                    else -> fail("Complete the remaining sign-up requirements and try again.")
                }
            }
        }
    }

    private suspend fun submitSecondFactorCode(code: String) {
        val current = signIn ?: return fail("Restart sign-in to continue.")
        val strategy = activeSecondFactor ?: secondFactorStrategy ?: return fail("Choose a verification method.")
        val params = when (strategy) {
            FACTOR_TOTP -> SignIn.AttemptSecondFactorParams.TOTP(code)
            FACTOR_BACKUP_CODE -> SignIn.AttemptSecondFactorParams.BackupCode(code)
            FACTOR_EMAIL_CODE -> SignIn.AttemptSecondFactorParams.EmailCode(code)
            FACTOR_PHONE_CODE -> SignIn.AttemptSecondFactorParams.PhoneCode(code)
            else -> return fail("This verification method is not supported.")
        }
        when (val result = withContext(Dispatchers.IO) { current.attemptSecondFactor(params) }) {
            is ClerkResult.Success -> handleSignIn(result.value)
            is ClerkResult.Failure -> showFailure(result)
        }
    }

    private suspend fun activate(sessionId: String?) {
        if (sessionId.isNullOrBlank()) {
            fail("Authentication completed without a session. Try again.")
            return
        }
        when (val result = withContext(Dispatchers.IO) { Clerk.auth.setActive(sessionId) }) {
            is ClerkResult.Success -> {
                val completionIssue = nativeSessionCompletionIssue(result.value.status, result.value.pendingTaskKey)
                if (completionIssue != null) {
                    fail(completionIssue)
                } else {
                    update { it.copy(step = NativeSignInStep.Complete, busy = false, error = null, requiredFields = emptyList(), secondFactors = emptyList()) }
                }
            }
            is ClerkResult.Failure -> showFailure(result)
        }
    }

    private fun showFailure(result: ClerkResult.Failure<ClerkErrorResponse>) {
        update { it.copy(busy = false, error = result.errorMessage) }
    }

    private fun showCode(prompt: String, mode: EmailCodeMode) {
        emailCodeMode = mode
        update { it.copy(
            step = NativeSignInStep.Code, codePrompt = prompt, error = null, requiredFields = emptyList(),
            verificationKind = if (mode == EmailCodeMode.SecondFactor) activeSecondFactor ?: FACTOR_EMAIL_CODE else FACTOR_EMAIL_CODE,
            canResendCode = true,
            secondFactors = if (mode == EmailCodeMode.SecondFactor) signIn?.supportedSecondFactors.orEmpty()
                .map { factor -> factor.strategy }.distinct().filter { strategy -> strategy != activeSecondFactor }
                else emptyList(),
        ) }
    }

    private fun fail(message: String) {
        update { it.copy(busy = false, error = message) }
    }

    private fun launchRequest(block: suspend () -> Unit) {
        if (_uiState.value.busy) return
        val version = ++requestVersion
        requestJob = viewModelScope.launch {
            if (_uiState.value.busy) return@launch
            update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                update { it.copy(busy = false, error = "Authentication could not be completed. Please try again.") }
            } finally {
                if (version == requestVersion && _uiState.value.busy) update { it.copy(busy = false) }
            }
        }
    }

    private fun update(transform: (NativeSignInUiState) -> NativeSignInUiState) {
        _uiState.value = transform(_uiState.value)
    }

    private fun clearAttempt() {
        signIn = null
        signUp = null
        emailCodeMode = null
        secondFactorStrategy = null
        activeSecondFactor = null
        newPasswordRequired = false
    }

    private fun refreshAvailability() {
        val configured = runCatching { BuildConfig.CLERK_PUBLISHABLE_KEY.trim().startsWith("pk_") }.getOrDefault(false)
        update {
            it.copy(
                googleAvailable = configured && Clerk.isInitialized.value && Clerk.isGoogleOneTapEnabled && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank(),
                emailAvailable = configured && Clerk.isInitialized.value && Clerk.isEmailEnabled,
            )
        }
    }

    private fun String.isValidEmailAddress(): Boolean =
        matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))

    private enum class EmailCodeMode { SignUp, SignIn, SecondFactor }

    private companion object {
        const val FORM_IDENTIFIER_NOT_FOUND = "form_identifier_not_found"
        const val FACTOR_EMAIL_CODE = "email_code"
        const val FACTOR_PHONE_CODE = "phone_code"
        const val FACTOR_PASSWORD = "password"
        const val FACTOR_TOTP = "totp"
        const val FACTOR_BACKUP_CODE = "backup_code"
        const val FACTOR_PASSKEY = "passkey"
    }
}

internal enum class NativeGoogleFailureDisposition { SILENT, ACCOUNT_UNAVAILABLE, SHOW_ERROR }

internal fun nativeGoogleFailureDisposition(
    cancelled: Boolean,
    accountUnavailable: Boolean,
    suppress: Boolean,
): NativeGoogleFailureDisposition = when {
    cancelled || suppress -> NativeGoogleFailureDisposition.SILENT
    accountUnavailable -> NativeGoogleFailureDisposition.ACCOUNT_UNAVAILABLE
    else -> NativeGoogleFailureDisposition.SHOW_ERROR
}

internal fun nativeSessionCompletionIssue(
    status: SessionStatus,
    pendingTask: SessionTaskKey?,
): String? = when {
    status != SessionStatus.ACTIVE -> "Your account needs one more verification step before FocusLock can continue."
    pendingTask == SessionTaskKey.MFA_REQUIRED -> "Your account requires two-step security setup before continuing."
    pendingTask == SessionTaskKey.RESET_PASSWORD -> "Your account requires a password reset before continuing."
    pendingTask == SessionTaskKey.CHOOSE_ORGANIZATION -> "Choose an organization for this account before continuing."
    pendingTask == SessionTaskKey.UNKNOWN -> "Your account has a pending verification step that FocusLock cannot complete yet."
    else -> null
}

