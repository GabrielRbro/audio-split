package io.github.audiosplit.tone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * This player intentionally never chooses an audio device. The UID policy in the main APK
 * must route it; selecting a device here would make the external-routing test meaningless.
 *
 * A short static PCM buffer loops in AudioTrack so playback continues with the screen off
 * without a synthesizer thread or an application wake lock.
 */
class ToneService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager
    private lateinit var notifications: NotificationManager
    private var player: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusGeneration = 0L
    private var foreground = false
    private val expire = Runnable { finish(getString(R.string.session_expired)) }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.channel_description)
            setSound(null, null)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (player == null) startPlayer(intent.getBooleanExtra(EXTRA_REQUEST_FOCUS, false))
            ACTION_STOP -> finish(getString(R.string.stopped_by_user))
            else -> if (player == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startPlayer(requestsFocus: Boolean) {
        try {
            // Android 15+ requires a foreground Activity or foreground service to obtain focus.
            // Enter the foreground before requesting it, also satisfying the FGS deadline.
            startForeground(NOTIFICATION_ID, notification(getString(R.string.notification_preparing)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
            ToneStatus.update(ToneStatus.Snapshot(active = true, requestsFocus = requestsFocus, message = getString(R.string.starting)))

            if (requestsFocus && !obtainFocus()) {
                finish(getString(R.string.focus_denied))
                return
            }

            val pcm = makePulseBuffer(BuildConfig.TONE_FREQUENCY_HZ)
            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
                .build()
            player = track // Own the resource before any operation that could fail.
            check(track.state != AudioTrack.STATE_UNINITIALIZED) { "AudioTrack não inicializado" }
            check(track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING) == pcm.size) { "Escrita PCM incompleta" }
            check(track.setLoopPoints(0, pcm.size, -1) == AudioTrack.SUCCESS) { "Loop PCM não suportado" }
            track.play()
            publishPlaying(requestsFocus)
            handler.postDelayed(expire, MAX_SESSION_MILLIS)
        } catch (error: RuntimeException) {
            finish(getString(R.string.playback_error, error.message ?: error.javaClass.simpleName))
        }
    }

    private fun obtainFocus(): Boolean {
        val generation = ++focusGeneration
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (generation == focusGeneration) handleFocus(change)
            }, handler)
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun handleFocus(change: Int) {
        val track = player ?: return
        try {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    track.play()
                    publishPlaying(requestsFocus = true)
                }
                AudioManager.AUDIOFOCUS_LOSS -> finish(getString(R.string.stopped_for_focus))
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    track.pause()
                    val message = getString(R.string.paused_for_focus)
                    ToneStatus.update(ToneStatus.Snapshot(active = true, requestsFocus = true, message = message))
                    updateNotification(message)
                }
            }
        } catch (error: RuntimeException) {
            finish(getString(R.string.playback_error, error.message ?: error.javaClass.simpleName))
        }
    }

    private fun publishPlaying(requestsFocus: Boolean) {
        val message = getString(if (requestsFocus) R.string.playing_with_focus else R.string.playing_without_focus)
        ToneStatus.update(ToneStatus.Snapshot(active = true, playing = true, requestsFocus = requestsFocus, message = message))
        updateNotification(message)
    }

    private fun finish(message: String) {
        releasePlayback()
        ToneStatus.update(ToneStatus.Snapshot(message = message))
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    private fun releasePlayback() {
        handler.removeCallbacks(expire)
        ++focusGeneration // Ignore queued callbacks from an abandoned request.
        val oldPlayer = player
        player = null
        if (oldPlayer != null) {
            try {
                oldPlayer.stop()
            } catch (_: IllegalStateException) {
                // Releasing an uninitialized or already stopped track is still required.
            } finally {
                runCatching { oldPlayer.release() }
            }
        }
        val oldFocus = focusRequest
        focusRequest = null
        if (oldFocus != null) runCatching { audioManager.abandonAudioFocusRequest(oldFocus) }
    }

    override fun onDestroy() {
        releasePlayback()
        if (ToneStatus.current.active) ToneStatus.update(ToneStatus.Snapshot(message = getString(R.string.service_stopped)))
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(message: String) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notifications.notify(NOTIFICATION_ID, notification(message))
        }
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, ToneActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ToneService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tone)
            .setContentTitle(getString(R.string.notification_title, BuildConfig.TONE_NAME, BuildConfig.TONE_FREQUENCY_HZ))
            .setContentText(message)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop_tone), stop).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_START = "io.github.audiosplit.tone.START"
        const val ACTION_STOP = "io.github.audiosplit.tone.STOP"
        const val EXTRA_REQUEST_FOCUS = "requestFocus"
        private const val CHANNEL = "tone_playback"
        private const val NOTIFICATION_ID = 42
        private const val SAMPLE_RATE = 48_000
        private const val MAX_SESSION_MILLIS = 10 * 60 * 1_000L

        private fun makePulseBuffer(frequency: Int): ShortArray {
            val activeFrames = SAMPLE_RATE * 3 / 4
            val periodFrames = SAMPLE_RATE * 3 / 2
            val rampFrames = SAMPLE_RATE / 50 // 20 ms attack and release, avoiding clicks.
            val peak = Short.MAX_VALUE * 0.08
            return ShortArray(periodFrames) { frame ->
                if (frame >= activeFrames) {
                    0
                } else {
                    val envelope = min(1.0, min(frame.toDouble() / rampFrames, (activeFrames - 1 - frame).toDouble() / rampFrames))
                    (sin(2.0 * PI * frequency * frame / SAMPLE_RATE) * peak * envelope).toInt().toShort()
                }
            }
        }
    }
}
