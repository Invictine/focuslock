package com.focuslock.app

import com.focuslock.app.service.BrowserUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlPolicyTest {

    @Test
    fun googleAppUsesOnlyKnownToolbarResources() {
        assertEquals(
            listOf(
                "${BrowserUrlPolicy.GOOGLE_APP}:id/googleapp_browser_url",
                "${BrowserUrlPolicy.GOOGLE_APP}:id/googleapp_browser_url_text",
                "${BrowserUrlPolicy.GOOGLE_APP}:id/url_bar",
            ),
            BrowserUrlPolicy.urlBarIds(
                BrowserUrlPolicy.GOOGLE_APP,
                knownIds = listOf(
                    "${BrowserUrlPolicy.GOOGLE_APP}:id/search_box_text",
                    "${BrowserUrlPolicy.GOOGLE_APP}:id/other_toolbar",
                ),
                genericNames = listOf("search_box_text", "url"),
            ),
        )
        assertFalse(BrowserUrlPolicy.allowsPageTextFallback(BrowserUrlPolicy.GOOGLE_APP))
    }

    @Test
    fun ordinaryBrowserUsesOwnedAndGenericIdsOnce() {
        assertEquals(
            listOf(
                "com.example.browser:id/address_bar",
                "com.example.browser:id/url_bar",
                "com.example.browser:id/search",
            ),
            BrowserUrlPolicy.urlBarIds(
                packageName = "com.example.browser",
                knownIds = listOf(
                    "com.example.browser:id/address_bar",
                    "com.other.browser:id/address_bar",
                    "com.example.browser:id/url_bar",
                ),
                genericNames = listOf("url_bar", "search"),
            ),
        )
        assertTrue(BrowserUrlPolicy.allowsPageTextFallback("com.example.browser"))
    }

    @Test
    fun toolbarUrlAcceptsDomainsSchemesAndDescriptionFallback() {
        assertEquals("example.co.in", BrowserUrlPolicy.toolbarUrl("example.co.in", null))
        assertEquals(
            "https://example.com/path?q=1",
            BrowserUrlPolicy.toolbarUrl("https://example.com/path?q=1", null),
        )
        assertEquals(
            "example.org/page",
            BrowserUrlPolicy.toolbarUrl("Search results", "example.org/page"),
        )
        assertEquals("https://x.com/home", BrowserUrlPolicy.toolbarUrl("https://x.com/home", null))
        assertEquals(
            "https://m.youtube.com/watch?v=123",
            BrowserUrlPolicy.toolbarUrl("https://m.youtube.com/watch?v=123", null),
        )
    }

    @Test
    fun toolbarUrlRejectsProseQueryAndBlankValues() {
        assertNull(BrowserUrlPolicy.toolbarUrl("Search results for cats", null))
        assertNull(BrowserUrlPolicy.toolbarUrl("?q=cats", null))
        assertNull(BrowserUrlPolicy.toolbarUrl("   ", "\t"))
    }
}
