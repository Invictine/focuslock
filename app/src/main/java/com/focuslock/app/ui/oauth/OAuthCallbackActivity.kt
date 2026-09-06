package com.focuslock.app.ui.oauth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.focuslock.app.FocusLockApplication
import com.focuslock.app.service.TickTickApiClient
import com.focuslock.app.service.TickTickAuthConfig
import com.focuslock.app.ui.MainActivity
import com.focuslock.app.ui.theme.FocusLockTheme
import kotlinx.coroutines.launch

class OAuthCallbackActivity : ComponentActivity() {

    private val apiClient = TickTickApiClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val data = intent.data
        val isLoopback = data?.scheme == "http" && data.host == "127.0.0.1"
        val isLegacyCustomScheme = data?.scheme == "focuslock" && data.host == "oauth" && data.path == "/callback"
        if (!isLoopback && !isLegacyCustomScheme) {
            finish()
            return
        }
        val code = data.getQueryParameter("code")
        val error = data.getQueryParameter("error")

        setContent {
            FocusLockTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Text(
                            text = "Connecting to TickTick...",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        if (error != null) {
            Toast.makeText(this, "TickTick authorization canceled or failed: $error", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (!code.isNullOrBlank()) {
            handleAuthorizationCode(code, data.getQueryParameter("state"))
        } else {
            Toast.makeText(this, "Invalid authorization response.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun handleAuthorizationCode(code: String, state: String?) {
        lifecycleScope.launch {
            val settings = FocusLockApplication.instance.settingsRepository
            if (!settings.consumeTickTickState(state)) {
                Toast.makeText(this@OAuthCallbackActivity, "Login expired or could not be verified. Connect again from Settings.", Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            val clientId = TickTickAuthConfig.effectiveClientId(settings)
            val clientSecret = TickTickAuthConfig.effectiveClientSecret(settings)

            if (clientId.isBlank() || clientSecret.isBlank()) {
                Toast.makeText(
                    this@OAuthCallbackActivity,
                    "TickTick login not configured on this build.",
                    Toast.LENGTH_LONG
                ).show()
                val mainIntent = Intent(this@OAuthCallbackActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(mainIntent)
                finish()
                return@launch
            }

            val tokenResponse = apiClient.exchangeCodeForToken(clientId, clientSecret, code)
            if (tokenResponse != null && tokenResponse.accessToken.isNotBlank()) {
                val profile = apiClient.fetchUserProfile(tokenResponse.accessToken)
                val displayName = profile?.nickname ?: profile?.username ?: profile?.email ?: "TickTick User"

                settings.setTickTickAuthSuccess(
                    tokenResponse.accessToken,
                    displayName,
                    refreshToken = tokenResponse.refreshToken,
                    expiresInSec = tokenResponse.expiresIn
                )
                Toast.makeText(
                    this@OAuthCallbackActivity,
                    "Connected successfully as $displayName!",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this@OAuthCallbackActivity,
                    "Failed to exchange authorization code. Try again.",
                    Toast.LENGTH_LONG
                ).show()
            }

            val mainIntent = Intent(this@OAuthCallbackActivity, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(mainIntent)
            finish()
        }
    }
}
