package app.getaudiobridge.output

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack

/** Preferences and routing stay native; no PCM or device handles cross the UI. */
internal object AudioRoutes {
    private val headphoneTypes = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY)
    fun selected(context: Context): String = PlaybackControl.preferences(context).getString("output", "system") ?: "system"
    fun device(context: Context, output: String): AudioDeviceInfo? {
        val devices = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return when (output) {
            "speaker" -> devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            "headphones" -> devices.firstOrNull { it.type in headphoneTypes }
            else -> null
        }
    }
    fun apply(context: Context, track: AudioTrack, output: String) {
        // Android may override a preference; the UI separately reports the actual routed device.
        check(track.setPreferredDevice(device(context, output))) { "O Android não aceitou esta saída de áudio" }
    }
    fun set(context: Context, output: String) {
        require(output in setOf("system", "speaker", "headphones")) { "Saída de áudio inválida" }
        require(output == "system" || device(context, output) != null) {
            if (output == "headphones") "Conecte um fone com fio ou USB para selecionar esta saída" else "Alto-falante indisponível"
        }
        PlaybackControl.engine?.setOutput(output)
        PlaybackControl.preferences(context).edit().putString("output", output).apply()
    }
}
