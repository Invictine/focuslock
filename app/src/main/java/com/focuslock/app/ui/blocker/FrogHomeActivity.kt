package com.focuslock.app.ui.blocker

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.service.FrogHomeLauncher
import com.focuslock.app.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** A real Home destination, so Home gestures don't visit the ordinary launcher first. */
class FrogHomeActivity : ComponentActivity() {
    private var locked by mutableStateOf(true)
    private var locationChecked by mutableStateOf(false)
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setWindowAnimations(0)
        enableEdgeToEdge()
        hideFrogStatusBar()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!locked) openRegularHome() }
        })
        setContent {
            if (locationChecked && locked) FrogFocusScreen(
                onOpenFocusLock = { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)) },
                // The lifecycle controller below owns arming, location and Home handoff.
                onFrogComplete = {},
                onFrogEnded = {},
            ) else Box(Modifier.fillMaxSize().background(Color.Black))
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                leaving = false
                val app = FocusLockApplication.instance
                // Arming may write settings; Home's first frame need not wait for it.
                val arming = launch { app.frogRepository.armIfDue() }
                launch {
                    while (true) {
                        app.homeLocationRepository.shouldEnforceNow()
                        delay(10_000)
                    }
                }
                app.frogRepository.frogStateFlow.collectLatest { state ->
                    if (!state.locked) {
                        arming.join()
                        if (app.frogRepository.currentState().locked) return@collectLatest
                    }
                    while (true) {
                        val enforce = app.homeLocationRepository.shouldEnforceOnAppSwitch()
                        locked = state.locked && enforce
                        if (!locked) { openRegularHome(); return@collectLatest }
                        locationChecked = true
                        delay(10_000)
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideFrogStatusBar()
    }

    override fun onStop() {
        locationChecked = false
        super.onStop()
    }

    private fun openRegularHome() {
        if (leaving) return
        leaving = true
        if (!FrogHomeLauncher.openRegularHome(this)) {
            // Keep a useful route if no other launcher is installed.
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}

internal fun ComponentActivity.hideFrogStatusBar() {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        hide(WindowInsetsCompat.Type.statusBars())
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
}
