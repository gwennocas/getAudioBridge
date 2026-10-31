package app.getaudiobridge.output

import android.app.Activity
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import java.nio.ByteBuffer

@InvokeArg
internal class ConnectArgs {
    lateinit var ip: String
    var port: Int = 45150
    var bufferMs: Int = 60
}

@InvokeArg
internal class BufferArgs { var bufferMs: Int = 60 }

@InvokeArg
internal class OutputArgs { lateinit var output: String }

/** Controls only. The Service owns playback and never retains this Activity. */
@TauriPlugin
class AudioOutputPlugin(host: Activity) : app.tauri.plugin.Plugin(host) {
    private val context = host.applicationContext
    companion object {
        init { System.loadLibrary("get_audio_bridge_lib") }
        @JvmStatic external fun nativeConnect(ip: String, port: Int, bufferMs: Int): String?
        @JvmStatic external fun nativeSetBuffer(bufferMs: Int)
        @JvmStatic external fun nativeRead(buffer: ByteBuffer, frames: Int): Int
        @JvmStatic external fun nativeSuspend()
        @JvmStatic external fun nativeStats(): String?
    }

    @Command
    fun start(invoke: Invoke) {
        val args = invoke.parseArgs(ConnectArgs::class.java)
        PlaybackControl.start(context, ConnectionOptions(args.ip, args.port, args.bufferMs)) { error ->
            if (error == null) invoke.resolve() else invoke.reject(error)
        }
    }

    @Command
    fun stop(invoke: Invoke) { PlaybackControl.stop { invoke.resolve() } }

    @Command
    fun status(invoke: Invoke) { PlaybackControl.execute { invoke.resolve(PlaybackControl.snapshot(context)) } }

    @Command
    fun setOutput(invoke: Invoke) {
        val args = invoke.parseArgs(OutputArgs::class.java)
        PlaybackControl.execute {
            try { AudioRoutes.set(context, args.output); invoke.resolve() }
            catch (e: Exception) { invoke.reject(e.message ?: "Falha ao trocar saída") }
        }
    }

    @Command
    fun setBuffer(invoke: Invoke) {
        val args = invoke.parseArgs(BufferArgs::class.java)
        PlaybackControl.setBuffer(context, args.bufferMs) { invoke.resolve() }
    }

    // Activity pause/destruction deliberately has no playback side effects.
}
