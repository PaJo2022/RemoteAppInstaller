package com.droid.remoteappinstaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.droid.remoteappinstaller.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var resultReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerResultReceiver()
        startForegroundService(Intent(this, InstallerService::class.java))

        binding.installButton.setOnClickListener {
            val packageName = binding.packageNameInput.text.toString().trim()
            val apkUrl = binding.apkUrlInput.text.toString().trim()

            if (packageName.isEmpty() || apkUrl.isEmpty()) {
                updateStatus("Enter package name and APK URL")
                return@setOnClickListener
            }

            updateStatus("Installing $packageName...")
            log("URL: $apkUrl")

            val intent = Intent(this, InstallerService::class.java).apply {
                putExtra("apk_url", apkUrl)
                putExtra("package_name", packageName)
            }
            startForegroundService(intent)
        }

        binding.uninstallButton.setOnClickListener {
            val packageName = binding.packageNameInput.text.toString().trim()
            if (packageName.isEmpty()) {
                updateStatus("Enter a package name")
                return@setOnClickListener
            }
            updateStatus("Uninstalling $packageName...")
            log("Uninstalling: $packageName")

            val intent = Intent(this, InstallerService::class.java).apply {
                putExtra("uninstall_package", packageName)
            }
            startForegroundService(intent)
        }

        binding.listAppsButton.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val apps = packageManager
                    .getInstalledPackages(PackageManager.GET_META_DATA)
                    .map { it.packageName }
                    .filter {
                        !it.startsWith("com.android") &&
                                !it.startsWith("android") &&
                                !it.startsWith("com.google")
                    }
                    .sorted()

                withContext(Dispatchers.Main) {
                    updateStatus("Found ${apps.size} apps")
                    log("--- Installed apps ---")
                    apps.forEach { log(it) }
                }
            }
        }
    }

    private fun registerResultReceiver() {
        resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val packageName = intent.getStringExtra("package_name")
                val action = intent.getStringExtra("action")
                val success = intent.getBooleanExtra("success", false)
                val message = intent.getStringExtra("message")

                if (success) {
                    updateStatus("$action SUCCESS: $packageName")
                    log("✓ $packageName installed successfully")
                } else {
                    updateStatus("$action FAILED: $message")
                    log("✗ Failed: $message")
                }
            }
        }

        registerReceiver(
            resultReceiver,
            IntentFilter("com.droid.remoteappinstaller.INSTALL_RESULT"),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        resultReceiver?.let { unregisterReceiver(it) }
    }

    private fun updateStatus(message: String) {
        binding.statusText.text = "Status: $message"
        Log.d("RemoteInstaller", message)
    }

    private fun log(message: String) {
        val current = binding.logText.text.toString()
        binding.logText.text = "$current\n$message"
    }
}