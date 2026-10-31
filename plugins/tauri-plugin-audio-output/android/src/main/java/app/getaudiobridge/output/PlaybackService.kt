package app.getaudiobridge.output

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import app.tauri.plugin.JSObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

internal data class ConnectionOptions(val ip: String, val port: Int, val bufferMs: Int)

/** Process-wide control queue; no Activity, WebView or Tauri Invoke is retained after a command. */
internal object PlaybackControl {
    private val executor = Executors.newSingleThreadExecutor()
    val main = Handler(Looper.getMainLooper())
    private val requestIds = AtomicLong()
    private val pending = ConcurrentHashMap<Long, (String?) -> Unit>()
    @Volatile var service: PlaybackService? = null
    var engine: AudioEngine? = null
    var lastError: String? = null

    fun execute(action: () -> Unit) { executor.execute { action() } }
    fun preferences(context: Context): SharedPreferences = context.getSharedPreferences("getAudioBridge-playback", Context.MODE_PRIVATE)

    fun start(context: Context, options: ConnectionOptions, completed: (String?) -> Unit) {
        val id = requestIds.incrementAndGet()
        pending[id] = completed
        main.postDelayed({
            pending.remove(id)?.let { callback ->
                callback("O serviço de reprodução não iniciou a tempo")
                stop {}
            }
        }, 10000)
        try {
            context.startForegroundService(Intent(context, PlaybackService::class.java)
                .setAction(PlaybackService.START).putExtra("requestId", id)
                .putExtra("ip", options.ip).putExtra("port", options.port)
                .putExtra("bufferMs", options.bufferMs.coerceIn(20, 120)))
        } catch (e: Exception) { complete(id, e.message ?: "Não foi possível iniciar o serviço") }
    }

    fun complete(id: Long, error: String?) { pending.remove(id)?.invoke(error) }

    fun stop(completed: () -> Unit) {
        val current = service
        if (current != null) current.stopPlayback(completed)
        else execute {
            try { engine?.stop() } catch (e: Exception) { Log.e("getAudioBridge", "Stop failed", e) }
            engine = null; AudioOutputPlugin.nativeSuspend(); completed()
        }
    }

    fun setBuffer(context: Context, value: Int, completed: () -> Unit) { execute {
        val target = value.coerceIn(20, 120)
        preferences(context).edit().putInt("bufferMs", target).apply()
        AudioOutputPlugin.nativeSetBuffer(target); completed()
    } }

    fun snapshot(context: Context): JSObject = (engine?.snapshot() ?: JSObject().apply {
        put("running", false); put("paused", false); put("writes", 0)
        put("underruns", 0); put("queuedMs", 0); put("bufferFrames", 0)
    }).apply {
        put("error", lastError); put("backgroundService", service != null)
        put("wakeLockHeld", service?.cpuAwake == true)
        put("wifiLockHeld", service?.wifiAwake == true)
        put("selectedOutput", AudioRoutes.selected(context))
        put("speakerAvailable", AudioRoutes.device(context, "speaker") != null)
        put("headphonesAvailable", AudioRoutes.device(context, "headphones") != null)
    }
}

