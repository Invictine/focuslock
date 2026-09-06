package com.focuslock.app

import com.focuslock.app.service.OAuthStateValidator
import com.focuslock.app.service.TickTickApiClient
import okhttp3.Credentials
import okhttp3.FormBody
import org.junit.Assert.*
import org.junit.Test

class TickTickOAuthTest {
    @Test fun tokenExchangeUsesBasicAuthenticationAndExactCallback() {
        val request = TickTickApiClient.authorizationCodeRequest("test-client", "test-secret", "code+value")
        assertEquals("https://ticktick.com/oauth/token", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals(Credentials.basic("test-client", "test-secret"), request.header("Authorization"))
        val form = request.body as FormBody
        val fields = (0 until form.size).associate { form.name(it) to form.value(it) }
        assertEquals("authorization_code", fields["grant_type"])
        assertEquals("http://127.0.0.1:8080/", fields["redirect_uri"])
        assertEquals("code+value", fields["code"])
        assertFalse(fields.containsKey("client_secret"))
    }

    @Test fun authorizeUrlCarriesEncodedStateAndCallback() {
        val url = TickTickApiClient.buildAuthorizeUrl("client", "unique state+")
        assertTrue(url.contains("state=unique+state%2B"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A8080%2F"))
        assertTrue(url.contains("response_type=code"))
    }

    @Test fun parsesFullRedirectUrlAndBareCode() {
        val full = TickTickApiClient.parseManualCallback("http://127.0.0.1:8080/?code=abc123&state=xyz")
        assertEquals("abc123" to "xyz", full)
        val bare = TickTickApiClient.parseManualCallback("abc123")
        assertEquals("abc123" to null, bare)
        assertEquals(null, TickTickApiClient.parseManualCallback("   "))
        assertEquals(null, TickTickApiClient.parseManualCallback("not a code with spaces"))
    }

    @Test fun acceptsOnlyMatchingUnexpiredState() {
        assertTrue(OAuthStateValidator.isValid("nonce", "nonce", 1_000, 2_000))
        assertFalse(OAuthStateValidator.isValid("nonce", "wrong", 1_000, 2_000))
        assertFalse(OAuthStateValidator.isValid(null, null, 1_000, 2_000))
        assertFalse(OAuthStateValidator.isValid("", "", 1_000, 2_000))
        assertFalse(OAuthStateValidator.isValid("nonce", "nonce", 1_000, 601_001))
        assertFalse(OAuthStateValidator.isValid("nonce", "nonce", 3_000, 2_000))
    }
}
