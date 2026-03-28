package com.droid.remoteappinstaller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

class InstallerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient()

    companion object {
        private const val CHANNEL_ID = "remote_installer"
        private const val NOTIF_ID = 1
        private const val POLL_INTERVAL = 30_000L // 30 seconds for testing
        const val SERVER_URL = "" //Enter your ipconfig/server url
        val DEVICE_ID = android.os.Build.SERIAL.ifEmpty { "emulator-001" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        val apkUrl = intent?.getStringExtra("apk_url")
        val packageName = intent?.getStringExtra("package_name")
        val uninstallPackage = intent?.getStringExtra("uninstall_package")

        when {
            uninstallPackage != null -> uninstallApp(uninstallPackage)
            apkUrl != null && packageName != null -> scope.launch { installApp(packageName, apkUrl) }
            else -> startPolling()
        }

        return START_STICKY
    }

    // ── Polling ───────────────────────────────────────────────────────────
    private fun startPolling() {
        scope.launch {
            while (true) {
                Log.d("RemoteInstaller", "Polling server for commands...")
                try {
                    checkForUpdates()
                } catch (e: Exception) {
                    Log.e("RemoteInstaller", "Poll error: ${e.message}")
                }
                delay(POLL_INTERVAL)
            }
        }
    }

    private suspend fun checkForUpdates() {
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$SERVER_URL/commands?deviceId=$DEVICE_ID")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e("RemoteInstaller", "Server error: ${response.code}")
                    return@withContext
                }

                val body = response.body?.string() ?: return@withContext
                Log.d("RemoteInstaller", "Server response: $body")

                val json = JSONObject(body)
                val commands = json.getJSONArray("commands")

                if (commands.length() == 0) {
                    Log.d("RemoteInstaller", "No pending commands")
                    return@withContext
                }

                for (i in 0 until commands.length()) {
                    val command = commands.getJSONObject(i)
                    val type = command.getString("type")
                    val packageName = command.getString("packageName")

                    when (type) {
                        "INSTALL", "UPDATE" -> {
                            val apkUrl = command.getString("apkUrl")
                            Log.d("RemoteInstaller", "Command: $type $packageName from $apkUrl")
                            installApp(packageName, apkUrl)
                        }
                        "UNINSTALL" -> {
                            Log.d("RemoteInstaller", "Command: UNINSTALL $packageName")
                            uninstallApp(packageName)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("RemoteInstaller", "checkForUpdates error: ${e.message}")
            }
        }
    }

    // ── Install ───────────────────────────────────────────────────────────
    suspend fun installApp(packageName: String, apkUrl: String) {
        Log.d("RemoteInstaller", "Installing $packageName from $apkUrl")
        try {
            val apkFile = downloadApk(apkUrl, packageName)
            if (apkFile == null) {
                Log.e("RemoteInstaller", "Download failed")
                reportResult(packageName, "INSTALL", false, "Download failed")
                return
            }
            Log.d("RemoteInstaller", "Downloaded: ${apkFile.length() / 1024}KB")
            silentInstall(packageName, apkFile)
        } catch (e: Exception) {
            Log.e("RemoteInstaller", "Install error: ${e.message}")
            reportResult(packageName, "INSTALL", false, e.message)
        }
    }

    // ── Download ──────────────────────────────────────────────────────────
    private suspend fun downloadApk(url: String, packageName: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                if (url.startsWith("file://")) {
                    val path = url.removePrefix("file://")
                    val source = File(path)
                    val dest = File(cacheDir, "$packageName.apk")
                    source.copyTo(dest, overwrite = true)
                    return@withContext dest
                }

                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e("RemoteInstaller", "HTTP error: ${response.code}")
                    return@withContext null
                }
                val apkFile = File(cacheDir, "$packageName.apk")
                response.body?.byteStream()?.use { input ->
                    apkFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                apkFile
            } catch (e: Exception) {
                Log.e("RemoteInstaller", "Download error: ${e.message}")
                null
            }
        }
    }

    // ── Silent install ────────────────────────────────────────────────────
    private fun silentInstall(packageName: String, apkFile: File) {
        val packageInstaller = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(packageName)
        }

        val sessionId = packageInstaller.createSession(params)
        Log.d("RemoteInstaller", "Session created: $sessionId")

        try {
            val session = packageInstaller.openSession(sessionId)
            session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                apkFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                session.fsync(output)
            }

            val intent = Intent(this, InstallResultReceiver::class.java).apply {
                putExtra("package_name", packageName)
                putExtra("action", "INSTALL")
            }
            val pendingIntent = PendingIntent.getBroadcast(
                this, sessionId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pendingIntent.intentSender)
            session.close()
            Log.d("RemoteInstaller", "Install committed for $packageName")

        } catch (e: Exception) {
            packageInstaller.abandonSession(sessionId)
            Log.e("RemoteInstaller", "Session failed: ${e.message}")
            reportResult(packageName, "INSTALL", false, e.message)
        } finally {
            apkFile.delete()
        }
    }

    // ── Uninstall ─────────────────────────────────────────────────────────
    fun uninstallApp(packageName: String) {
        Log.d("RemoteInstaller", "Uninstalling: $packageName")
        val packageInstaller = packageManager.packageInstaller
        val intent = Intent(this, InstallResultReceiver::class.java).apply {
            putExtra("package_name", packageName)
            putExtra("action", "UNINSTALL")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, packageName.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        packageInstaller.uninstall(packageName, pendingIntent.intentSender)
    }

    // ── Report result to server ───────────────────────────────────────────
    fun reportResult(packageName: String, action: String, success: Boolean, message: String?) {
        scope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("deviceId", DEVICE_ID)
                    put("packageName", packageName)
                    put("action", action)
                    put("success", success)
                    put("message", message ?: "")
                }
                val body = json.toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("$SERVER_URL/result")
                    .post(body)
                    .build()
                client.newCall(request).execute()
                Log.d("RemoteInstaller", "Result reported to server: $action $packageName")
            } catch (e: Exception) {
                Log.e("RemoteInstaller", "Report error: ${e.message}")
            }
        }
    }

    // ── Notification ──────────────────────────────────────────────────────
    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID, "Remote App Installer",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Installer")
            .setContentText("Monitoring for app updates")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}