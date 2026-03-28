package com.droid.remoteappinstaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra("package_name") ?: "unknown"
        val action = intent.getStringExtra("action") ?: "INSTALL"
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.d("RemoteInstaller", "$action SUCCESS: $packageName")
                // Notify UI
                sendBroadcastResult(context, packageName, action, true, null)
                // Report to server
                reportToServer(packageName, action, true, null)
            }
            else -> {
                Log.e("RemoteInstaller", "$action FAILED: $packageName — $message (status=$status)")
                sendBroadcastResult(context, packageName, action, false, message)
                reportToServer(packageName, action, false, message)
            }
        }
    }

    private fun sendBroadcastResult(
        context: Context,
        packageName: String,
        action: String,
        success: Boolean,
        message: String?
    ) {
        val resultIntent = Intent("com.droid.remoteappinstaller.INSTALL_RESULT").apply {
            putExtra("package_name", packageName)
            putExtra("action", action)
            putExtra("success", success)
            putExtra("message", message)
        }
        context.sendBroadcast(resultIntent)
    }

    private fun reportToServer(
        packageName: String,
        action: String,
        success: Boolean,
        message: String?
    ) {
        Thread {
            try {
                val client = OkHttpClient()
                val json = JSONObject().apply {
                    put("deviceId", InstallerService.DEVICE_ID)
                    put("packageName", packageName)
                    put("action", action)
                    put("success", success)
                    put("message", message ?: "")
                }
                val body = json.toString()
                    .toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("${InstallerService.SERVER_URL}/result")
                    .post(body)
                    .build()
                client.newCall(request).execute()
                Log.d("RemoteInstaller", "Result reported to server")
            } catch (e: Exception) {
                Log.e("RemoteInstaller", "Report error: ${e.message}")
            }
        }.start()
    }
}