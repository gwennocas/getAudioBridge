package app.getaudiobridge.output

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Build
import android.provider.Settings
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import androidx.core.content.FileProvider
import app.tauri.plugin.JSObject
import org.json.JSONObject
import java.net.URL
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

/** User-initiated updates only. This executor never runs on the audio/control queue. */
internal object AppUpdates {
    private val executor = Executors.newSingleThreadExecutor()
    fun execute(action: () -> Unit) { executor.execute { action() } }
    private fun prefs(context: Context) = context.getSharedPreferences("getAudioBridge-updates", Context.MODE_PRIVATE)
    @Suppress("DEPRECATION")
    private fun installed(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    private fun manager(context: Context) = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    fun status(context: Context): JSObject {
        val prefs = prefs(context)
        val current = installed(context)
        val latest = prefs.getString("version", "") ?: ""
        if (prefs.getString("seenInstalled", "") != current) {
            prefs.edit().putString("seenInstalled", current).remove("download").apply()
        }
        val result = JSObject().apply { put("installed", current); put("version", latest); put("size", prefs.getLong("size", 0)) }
        if (latest.isEmpty() || (!UpdatePolicy.newer(latest, current) && latest != current)) {
            result.put("phase", if (prefs.getBoolean("checked", false)) "current" else "idle")
            return result
        }
        result.put("phase", if (latest == current) "current" else "available")
        val id = prefs.getLong("download", -1)
        if (id < 0) return result
        manager(context).query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return result
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    result.put("phase", "ready")
                    result.put("canInstall", context.packageManager.canRequestPackageInstalls())
                }
                DownloadManager.STATUS_FAILED -> {
                    result.put("error", "O download falhou. Toque para tentar novamente.")
                }
                else -> {
                    result.put("phase", "downloading")
                    result.put("downloaded", cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)))
                    result.put("total", maxOf(0L, cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))))
                }
            }
        }
        return result
    }

    fun checkForUpdate(context: Context): JSObject {
        val previous = status(context)
        if (previous.optString("phase") == "downloading") return previous
        val connection = URL("https://api.github.com/repos/gwennocas/getAudioBridge/releases/latest").openConnection() as HttpsURLConnection
        try {
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "getAudioBridge/${installed(context)}")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            check(connection.responseCode == 200) { "Não foi possível consultar o GitHub (${connection.responseCode}). Tente novamente." }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    check(output.size() + count <= 1_048_576) { "Resposta do GitHub maior que o esperado" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val release = JSONObject(String(bytes, Charsets.UTF_8))
            check(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "Nenhuma versão estável disponível" }
            val tag = release.getString("tag_name")
            val assets = release.getJSONArray("assets")
            val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull {
                UpdatePolicy.trustedAsset(tag, it.optString("name"), it.optString("browser_download_url"), it.optString("digest"))
            } ?: error("A release não possui APK arm64 oficial com checksum verificável")
            check(asset.getLong("size") in 1..80_000_000L) { "Tamanho de APK inesperado" }
            val prefs = prefs(context)
            val edit = prefs.edit()
            if (prefs.getString("digest", "") != asset.getString("digest") || tag.removePrefix("v") == installed(context)) edit.remove("download")
            edit.putBoolean("checked", true).putString("version", tag.removePrefix("v"))
                .putString("tag", tag).putString("name", asset.getString("name"))
                .putString("url", asset.getString("browser_download_url"))
                .putString("digest", asset.getString("digest").lowercase()).putLong("size", asset.getLong("size"))
                .apply()
            return status(context)
        } finally { connection.disconnect() }
    }

    fun download(context: Context): JSObject {
        val state = status(context)
        check(state.optString("phase") == "available" || (state.optString("phase") == "current" && state.optString("version") == installed(context))) { "Verifique se há uma atualização disponível" }
        val prefs = prefs(context)
        val url = prefs.getString("url", "") ?: ""
        check(UpdatePolicy.trustedAsset(prefs.getString("tag", "") ?: "", prefs.getString("name", "") ?: "", url, prefs.getString("digest", "") ?: "")) { "Endereço de atualização inválido" }
        val request = DownloadManager.Request(Uri.parse(url)).setTitle("getAudioBridge ${state.getString("version")}")
            .setDescription("Atualização solicitada pelo usuário")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "getAudioBridge-${state.getString("version")}-${System.currentTimeMillis()}.apk")
        val id = manager(context).enqueue(request)
        prefs.edit().putLong("download", id).apply()
        return status(context)
    }

    fun install(context: Context): JSObject {
        check(status(context).optString("phase") == "ready") { "Aguarde o download terminar" }
        val prefs = prefs(context)
        val uri = manager(context).getUriForDownloadedFile(prefs.getLong("download", -1)) ?: error("APK removido. Baixe novamente.")
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val verified = File(context.cacheDir, "getAudioBridge-update.apk")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                verified.outputStream().use { output ->
                    val buffer = ByteArray(32768)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        count += n; check(count <= 80_000_000) { "APK maior que o esperado" }
                        hash.update(buffer, 0, n); output.write(buffer, 0, n)
                    }
                }
            } ?: error("Não foi possível ler o APK")
            val digest = "sha256:" + hash.digest().joinToString("") { "%02x".format(it) }
            check(count == prefs.getLong("size", 0) && digest == prefs.getString("digest", "")) { "O APK não passou na verificação. Baixe novamente." }
            verifyPackage(context, verified)
        } catch (e: Exception) {
            verified.delete()
            manager(context).remove(prefs.getLong("download", -1))
            prefs.edit().remove("download").apply()
            throw e
        }
        val intent = if (!context.packageManager.canRequestPackageInstalls()) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            val verifiedUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", verified)
            Intent(Intent.ACTION_VIEW).setDataAndType(verifiedUri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return status(context)
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(context: Context, file: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("APK inválido")
        val current = context.packageManager.getPackageInfo(context.packageName, flags)
        check(candidate.packageName == context.packageName) { "O APK não pertence ao getAudioBridge" }
        fun signatures(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { it.toCharsString() }?.toSet() ?: emptySet()
        val expected = signatures(current)
        check(expected.isNotEmpty() && expected == signatures(candidate)) { "A assinatura do APK não corresponde ao aplicativo instalado" }
        val version = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        val installedVersion = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode else current.versionCode.toLong()
        check(version >= installedVersion) { "A atualização não pode instalar uma versão mais antiga" }
    }
}
