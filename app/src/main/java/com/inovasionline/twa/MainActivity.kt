package com.inovasionline.twa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.messaging.FirebaseMessaging

@SuppressLint("InlinedApi")
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RIO_DEBUG"
        private const val PREF = "push"
        private const val KEY_SENT = "sent"
    }

    private lateinit var webView: WebView
    private lateinit var splashOverlay: View

    private var hasRequestedPermission = false
    private var openedSettings = false

    private val HOME_URL = "https://inovasionline.com"

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->

            hasRequestedPermission = true

            if (!isGranted) {
                checkNotificationPermission()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        splashOverlay = findViewById(R.id.splashOverlay)

        setupWebView()
        checkNotificationPermission()
        checkAndRefreshFcmTokenIfNeeded()
        PushRegistrar.ensureRegistered(this)
        handleIncomingUrl(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingUrl(intent)
    }

    override fun onResume() {
        super.onResume()

        if (openedSettings) {
            openedSettings = false
            checkNotificationPermission()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {

        webView.visibility = View.INVISIBLE
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {
            override fun onPageCommitVisible(view: WebView?, url: String?) {

                webView.postDelayed({

                    webView.alpha = 0f
                    webView.scaleX = 1.02f
                    webView.scaleY = 1.02f
                    webView.visibility = View.VISIBLE

                    webView.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(400)
                        .setInterpolator(FastOutSlowInInterpolator())
                        .start()

                    splashOverlay.animate()
                        .alpha(0f)
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .setDuration(450)
                        .setInterpolator(FastOutSlowInInterpolator())
                        .withEndAction {
                            splashOverlay.visibility = View.GONE
                            splashOverlay.scaleX = 1f
                            splashOverlay.scaleY = 1f
                        }
                        .start()

                }, 120)
            }
        }

        webView.loadUrl(HOME_URL)
    }

    private fun handleIncomingUrl(intent: Intent?) {

        val url = intent?.getStringExtra("open_url")
            ?: intent?.dataString

        if (!url.isNullOrBlank()) {
            Log.d(TAG, "Opening URL from intent: $url")
            webView.loadUrl(url)
        }
    }

    private fun checkAndRefreshFcmTokenIfNeeded() {

        val prefs = getSharedPreferences(PREF, MODE_PRIVATE)
        val sent = prefs.getBoolean(KEY_SENT, false)

        Log.d(TAG, "FCM check → sent flag = $sent")

        if (!sent) {

            Log.d(TAG, "Token belum terkirim. Deleting token...")

            FirebaseMessaging.getInstance().deleteToken()
                .addOnSuccessListener {

                    Log.d(TAG, "Token deleted successfully")

                    FirebaseMessaging.getInstance().token
                        .addOnSuccessListener { newToken ->
                            Log.d(TAG, "New token generated: $newToken")
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to get new token", e)
                        }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to delete token", e)
                }

        } else {
            Log.d(TAG, "Token already sent. No refresh needed.")
        }
    }

    private fun checkNotificationPermission() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) return

        val isPermanentDenied =
            hasRequestedPermission &&
                    !shouldShowRequestPermissionRationale(
                        Manifest.permission.POST_NOTIFICATIONS
                    )

        if (isPermanentDenied) {
            showSettingsDialog()
        } else {
            showPrePermissionDialog()
        }
    }

    private fun showPrePermissionDialog() {

        MaterialAlertDialogBuilder(this)
            .setTitle("Aktifkan Notifikasi")
            .setMessage(
                "Untuk melanjutkan, Anda perlu mengaktifkan notifikasi agar dapat menerima update pesanan dan informasi penting lainnya."
            )
            .setCancelable(false)
            .setPositiveButton("Aktifkan") { _, _ ->
                permissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
            .setNegativeButton("Keluar") { _, _ ->
                finish()
            }
            .show()
    }

    private fun showSettingsDialog() {

        MaterialAlertDialogBuilder(this)
            .setTitle("Notifikasi Diblokir")
            .setMessage(
                "Notifikasi telah diblokir. Untuk menerima update pesanan dan informasi penting, silakan aktifkan melalui Pengaturan Aplikasi."
            )
            .setCancelable(false)
            .setPositiveButton("Buka Pengaturan") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("Keluar") { _, _ ->
                finish()
            }
            .show()
    }

    private fun openAppSettings() {
        openedSettings = true
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri()
        )
        startActivity(intent)
    }
}
