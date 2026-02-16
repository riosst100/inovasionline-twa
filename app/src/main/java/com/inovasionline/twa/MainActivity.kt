package com.inovasionline.twa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

import android.webkit.CookieManager
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

import kotlinx.coroutines.withContext

import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.credentials.*
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@SuppressLint("InlinedApi")
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RIO_DEBUG"
        private const val PREF = "push"
        private const val KEY_SENT = "sent"
        private const val PREF_LOGIN = "login_pref"
        private const val KEY_LOGGED_IN = "logged_in"
    }

    private lateinit var webView: WebView
    private lateinit var splashOverlay: View
    private lateinit var credentialManager: CredentialManager

    private var hasRequestedPermission = false
    private var shouldAskPermissionAfterLogin = false
    private var loginDialog: androidx.appcompat.app.AlertDialog? = null


    private val loginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ===== FIX TAMBAHAN =====
    private var isLoginInProgress = false
    // =========================

    private val HOME_URL = "https://inovasionline.com"
    private val BACKEND_URL =
        "https://api.inovasionline.com/auth/google/native"

    private val WEB_CLIENT_ID =
        "962366033380-169mr3kth1sl22fh94ek79ioalj7us4b.apps.googleusercontent.com"

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            hasRequestedPermission = true
            if (!isGranted) checkNotificationPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        splashOverlay = findViewById(R.id.splashOverlay)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        credentialManager = CredentialManager.create(this)

        setupWebView()
        checkLoginFirst()
        checkAndRefreshFcmTokenIfNeeded()
        PushRegistrar.ensureRegistered(this)
        handleIncomingUrl(intent)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        val loggedIn = getSharedPreferences(PREF_LOGIN, MODE_PRIVATE)
            .getBoolean(KEY_LOGGED_IN, false)

        Log.d(TAG, "onResume -> loggedIn=$loggedIn isLoginInProgress=$isLoginInProgress")

        if (!loggedIn && !isLoginInProgress) {
            showGoogleLoginDialog()
        }
    }

    private fun showLoading() {
        splashOverlay.visibility = View.VISIBLE
        splashOverlay.alpha = 1f
    }

    private fun hideLoadingSmooth() {
        splashOverlay.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                splashOverlay.visibility = View.GONE
            }
            .start()
    }

    // ================= LOGIN =================

    private fun checkLoginFirst() {
        val prefs = getSharedPreferences(PREF_LOGIN, MODE_PRIVATE)
        val loggedIn = prefs.getBoolean(KEY_LOGGED_IN, false)
        if (!loggedIn) showGoogleLoginDialog()
    }

    private fun showGoogleLoginDialog() {

        if (loginDialog?.isShowing == true) return

        loginDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Login Diperlukan")
            .setMessage("Untuk melanjutkan, silakan login menggunakan akun Google Anda.")
            .setCancelable(false)
            .setPositiveButton("Login dengan Google") { dialog, _ ->
                dialog.dismiss()   // <<< PENTING
                showGoogleLoginPopup()
            }
            .setNegativeButton("Keluar") { _, _ ->
                finish()
            }
            .create()

        loginDialog?.show()
    }


    private fun showGoogleLoginPopup() {

        if (isLoginInProgress) return

        isLoginInProgress = true

        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        loginScope.launch {

            try {

                val result = credentialManager.getCredential(
                    request = request,
                    context = this@MainActivity
                )

                val credential = result.credential

                if (credential is CustomCredential &&
                    credential.type ==
                    GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {

                    val googleIdTokenCredential =
                        GoogleIdTokenCredential.createFrom(credential.data)

                    val idToken = googleIdTokenCredential.idToken

                    sendTokenToBackend(idToken)

                } else {
                    resetLoginState()
                }


            } catch (e: GetCredentialCancellationException) {

                Log.e(TAG, "Login cancelled by user", e)
                resetLoginState()

                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Login dibatalkan",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()

                    showGoogleLoginDialog()
                }

            } catch (e: GetCredentialException) {

                Log.e(TAG, "Credential error", e)
                resetLoginState()

                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Terjadi kesalahan login",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()

                    showGoogleLoginDialog()
                }

            } catch (e: Exception) {

                if (e is java.util.concurrent.CancellationException) {
                    Log.d(TAG, "Login coroutine cancelled normally")
                    return@launch
                }

                Log.e(TAG, "Unknown login error", e)
                resetLoginState()

                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        "Login gagal, coba lagi",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()

                    showGoogleLoginDialog()
                }
            }


        }
        }

    private fun resetLoginState() {
        isLoginInProgress = false
        hideLoadingSmooth()

        loginDialog?.dismiss()
        loginDialog = null
    }


    // ================= BACKEND =================

    private fun sendTokenToBackend(idToken: String) {

        loginScope.launch(Dispatchers.IO) {

            try {

                val url = URL(BACKEND_URL)
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val body = JSONObject().apply {
                    put("idToken", idToken)
                }.toString()

                connection.outputStream.use {
                    it.write(body.toByteArray())
                }

                val responseCode = connection.responseCode
                val response =
                    connection.inputStream.bufferedReader().readText()

                if (responseCode == 200) {

                    val json = JSONObject(response)
                    val accessToken = json.getString("accessToken")

                    getSharedPreferences(PREF_LOGIN, MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_LOGGED_IN, true)
                        .apply()

                    withContext(Dispatchers.Main) {
                        shouldAskPermissionAfterLogin = true
                        openBridgeLogin(accessToken)
                    }

                } else {
                    throw Exception("Backend error: $responseCode")
                }

            } catch (e: Exception) {

                Log.e(TAG, "Backend error", e)

                withContext(Dispatchers.Main) {
                    resetLoginState()
                }
            }
        }
    }

    private fun openBridgeLogin(accessToken: String) {

        val bridgeUrl =
            "https://api.inovasionline.com/auth/mobile-bridge?token=$accessToken"

        webView.loadUrl(bridgeUrl)

        webView.postDelayed({
            webView.loadUrl(HOME_URL)
            isLoginInProgress = false
        }, 1000)
    }

    // ================= WEBVIEW =================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {

        webView.visibility = View.INVISIBLE
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?
            ) {
                showLoading()
            }

            override fun onPageCommitVisible(
                view: WebView?,
                url: String?
            ) {

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

                    hideLoadingSmooth()

                    if (shouldAskPermissionAfterLogin) {
                        shouldAskPermissionAfterLogin = false
                        webView.postDelayed({
                            checkNotificationPermission()
                        }, 3000)
                    }

                }, 120)
            }
        }

        webView.loadUrl(HOME_URL)
    }

    // ================= PERMISSION =================

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

        if (isPermanentDenied) showSettingsDialog()
        else showPrePermissionDialog()
    }

    private fun showPrePermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Aktifkan Notifikasi")
            .setMessage("Untuk melanjutkan, aktifkan notifikasi.")
            .setCancelable(false)
            .setPositiveButton("Aktifkan") { _, _ ->
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton("Keluar") { _, _ ->
                finish()
            }
            .show()
    }

    private fun showSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Notifikasi Diblokir")
            .setMessage("Silakan aktifkan melalui Pengaturan.")
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
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri()
        )
        startActivity(intent)
    }

    private fun handleIncomingUrl(intent: Intent?) {
        val url = intent?.getStringExtra("open_url")
            ?: intent?.dataString
        if (!url.isNullOrBlank()) webView.loadUrl(url)
    }

    private fun checkAndRefreshFcmTokenIfNeeded() {
        val prefs = getSharedPreferences(PREF, MODE_PRIVATE)
        val sent = prefs.getBoolean(KEY_SENT, false)
        if (!sent) FirebaseMessaging.getInstance().deleteToken()
    }
}
