package com.focuslock.app.reminder

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.focuslock.app.ui.permissions.PermissionHelper
import com.focuslock.app.ui.theme.FocusLockTheme
import java.io.File

/** A user-dismissible pause before disabling or removing FocusLock. */
class RemovalReminderActivity : ComponentActivity() {
    private lateinit var media: ReminderMediaSession
    private var reason: String = REASON_UNINSTALL
    internal val isReminderPlaying: Boolean
        get() = ::media.isInitialized && media.isPlaying

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reason = normalizeReason(intent.getStringExtra(EXTRA_REASON))
        if (reason != REASON_PREVIEW && !RemovalReminderStore(this).enabled) {
            finish()
            return
        }
        media = ReminderMediaSession(this)

        setContent {
            FocusLockTheme {
                ReminderPopup(
                    reason = reason,
                    media = media,
                    onDismiss = { finish() },
                    onKeepFocusLock = {
                        when (reason) {
                            REASON_PREVIEW -> finish()
                            REASON_ADMIN_DISABLED -> {
                                RemovalReminderController.allowTemporarily(this)
                                PermissionHelper.openDeviceAdminSettings(this)
                                finish()
                            }
                            REASON_DEACTIVATE -> finish()
                            else -> {
                                val home = Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_HOME)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatching { startActivity(home) }
                                finish()
                            }
                        }
                    },
                    onContinue = {
                        RemovalReminderController.allowTemporarily(this)
                        if (reason == REASON_DEACTIVATE) PermissionHelper.disableDeviceAdmin(this)
                        finish()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RemovalReminderController.onActivityVisible(this)
        if (::media.isInitialized) media.resumeAfterLifecycle()
    }

    override fun onPause() {
        if (::media.isInitialized) media.pauseForLifecycle()
        RemovalReminderController.onActivityPaused()
        super.onPause()
    }

    override fun onDestroy() {
        if (::media.isInitialized) media.release()
        if (!isChangingConfigurations) RemovalReminderController.onActivityDismissed(this, reason)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REASON = "com.focuslock.app.reminder.extra.REASON"
        const val REASON_PREVIEW = "preview"
        const val REASON_ADMIN_REQUESTED = "admin_requested"
        const val REASON_ADMIN_DISABLED = "admin_disabled"
        const val REASON_UNINSTALL = "uninstall"
        const val REASON_DEACTIVATE = "deactivate"

        fun intent(context: Context, reason: String): Intent =
            Intent(context, RemovalReminderActivity::class.java)
                .putExtra(EXTRA_REASON, normalizeReason(reason))

        private fun normalizeReason(reason: String?): String = when (reason) {
            REASON_PREVIEW, REASON_ADMIN_REQUESTED, REASON_ADMIN_DISABLED,
            REASON_UNINSTALL, REASON_DEACTIVATE -> reason
            else -> REASON_UNINSTALL
        }
    }
}

@Composable
private fun ReminderPopup(
    reason: String,
    media: ReminderMediaSession,
    onDismiss: () -> Unit,
    onKeepFocusLock: () -> Unit,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { RemovalReminderStore(context) }
    val file = remember { store.mediaFile() }
    val mime = remember { store.mediaMimeType().orEmpty() }
    val name = remember { store.mediaName().orEmpty() }
    var mediaError by remember(file) { mutableStateOf(false) }

    LaunchedEffect(file, mime) {
        if (file != null && (store.enabled || reason == RemovalReminderActivity.REASON_PREVIEW)) {
            media.prepare(file, mime, onError = { mediaError = true })
        }
    }

    val (body, primaryLabel, showContinue) = when (reason) {
        RemovalReminderActivity.REASON_PREVIEW -> Triple(
            "This is a preview of the reminder shown when you choose to turn off uninstall protection or remove FocusLock.",
            "Close preview",
            false
        )
        RemovalReminderActivity.REASON_ADMIN_REQUESTED -> Triple(
            "Android is asking whether to turn off FocusLock's uninstall protection. You can go back to FocusLock or continue to Android's confirmation.",
            "Keep FocusLock",
            true
        )
        RemovalReminderActivity.REASON_ADMIN_DISABLED -> Triple(
            "Uninstall protection has been turned off. FocusLock is still installed. You can turn protection back on in Android settings.",
            "Turn on uninstall protection",
            true
        )
        RemovalReminderActivity.REASON_DEACTIVATE -> Triple(
            "Continuing will turn off FocusLock's device administrator protection. Android may then let you remove the app.",
            "Keep FocusLock",
            true
        )
        else -> Triple(
            "Android is asking whether to remove FocusLock. You can return to your Home screen or continue to Android's confirmation.",
            "Keep FocusLock",
            true
        )
    }

    Box(
        Modifier.fillMaxSize().safeDrawingPadding().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onDismiss
        ),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 680.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Do you really want to do this?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (file != null && (store.enabled || reason == RemovalReminderActivity.REASON_PREVIEW)) {
                    Spacer(Modifier.height(20.dp))
                    if (mediaError) {
                        Text(
                            "This reminder media could not be played. You can still choose what to do.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        if (mime.startsWith("video/")) {
                            Box(
                                Modifier.fillMaxWidth().heightIn(max = 280.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                AndroidView(
                                    factory = { ctx -> media.createVideoSurface(ctx) },
                                    modifier = Modifier
                                        .widthIn(max = 280.dp * media.videoAspect)
                                        .fillMaxWidth()
                                        .aspectRatio(media.videoAspect)
                                        .clip(RoundedCornerShape(16.dp))
                                )
                            }
                        } else {
                            Surface(
                                modifier = Modifier.fillMaxWidth().height(120.dp),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("Personal reminder", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Text(
                            text = name.ifBlank { "Personal reminder" },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { media.toggle() },
                                modifier = Modifier.size(48.dp).semantics {
                                    contentDescription = if (media.isPlaying) "Pause reminder media" else "Play reminder media"
                                }
                            ) {
                                Icon(
                                    imageVector = if (media.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null
                                )
                            }
                            Spacer(Modifier.width(20.dp))
                            IconButton(
                                onClick = { media.replay() },
                                modifier = Modifier.size(48.dp).semantics { contentDescription = "Replay reminder media" }
                            ) {
                                Icon(Icons.Default.Replay, contentDescription = null)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Button(onClick = onKeepFocusLock, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(primaryLabel)
                }
                if (showContinue) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Continue")
                    }
                    Text(
                        if (reason == RemovalReminderActivity.REASON_DEACTIVATE)
                            "Continue turns off device administrator protection and returns to Android."
                        else "Continue returns to Android. FocusLock will not confirm removal for you.",
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/** Owns a one-shot local player. Volume is never changed; transient audio focus is respected. */
private class ReminderMediaSession(private val context: Context) {
    var isPlaying by mutableStateOf(false)
        private set
    var videoAspect by mutableStateOf(16f / 9f)
        private set
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var video = false
    private var surfaceHolder: SurfaceHolder? = null
    private var resumeAfterPause = false
    private var prepared = false
    private var resumed = false
    private var pendingAutoplay = true
    private var onError: (() -> Unit)? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    fun createVideoSurface(ctx: Context): SurfaceView = SurfaceView(ctx).also { view ->
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceHolder = holder
                runCatching { player?.setDisplay(holder) }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (surfaceHolder === holder) surfaceHolder = null
            }
        })
    }

    fun prepare(source: File, mime: String, onError: () -> Unit) {
        if (file == source && player != null) return
        releasePlayer()
        file = source
        video = mime.startsWith("video/")
        this.onError = onError
        val created = MediaPlayer()
        player = created
        try {
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (video) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            if (video) surfaceHolder?.let(created::setDisplay)
            created.setDataSource(source.absolutePath)
            created.setOnPreparedListener { mp ->
                prepared = true
                if (resumed && requestAudioFocus()) {
                    runCatching { mp.start(); isPlaying = true; pendingAutoplay = false }.onFailure { onError() }
                }
            }
            created.setOnVideoSizeChangedListener { _, width, height ->
                if (width > 0 && height > 0) videoAspect = width.toFloat() / height.toFloat()
            }
            created.setOnCompletionListener { isPlaying = false; abandonAudioFocus() }
            created.setOnErrorListener { _, _, _ -> isPlaying = false; onError(); abandonAudioFocus(); true }
            created.prepareAsync()
        } catch (_: Exception) {
            player = null
            created.release()
            onError()
        }
    }

    fun toggle() {
        val current = player ?: return
        if (!prepared) return
        if (isPlaying) {
            runCatching { current.pause() }
            isPlaying = false
            abandonAudioFocus()
        } else if (requestAudioFocus()) {
            runCatching { current.start(); isPlaying = true }.onFailure { onError?.invoke() }
        }
    }

    fun replay() {
        val current = player ?: return
        if (!prepared) return
        if (requestAudioFocus()) runCatching { current.seekTo(0); current.start(); isPlaying = true }
    }

    fun pauseForLifecycle() {
        resumed = false
        resumeAfterPause = isPlaying
        if (isPlaying) {
            runCatching { player?.pause() }
            isPlaying = false
            abandonAudioFocus()
        }
    }

    fun resumeAfterLifecycle() {
        resumed = true
        if ((resumeAfterPause || pendingAutoplay) && prepared && requestAudioFocus()) {
            runCatching { player?.start(); isPlaying = true; pendingAutoplay = false }
        }
        resumeAfterPause = false
    }

    fun release() = releasePlayer()

    private fun requestAudioFocus(): Boolean {
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change < 0) {
                if (isPlaying) {
                    resumeAfterPause = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    runCatching { player?.pause() }
                    isPlaying = false
                }
            }
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(if (video) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC).build()
                    )
                    .setOnAudioFocusChangeListener(listener)
                    .setWillPauseWhenDucked(true)
                    .build()
                focusRequest = request
                audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: Exception) { false }
    }

    private fun abandonAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let(audioManager::abandonAudioFocusRequest)
        }
        focusRequest = null
    }

    private fun releasePlayer() {
        abandonAudioFocus()
        runCatching { player?.release() }
        player = null
        prepared = false
        isPlaying = false
    }
}
