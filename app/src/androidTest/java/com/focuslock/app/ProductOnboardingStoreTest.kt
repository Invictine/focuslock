package com.focuslock.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.focuslock.app.ui.onboarding.ProductOnboardingStore
import com.focuslock.app.ui.onboarding.productTourPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductOnboardingStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context
    private val preferences get() = context.getSharedPreferences(PREFS_NAME, 0)

    @get:Rule val preferencesBackup = object : ExternalResource() {
        private var saved: Map<String, Any?> = emptyMap()

        override fun before() {
            saved = preferences.all.toMap()
            preferences.edit().clear().commit()
        }

        override fun after() {
            preferences.edit().clear().apply {
                saved.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }.commit()
        }
    }

    @Test
    fun savedPageRoundTripsAndPauseResumeAreSeparateFromCompletion() {
        val store = ProductOnboardingStore(context)
        assertTrue(store.shouldAutoShow)
        assertFalse(store.isComplete)

        store.savePage(4)
        assertEquals(4, ProductOnboardingStore(context).pageIndex)
        store.pause()
        assertFalse(store.shouldAutoShow)
        assertFalse(store.isComplete)

        ProductOnboardingStore(context).resume()
        assertTrue(store.shouldAutoShow)
        assertEquals(4, store.pageIndex)

        store.finish()
        assertTrue(store.isComplete)
        assertFalse(store.shouldAutoShow)

        store.replay()
        assertFalse(store.isComplete)
        assertTrue(store.shouldAutoShow)
        assertEquals(0, store.pageIndex)
    }

    @Test
    fun savedPageIsClampedToAvailableTopics() {
        val store = ProductOnboardingStore(context)
        store.savePage(productTourPages.size + 20)
        assertEquals(productTourPages.lastIndex, ProductOnboardingStore(context).pageIndex)

        store.savePage(-5)
        assertEquals(0, ProductOnboardingStore(context).pageIndex)
    }

    private companion object {
        const val PREFS_NAME = "focuslock_product_onboarding"
    }
}
