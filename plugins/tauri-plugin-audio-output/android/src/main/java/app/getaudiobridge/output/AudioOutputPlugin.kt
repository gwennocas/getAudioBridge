package app.getaudiobridge.output

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioTimestamp
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import app.tauri.annotation.Command
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.JSObject
import app.tauri.plugin.Invoke
import app.tauri.plugin.Plugin
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@TauriPlugin
class AudioOutputPlugin(private val host: Activity) : Plugin(host) {
    companion object {
        @JvmStatic external fun nativeRead(buffer: ByteBuffer, frames: Int): Int
        @JvmStatic external fun nativeSuspend()
    }
    private val running = AtomicBoolean(false)
    private val control = Executors.newSingleThreadExecutor()
    private val manager = host.getSystemService(Activity.AUDIO_SERVICE) as AudioManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change ->
            if (change < 0) control.execute { stopEngine(); nativeSuspend() }
        }, Handler(Looper.getMainLooper())).build()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var error: String? = null
    @Volatile private var writes = 0L
    @Volatile private var framesWritten = 0L
    @Volatile private var queuedMs = 0.0
    @Volatile private var timestampAvailable = false
    @Volatile private var hardwareQueueMs = 0.0
    @Volatile private var route = "Saída do sistema"
    @Volatile private var bufferIncreases = 0

    @Command
    fun start(invoke: Invoke) { control.execute {
        try {
            stopEngine()
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Foco de áudio indisponível" }
            error = null; writes = 0; framesWritten = 0; queuedMs = 0.0; bufferIncreases = 0
            timestampAvailable = false; hardwareQueueMs = 0.0
            val min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0) { "PCM estéreo 48 kHz não suportado: $min" }
            val audio = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(maxOf(min, 3840)).build()
            track = audio
            check(audio.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack não inicializou" }
            // getMinBufferSize is a conservative streaming allocation, not the fast-track burst.
            // Keep that allocation, but request a smaller effective write queue; Android clamps it.
            val burst = manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 240
            audio.setBufferSizeInFrames(maxOf(480, burst * 2))
            running.set(true)
            worker = Thread({ render(audio) }, "gab-audiotrack").also { it.start() }
            host.runOnUiThread { host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            invoke.resolve()
        } catch (e: Exception) { error = e.message; stopEngine(); nativeSuspend(); invoke.reject(e.message ?: "Falha de áudio") }
    } }

    private fun render(audio: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val pcm = ByteBuffer.allocateDirect(960).order(ByteOrder.LITTLE_ENDIAN)
        val stamp = AudioTimestamp()
        var previousHead = 0L; var headWrap = 0L
        var previousUnderruns = 0
        try {
            audio.play()
            while (running.get()) {
                pcm.clear()
                check(nativeRead(pcm, 240) == 960) { "Falha na ponte JNI de áudio" }
                pcm.position(0); pcm.limit(960)
                while (pcm.hasRemaining() && running.get()) {
                    val n = audio.write(pcm, pcm.remaining(), AudioTrack.WRITE_BLOCKING)
                    check(n > 0) { "AudioTrack.write: $n" }
                    framesWritten += n / 4
                }
                writes++
                if (writes % 20L == 0L) {
                    val underruns = audio.underrunCount
                    if (underruns > previousUnderruns) {
                        // Gradually trade a little latency for stability, never beyond 40 ms
                        // (or the device's initial minimum if that is already larger).
                        val current = audio.bufferSizeInFrames
                        val ceiling = maxOf(current, minOf(audio.bufferCapacityInFrames, 1920))
                        val desired = minOf(ceiling, current + 240)
                        if (desired > current && audio.setBufferSizeInFrames(desired) > current) bufferIncreases++
                    }
                    previousUnderruns = underruns
                }
                val raw = audio.playbackHeadPosition.toLong() and 0xffffffffL
                if (raw < previousHead) headWrap += 1L shl 32
                previousHead = raw
                queuedMs = maxOf(0.0, (framesWritten - headWrap - raw).toDouble() / 48.0)
                if (writes % 100L == 0L) {
                    route = audio.routedDevice?.let { "${it.productName} (tipo ${it.type})" } ?: "Saída do sistema"
                    timestampAvailable = audio.getTimestamp(stamp)
                    if (timestampAvailable) hardwareQueueMs = maxOf(0.0, (framesWritten - stamp.framePosition).toDouble() / 48.0)
                    Log.i("getAudioBridge", "writes=$writes queuedMs=$queuedMs underruns=${audio.underrunCount} route=$route")
                }
            }
        } catch (e: Exception) { if (running.get()) { error = e.message; Log.e("getAudioBridge", "Playback failed", e) } }
        finally { running.set(false) }
    }

    private fun stopEngine() {
        running.set(false)
        // pause unblocks any blocking write before joining; release only after the writer exits.
        try { track?.pause() } catch (_: Exception) {}
        worker?.join(1500)
        if (worker?.isAlive == true) {
            track?.stop(); worker?.join(1000)
        }
        worker = null
        track?.let { try { it.flush(); it.release() } catch (_: Exception) {} }
        track = null
        manager.abandonAudioFocusRequest(focus)
        host.runOnUiThread { host.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    @Command
    fun stop(invoke: Invoke) { control.execute { stopEngine(); invoke.resolve() } }
    @Command
    fun status(invoke: Invoke) { control.execute {
        val audio = track
        val s = JSObject()
        s.put("running", running.get()); s.put("error", error)
        s.put("writes", writes); s.put("underruns", audio?.underrunCount ?: 0)
        s.put("queuedMs", queuedMs); s.put("route", route)
        s.put("bufferFrames", audio?.bufferSizeInFrames ?: 0)
        s.put("bufferIncreases", bufferIncreases)
        s.put("lowLatency", audio?.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        s.put("timestampAvailable", timestampAvailable); s.put("hardwareQueueMs", hardwareQueueMs)
        invoke.resolve(s)
    } }
    override fun onPause(activity: AppCompatActivity) { control.execute { stopEngine(); nativeSuspend() } }
    override fun onDestroy(activity: AppCompatActivity) { control.execute { stopEngine(); nativeSuspend() }; control.shutdown() }
}