/** Audio, Rust UDP receiver and locks live here, independently of Activity lifetime. */
class PlaybackService : Service() {
    companion object {
        const val START = "app.getaudiobridge.output.START"
        const val STOP = "app.getaudiobridge.output.STOP"
        private const val CHANNEL = "getAudioBridge-playback"
        private const val NOTIFICATION_ID = 45150
    }
    private lateinit var session: MediaSession
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var latestStartId = 0
    private var sessionReleased = false
    val cpuAwake: Boolean get() = wakeLock?.isHeld == true
    val wifiAwake: Boolean get() = wifiLock?.isHeld == true

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL, "Áudio do computador", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Reprodução contínua do getAudioBridge com controles para desconectar"
            setSound(null, null); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        createMediaSession()
        PlaybackControl.service = this
    }

    private fun createMediaSession() {
        sessionReleased = false
        session = MediaSession(this, "getAudioBridge").apply {
            setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "getAudioBridge")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Áudio ao vivo do computador").build())
            setSessionActivity(openAppIntent())
            setCallback(object : MediaSession.Callback() {
                override fun onStop() { stopPlayback() }
                // A manual pause on the lock screen explicitly disconnects the live stream.
                override fun onPause() { stopPlayback() }
                override fun onCustomAction(action: String, extras: Bundle?) { if (action == STOP) stopPlayback() }
                override fun onPlay() { PlaybackControl.execute {
                    try { PlaybackControl.engine?.resume() }
                    catch (e: Exception) { PlaybackControl.lastError = e.message; updateMediaState() }
                } }
            }, PlaybackControl.main)
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == STOP) { stopPlayback(); return START_NOT_STICKY }
        if (sessionReleased) createMediaSession()
        val prefs = PlaybackControl.preferences(this)
        val options = if (intent?.action == START) {
            ConnectionOptions(intent.getStringExtra("ip") ?: "", intent.getIntExtra("port", 45150), intent.getIntExtra("bufferMs", 60))
        } else if (intent == null && prefs.getBoolean("wanted", false)) {
            ConnectionOptions(prefs.getString("ip", "") ?: "", prefs.getInt("port", 45150), prefs.getInt("bufferMs", 60))
        } else { stopSelfResult(startId); return START_NOT_STICKY }
        val requestId = intent?.getLongExtra("requestId", 0) ?: 0
        try {
            // Promote before requesting audio focus: Android 15 requires a visible app or FGS.
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICATION_ID, notification(false))
        } catch (e: Exception) {
            PlaybackControl.complete(requestId, e.message ?: "Falha ao criar notificação de mídia")
            stopSelfResult(startId); return START_NOT_STICKY
        }
        PlaybackControl.execute {
            try {
                PlaybackControl.engine?.stop(); PlaybackControl.engine = null
                PlaybackControl.service = this
                PlaybackControl.lastError = null
                acquireLocks()
                val error = AudioOutputPlugin.nativeConnect(options.ip, options.port, options.bufferMs)
                check(error == null) { error ?: "Falha de conexão" }
                prefs.edit().putString("ip", options.ip).putInt("port", options.port)
                    .putInt("bufferMs", options.bufferMs.coerceIn(20, 120)).putBoolean("wanted", true).commit()
                val audio = AudioEngine(applicationContext, { updateMediaState() }, { reason ->
                    PlaybackControl.execute { PlaybackControl.lastError = reason; stopPlayback() }
                })
                PlaybackControl.engine = audio
                audio.start(); updateMediaState()
                PlaybackControl.complete(requestId, null)
                Log.i("getAudioBridge", "Media service started; screen-off playback enabled")
            } catch (e: Exception) {
                PlaybackControl.lastError = e.message ?: "Falha na reprodução"
                val reason = PlaybackControl.lastError
                stopPlayback { PlaybackControl.complete(requestId, reason) }
            }
        }
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "getAudioBridge:playback").apply { setReferenceCounted(false) }
        if (!cpuAwake) wakeLock?.acquire()
        // Best effort: recent Android versions may constrain Wi-Fi lock behavior.
        // The media FGS and partial CPU lock remain independent of those optimizations.
        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            if (wifiLock == null) wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "getAudioBridge:stream")
                ?.apply { setReferenceCounted(false) }
            if (!wifiAwake) wifiLock?.acquire()
        } catch (e: Exception) { Log.w("getAudioBridge", "Wi-Fi optimization unavailable", e) }
    }

    private fun releaseLocks() {
        if (cpuAwake) wakeLock?.release()
        if (wifiAwake) wifiLock?.release()
        wakeLock = null; wifiLock = null
    }

    fun stopPlayback(completed: () -> Unit = {}) {
        val stoppingStartId = latestStartId
        PlaybackControl.execute {
            PlaybackControl.preferences(this).edit().putBoolean("wanted", false).commit()
            if (PlaybackControl.service === this) {
                try { PlaybackControl.engine?.stop() } catch (e: Exception) { Log.e("getAudioBridge", "Stop failed", e) }
                PlaybackControl.engine = null
                AudioOutputPlugin.nativeSuspend()
                PlaybackControl.service = null
            }
            releaseLocks()
            PlaybackControl.main.post {
                // Do not tear down a newer START racing with an older STOP.
                if (latestStartId == stoppingStartId) {
                    releaseSession(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(stoppingStartId)
                }
                completed()
            }
            Log.i("getAudioBridge", "Playback disconnected; audio, UDP and power locks released")
        }
    }

    private fun openAppIntent(): PendingIntent? = packageManager.getLaunchIntentForPackage(packageName)?.let {
        PendingIntent.getActivity(this, 0, it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notification(paused: Boolean): Notification {
        val disconnect = PendingIntent.getService(this, 1, Intent(this, PlaybackService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_audio)
            .setContentTitle("getAudioBridge").setContentText(if (paused) "Áudio pausado por outro aplicativo" else "Áudio do computador · reprodução contínua")
            .setContentIntent(openAppIntent()).setDeleteIntent(disconnect).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Desconectar", disconnect).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0)).build()
    }

    private fun updateMediaState() { PlaybackControl.main.post {
        if (sessionReleased || PlaybackControl.service !== this) return@post
        val paused = PlaybackControl.engine?.isPaused == true
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE)
            .setState(if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (paused) 0f else 1f)
            .addCustomAction(STOP, "Desconectar", R.drawable.ic_stat_audio).build())
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(paused))
    } }

    private fun releaseSession() {
        if (!sessionReleased) { session.isActive = false; session.release(); sessionReleased = true }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseSession()
        PlaybackControl.execute {
            if (PlaybackControl.service === this) {
                try { PlaybackControl.engine?.stop() } catch (e: Exception) { Log.e("getAudioBridge", "Cleanup failed", e) }
                PlaybackControl.engine = null; AudioOutputPlugin.nativeSuspend(); PlaybackControl.service = null
            }
            releaseLocks()
        }
        super.onDestroy()
    }
    // Removing the Activity from recents deliberately leaves this service running.
}
