package com.focuslock.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class BlockedWebsite(
    val domain: String,
    val displayName: String,
    val isBlocked: Boolean = true,
    val category: String = "Social Media",
    val isCustom: Boolean = false
) {
    companion object {
        val DEFAULT_BLOCKED_WEBSITES = listOf(
            BlockedWebsite("instagram.com", "Instagram Web", isBlocked = true, category = "Social Media"),
            BlockedWebsite("youtube.com", "YouTube Web", isBlocked = true, category = "Entertainment"),
            BlockedWebsite("m.youtube.com", "YouTube Mobile", isBlocked = true, category = "Entertainment"),
            BlockedWebsite("reddit.com", "Reddit Web", isBlocked = true, category = "Social Media"),
            BlockedWebsite("tiktok.com", "TikTok Web", isBlocked = true, category = "Entertainment"),
            BlockedWebsite("x.com", "X (Twitter) Web", isBlocked = true, category = "Social Media"),
            BlockedWebsite("twitter.com", "Twitter Web", isBlocked = true, category = "Social Media"),
            BlockedWebsite("facebook.com", "Facebook Web", isBlocked = false, category = "Social Media"),
            BlockedWebsite("twitch.tv", "Twitch Web", isBlocked = false, category = "Entertainment"),
            BlockedWebsite("netflix.com", "Netflix Web", isBlocked = false, category = "Entertainment")
        )
    }
}
