package com.focuslock.app.auth

import com.clerk.api.network.model.error.ClerkErrorResponse
import com.clerk.api.network.model.error.Error
import com.clerk.api.network.serialization.ClerkResult
import com.clerk.api.signin.SignIn
import com.clerk.api.signup.SignUp
import com.clerk.api.sso.OAuthResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GoogleTokenExchangeTest {
    @Test
    fun existingAccountKeepsItsRequiredSecondFactorAndNeverCreatesAnAccount() = runTest {
        val pending = OAuthResult(signIn = SignIn(id = "sign_in", status = SignIn.Status.NEEDS_SECOND_FACTOR))
        var signUps = 0
        val exchange = GoogleTokenExchange(
            signIn = { ClerkResult.success(pending) },
            signUp = { signUps++; error("Should not create another account") },
            transfer = { error("Should not transfer an existing sign-in") },
        )
        val result = exchange.authenticate("test-token") as ClerkResult.Success
        assertSame(pending, result.value)
        assertEquals(0, signUps)
    }

    @Test
    fun unknownGoogleAccountCreatesSignupAndPreservesRequiredProfileFields() = runTest {
        val pending = SignUp(
            id = "sign_up", status = SignUp.Status.MISSING_REQUIREMENTS,
            requiredFields = listOf("first_name"), optionalFields = emptyList(),
            missingFields = listOf("first_name"), unverifiedFields = emptyList(), verifications = emptyMap(),
            passwordEnabled = false,
        )
        var sentToken: String? = null
        val exchange = GoogleTokenExchange(
            signIn = { ClerkResult.apiFailure(ClerkErrorResponse(listOf(Error(code = "external_account_not_found")))) },
            signUp = { token -> sentToken = token; ClerkResult.success(pending) },
            transfer = { error("This signup does not require reverse transfer") },
        )
        val result = exchange.authenticate("test-token") as ClerkResult.Success
        assertSame(pending, result.value.signUp)
        assertEquals("test-token", sentToken)
    }

    @Test
    fun serverRejectionCannotAccidentallyCreateAnotherAccount() = runTest {
        val rejected = ClerkResult.apiFailure(ClerkErrorResponse(listOf(Error(code = "oauth_access_denied"))))
        var signUps = 0
        val exchange = GoogleTokenExchange(
            signIn = { rejected },
            signUp = { signUps++; error("Rejected credentials must not create accounts") },
            transfer = { error("Rejected credentials must not transfer") },
        )
        assertSame(rejected, exchange.authenticate("test-token"))
        assertEquals(0, signUps)
    }
}
