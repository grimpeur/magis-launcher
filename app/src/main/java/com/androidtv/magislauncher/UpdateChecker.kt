package com.androidtv.magislauncher

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class RemoteVersion(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String,
)

object UpdateChecker {

    // Timeouts cortos: el chequeo corre antes de conectar la VPN y no tiene que demorar el arranque.
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    suspend fun check(): RemoteVersion? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(LauncherConfig.VERSION_JSON_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val json = JSONObject(response.body!!.string())
                val remoteCode = json.getInt("versionCode")
                if (remoteCode <= BuildConfig.VERSION_CODE) return@withContext null
                RemoteVersion(
                    versionCode = remoteCode,
                    versionName = json.getString("versionName"),
                    apkUrl = json.getString("apkUrl"),
                    changelog = json.getString("changelog"),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun download(context: Context, remote: RemoteVersion): Long {
        val dir = File(context.externalCacheDir, LauncherConfig.DOWNLOAD_DIR).apply { mkdirs() }
        File(dir, LauncherConfig.DOWNLOAD_FILE_NAME).delete()

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(remote.apkUrl))
            .setTitle("Magis Launcher v${remote.versionName}")
            .setDescription("Descargando actualización...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(File(dir, LauncherConfig.DOWNLOAD_FILE_NAME)))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        return manager.enqueue(request)
    }

    fun install(context: Context) {
        val apkFile = File(
            File(context.externalCacheDir, LauncherConfig.DOWNLOAD_DIR),
            LauncherConfig.DOWNLOAD_FILE_NAME,
        )
        if (!apkFile.exists()) {
            Toast.makeText(context, "Archivo de actualización no encontrado", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        context.startActivity(intent)
    }
}
