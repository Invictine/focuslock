package com.focuslock.app.auth

import android.app.Activity
import android.content.MutableContextWrapper
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.clerk.api.Clerk
import com.clerk.api.auth.types.IdTokenProvider
import com.clerk.api.network.model.error.ClerkErrorResponse
import com.clerk.api.network.model.verification.Verification
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.signin.SignIn
import com.clerk.api.signup.SignUp
import com.clerk.api.sso.OAuthResult
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.focuslock.app.BuildConfig
import java.security.SecureRandom
import android.util.Base64

internal class NativeGoogleSignInCancelled : Exception()
internal class NativeGoogleSignInUnavailable(val userMessage: String) : Exception(userMessage)

/** A user-initiated Google button opens only Google's native Credential Manager UI. */
internal suspend fun nativeGoogleSignIn(activity: Activity): ClerkResult<OAuthResult, ClerkErrorResponse> {
    val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.takeIf { it.isNotBlank() }
        ?: return ClerkResult.unknownFailure(NativeGoogleSignInUnavailable(
            "Google sign-in is not available right now. Try email instead."
        ))
    if (activity.isFinishing || activity.isDestroyed) {
        return ClerkResult.unknownFailure(NativeGoogleSignInCancelled())
    }
    val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
    val option = GetSignInWithGoogleOption.Builder(clientId)
        .setNonce(Base64.encodeToString(nonce, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING))
        .build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    val credential = try {
        CredentialManager.create(activity).getCredential(
            context = MutableContextWrapper(activity),
            request = request,
        ).credential
    } catch (_: GetCredentialCancellationException) {
        return ClerkResult.unknownFailure(NativeGoogleSignInCancelled())
    } catch (_: NoCredentialException) {
        return ClerkResult.unknownFailure(NativeGoogleSignInUnavailable(
            "No Google account is available. Add one in Android Settings or use email instead."
        ))
    } catch (_: GetCredentialException) {
        return ClerkResult.unknownFailure(NativeGoogleSignInUnavailable(
            "Google sign-in could not open. Check Google Play services and try again, or use email."
        ))
    }
    if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        return ClerkResult.unknownFailure(NativeGoogleSignInUnavailable(
            "Google did not return a sign-in credential. Please try again."
        ))
    }
    val token = try {
        GoogleIdTokenCredential.createFrom(credential.data).idToken
    } catch (_: GoogleIdTokenParsingException) {
        return ClerkResult.unknownFailure(NativeGoogleSignInUnavailable(
            "Google did not return a valid sign-in credential. Please try again."
        ))
    }
    // Clerk validates the Google ID token and owns the resulting account/session.
    return GoogleTokenExchange().authenticate(token)
}

/** Keeps new-account transfer explicit without retrying unrelated server failures as sign-up. */
internal class GoogleTokenExchange(
    private val signIn: suspend (String) -> ClerkResult<OAuthResult, ClerkErrorResponse> = { idToken ->
        Clerk.auth.signInWithIdToken { token = idToken; provider = IdTokenProvider.GOOGLE }
    },
    private val signUp: suspend (String) -> ClerkResult<SignUp, ClerkErrorResponse> = { idToken ->
        SignUp.create(SignUp.CreateParams.GoogleOneTap(idToken))
    },
    private val transfer: suspend () -> ClerkResult<SignIn, ClerkErrorResponse> = {
        SignIn.create(SignIn.CreateParams.Strategy.Transfer())
    },
) {
    suspend fun authenticate(token: String): ClerkResult<OAuthResult, ClerkErrorResponse> {
        val result = signIn(token)
        if (result !is ClerkResult.Failure ||
            result.error?.errors?.none { it.code == "external_account_not_found" } != false) {
            return result
        }
        return when (val created = signUp(token)) {
            is ClerkResult.Failure -> created
            is ClerkResult.Success -> {
                if (created.value.verifications["external_account"]?.status == Verification.Status.TRANSFERABLE) {
                    when (val existing = transfer()) {
                        is ClerkResult.Failure -> existing
                        is ClerkResult.Success -> ClerkResult.success(OAuthResult(signIn = existing.value))
                    }
                } else {
                    ClerkResult.success(OAuthResult(signUp = created.value))
                }
            }
        }
    }
}
