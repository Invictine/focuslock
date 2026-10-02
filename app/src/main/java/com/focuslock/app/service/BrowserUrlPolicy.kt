package com.focuslock.app.service

/** Google also contains search results: only its browser toolbar is a navigation source. */
internal object BrowserUrlPolicy {
    const val GOOGLE_APP = "com.google.android.googlequicksearchbox"

    private val googleToolbarResources = listOf(
        "googleapp_browser_url", "googleapp_browser_url_text", "url_bar"
    )

    fun urlBarIds(packageName: String, knownIds: List<String>, genericNames: List<String>): List<String> {
        if (packageName == GOOGLE_APP) {
            return googleToolbarResources.map { "$packageName:id/$it" }
        }
        return (knownIds.filter { it.startsWith("$packageName:id/") } +
            genericNames.map { "$packageName:id/$it" }).distinct()
    }

    fun allowsPageTextFallback(packageName: String): Boolean = packageName != GOOGLE_APP

    fun toolbarUrl(text: CharSequence?, description: CharSequence?): String? =
        listOf(text, description).mapNotNull { it?.toString()?.trim() }.firstOrNull {
            it.isNotBlank() && it.length <= 2048 && it.none(Char::isWhitespace) &&
                (it.startsWith("https://") || it.startsWith("http://") ||
                    Regex("^(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?::[0-9]+)?(?:[/?#].*)?$").matches(it))
        }
}
