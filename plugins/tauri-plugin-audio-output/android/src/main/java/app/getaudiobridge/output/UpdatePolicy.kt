package app.getaudiobridge.output

internal object UpdatePolicy {
    private val version = Regex("^v?(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,6})$")
    fun newer(candidate: String, installed: String): Boolean {
        val a = version.matchEntire(candidate)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
        val b = version.matchEntire(installed)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
        return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
    }
    fun trustedAsset(tag: String, name: String, url: String, digest: String): Boolean {
        if (!version.matches(tag) || !Regex("^sha256:[a-fA-F0-9]{64}$").matches(digest)) return false
        val number = Regex.escape(tag.removePrefix("v"))
        if (!Regex("^getAudioBridge[_-]v?$number[_-]android-arm64\\.apk$").matches(name)) return false
        return url == "https://github.com/gwennocas/getAudioBridge/releases/download/$tag/$name"
    }
}
