package com.focuslock.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class BlockedApp(
    val packageName: String,
    val appName: String,
    val isBlocked: Boolean = true,
    val category: String = "Social Media",
    val specificShortsOnly: Boolean = false
) {
    companion object {
        val DEFAULT_DOOMSCROLL_APPS = listOf(
            BlockedApp("com.instagram.android", "Instagram", isBlocked = true, category = "Social Media"),
            BlockedApp("com.google.android.youtube", "YouTube", isBlocked = true, category = "Entertainment"),
            BlockedApp("com.zhiliaoapp.musically", "TikTok", isBlocked = true, category = "Social Media"),
            BlockedApp("com.reddit.frontpage", "Reddit", isBlocked = true, category = "Social Media"),
            BlockedApp("com.twitter.android", "X (Twitter)", isBlocked = true, category = "Social Media"),
            BlockedApp("com.facebook.katana", "Facebook", isBlocked = false, category = "Social Media"),
            BlockedApp("com.snapchat.android", "Snapchat", isBlocked = false, category = "Social Media"),
            BlockedApp("tv.twitch.android.app", "Twitch", isBlocked = false, category = "Entertainment"),
            BlockedApp("com.netflix.mediaclient", "Netflix", isBlocked = false, category = "Entertainment"),
            BlockedApp("com.discord", "Discord", isBlocked = false, category = "Messaging")
        )
    }
}
