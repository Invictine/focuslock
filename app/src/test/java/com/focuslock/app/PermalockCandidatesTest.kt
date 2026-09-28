package com.focuslock.app

import com.focuslock.app.service.InstalledApp
import com.focuslock.app.ui.permalock.permalockCandidates
import org.junit.Assert.assertEquals
import org.junit.Test

class PermalockCandidatesTest {

    private val protected = setOf("com.example.launcher", "com.android.settings")

    private fun app(packageName: String, appName: String, isSystem: Boolean = false) =
        InstalledApp(
            packageName = packageName,
            appName = appName,
            category = "Other",
            isSystem = isSystem
        )

    private val apps = listOf(
        app("com.instagram.android", "Instagram"),
        app("com.google.android.youtube", "YouTube"),
        app("com.example.launcher", "Example Launcher", isSystem = true),
        app("com.android.settings", "Settings", isSystem = true),
        app("com.focuslock.app", "FocusLock"),
    )

    @Test
    fun excludesPermanentAndProtectedAndKeepsInputOrder() {
        val result = permalockCandidates(
            apps = apps,
            permanentPackages = setOf("com.instagram.android"),
            query = "",
            isProtected = { it in protected },
        )
        assertEquals(
            listOf("com.google.android.youtube", "com.focuslock.app"),
            result.map { it.packageName },
        )
    }

    @Test
    fun permanentMatchingIsTrimmedAndCaseInsensitive() {
        val result = permalockCandidates(
            apps = apps,
            permanentPackages = setOf("  COM.Instagram.Android "),
            query = "",
            isProtected = { false },
        )
        assertEquals(
            listOf("com.google.android.youtube", "com.example.launcher", "com.android.settings", "com.focuslock.app"),
            result.map { it.packageName },
        )
    }

    @Test
    fun queryMatchesLabelOrPackageIgnoringCaseAndWhitespace() {
        assertEquals(
            listOf("com.instagram.android"),
            permalockCandidates(apps, emptySet(), "  instagram ", { false }).map { it.packageName },
        )
        // Package-name substring match ("youtube" is inside the label and the package).
        assertEquals(
            listOf("com.google.android.youtube"),
            permalockCandidates(apps, emptySet(), "youtube", { false }).map { it.packageName },
        )
    }

    @Test
    fun blankOrEmptyPackagesAreNeverCandidates() {
        val result = permalockCandidates(
            apps = listOf(app("", "Nameless"), app("   ", "Blank")) + apps,
            permanentPackages = emptySet(),
            query = "",
            isProtected = { false },
        )
        assertEquals(
            listOf("com.instagram.android", "com.google.android.youtube", "com.example.launcher", "com.android.settings", "com.focuslock.app"),
            result.map { it.packageName },
        )
    }
}
