package app.getaudiobridge.output

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.AudioTimestamp
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import app.tauri.plugin.JSObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** Native render engine. Lifecycle operations are serialized by PlaybackControl. */
internal class AudioEngine(context: Context, private val changed: () -> Unit, private val failed: (String) -> Unit) {
    private val context = context.applicationContext
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val attributes: AudioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
        .setOnAudioFocusChangeListener({ change -> PlaybackControl.execute {
            if (!running.get()) return@execute
            try {
                when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> { paused.set(false); track?.setVolume(1f); track?.play() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> track?.setVolume(0.2f)
                    else -> { paused.set(true); track?.pause() }
                }
                changed()
            } catch (e: Exception) { failed(e.message ?: "Falha ao alterar foco de áudio") }
        } }, Handler(Looper.getMainLooper())).build()
    private var track: AudioTrack? = null
    private var worker: Thread? = null
    private var deviceCallbackRegistered = false
    @Volatile private var desiredBufferFrames = 480
    private val routing = AudioRouting.OnRoutingChangedListener { output -> PlaybackControl.execute {
        // Android can recreate an AudioTrack with a much larger queue when changing devices.
        if (running.get() && track === output) track?.setBufferSizeInFrames(desiredBufferFrames)
    } }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) { reroute() }
        override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) { reroute() }
    }
    private fun reroute() { PlaybackControl.execute {
        if (!running.get()) return@execute
        try { setOutput(AudioRoutes.selected(context)); changed() }
        catch (e: Exception) { Log.w("getAudioBridge", "Output preference unavailable", e) }
    } }

    fun setOutput(output: String) { track?.let { AudioRoutes.apply(context, it, output) } }
    @Volatile private var writes = 0L
    @Volatile private var framesWritten = 0L
    @Volatile private var queuedMs = 0.0
    @Volatile private var timestampAvailable = false
    @Volatile private var hardwareQueueMs = 0.0
    @Volatile private var route = "Saída do sistema"
    @Volatile private var bufferIncreases = 0

    val isRunning: Boolean get() = running.get()
    val isPaused: Boolean get() = paused.get()

    fun start() {
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Foco de áudio indisponível" }
        val min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "PCM estéreo 48 kHz não suportado: $min" }
        val audio = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(min, 3840)).build()
        track = audio
        check(audio.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack não inicializou" }
        setOutput(AudioRoutes.selected(context))
        manager.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        deviceCallbackRegistered = true
        val burst = manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 240
        desiredBufferFrames = maxOf(480, burst * 2)
        audio.setBufferSizeInFrames(desiredBufferFrames)
        audio.addOnRoutingChangedListener(routing, Handler(Looper.getMainLooper()))
        paused.set(false); running.set(true)
        worker = Thread({ render(audio) }, "gab-audiotrack").also { it.start() }
    }

    fun resume() {
        if (!running.get()) return
        check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Outro aplicativo está usando o foco de áudio" }
        track?.setVolume(1f); paused.set(false); track?.play(); changed()
    }

    private fun render(audio: AudioTrack) {
        val pcm = ByteBuffer.allocateDirect(960).order(ByteOrder.LITTLE_ENDIAN)
        val stamp = AudioTimestamp()
        var previousHead = 0L; var headWrap = 0L; var previousUnderruns = 0
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val warmup = ByteArray(minOf(audio.bufferSizeInFrames, 1920) * 4)
            val primed = audio.write(warmup, 0, warmup.size, AudioTrack.WRITE_NON_BLOCKING)
            check(primed >= 0) { "AudioTrack prefill: $primed" }
            framesWritten = primed / 4L; audio.play()
            while (running.get()) {
                if (paused.get()) { Thread.sleep(20); continue }
                pcm.clear()
                check(AudioOutputPlugin.nativeRead(pcm, 240) == 960) { "Falha na ponte JNI de áudio" }
                pcm.position(0); pcm.limit(960)
                while (pcm.hasRemaining() && running.get() && !paused.get()) {
                    val n = audio.write(pcm, pcm.remaining(), AudioTrack.WRITE_BLOCKING)
                    if (n <= 0 && (!running.get() || paused.get())) break
                    check(n > 0) { "AudioTrack.write: $n" }
                    framesWritten += n / 4
                }
                writes++
                if (writes % 20L == 0L) {
                    val underruns = audio.underrunCount
                    if (underruns > previousUnderruns) {
                        val current = audio.bufferSizeInFrames
                        val desired = minOf(maxOf(current, minOf(audio.bufferCapacityInFrames, 1920)), current + 240)
                        if (desired > current && audio.setBufferSizeInFrames(desired) > current) {
                            desiredBufferFrames = audio.bufferSizeInFrames; bufferIncreases++
                        }
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
                    if (writes % 400L == 0L && Log.isLoggable("getAudioBridge", Log.DEBUG)) {
                        Log.d("getAudioBridge", "writes=$writes queuedMs=$queuedMs underruns=${audio.underrunCount} bufferFrames=${audio.bufferSizeInFrames} route=$route metrics=${AudioOutputPlugin.nativeStats()}")
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) { Log.e("getAudioBridge", "Playback failed", e); failed(e.message ?: "Falha de reprodução") }
        } finally { running.set(false) }
    }

    fun stop() {
        running.set(false); paused.set(false)
        if (deviceCallbackRegistered) { manager.unregisterAudioDeviceCallback(devices); deviceCallbackRegistered = false }
        try { track?.pause() } catch (_: Exception) {}
        worker?.join(1500)
        if (worker?.isAlive == true) { try { track?.stop() } catch (_: Exception) {}; worker?.join(1000) }
        check(worker?.isAlive != true) { "A thread de áudio não encerrou" }
        worker = null
        track?.let { try { it.removeOnRoutingChangedListener(routing); it.flush(); it.release() } catch (_: Exception) {} }
        track = null; queuedMs = 0.0; hardwareQueueMs = 0.0; timestampAvailable = false
        manager.abandonAudioFocusRequest(focus)
    }

    fun snapshot(): JSObject = JSObject().apply {
        put("running", running.get()); put("paused", paused.get()); put("writes", writes)
        put("underruns", track?.underrunCount ?: 0); put("queuedMs", queuedMs); put("route", route)
        put("bufferFrames", track?.bufferSizeInFrames ?: 0); put("bufferIncreases", bufferIncreases)
        put("lowLatency", track?.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        put("timestampAvailable", timestampAvailable); put("hardwareQueueMs", hardwareQueueMs)
    }
}
