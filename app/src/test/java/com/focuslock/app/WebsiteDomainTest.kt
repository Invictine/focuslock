package com.focuslock.app

import com.focuslock.app.data.repository.SettingsRepository
import com.focuslock.app.service.TickTickApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebsiteDomainTest {

    @Test
    fun testDomainCleaning() {
        assertEquals("instagram.com", SettingsRepository.cleanDomain("https://www.instagram.com/reels/"))
        assertEquals("youtube.com", SettingsRepository.cleanDomain("https://m.youtube.com/watch?v=123"))
        assertEquals("reddit.com", SettingsRepository.cleanDomain("reddit.com/r/all"))
        assertEquals("x.com", SettingsRepository.cleanDomain("https://x.com/home"))
        assertEquals("news.ycombinator.com", SettingsRepository.cleanDomain("http://news.ycombinator.com/"))
    }

    @Test
    fun testOAuthAuthorizeUrl() {
        val url = TickTickApiClient.buildAuthorizeUrl("my_client_id", "test-state")
        assertTrue(url.contains("client_id=my_client_id"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A8080%2F"))
        assertTrue(url.contains("response_type=code"))
    }
}
