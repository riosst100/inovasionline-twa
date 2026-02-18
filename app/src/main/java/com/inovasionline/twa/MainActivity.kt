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
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.*
import com.google.android.gms.common.api.ApiException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

@SuppressLint("InlinedApi")
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RIO_DEBUG"
        private const val PREF_LOGIN = "login_pref"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val PREF = "push"
        private const val KEY_SENT = "sent"
    }

    private lateinit var webView: WebView
    private lateinit var splashOverlay: View
    private lateinit var googleSignInClient: GoogleSignInClient

    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var isLoginInProgress = false
    private var hasRequestedNotification = false
    private var isLoginDelayRunning = false
    private var isRequestingPermission = false


    private var notificationDialog: androidx.appcompat.app.AlertDialog? = null

    private var loginDialog: androidx.appcompat.app.AlertDialog? = null
    private var settingsDialog: androidx.appcompat.app.AlertDialog? = null



    private val HOME_URL = "https://inovasionline.com"
    private val BACKEND_URL =
        "https://api.inovasionline.com/auth/google/native"

    private val WEB_CLIENT_ID =
        "962366033380-169mr3kth1sl22fh94ek79ioalj7us4b.apps.googleusercontent.com"

    // ================= GOOGLE LOGIN RESULT =================

    private val googleLoginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->

            isLoginInProgress = false

            if (result.resultCode != RESULT_OK) {
                val string = "cancelled"
                handleLoginCancelled()
                Log.e(TAG, "Login $string: ${result.resultCode}")
                return@registerForActivityResult
            }

            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)

            try {
                val account = task.getResult(ApiException::class.java)
                val idToken = account.idToken

                if (idToken != null) {
                    sendTokenToBackend(idToken)
                } else {
                    Log.e(TAG, "ID Token null")
                }

            } catch (e: ApiException) {
                Log.e(TAG, "Google Sign-In failed: ${e.statusCode}")
            }
        }


    private fun handleLoginCancelled() {
        isLoginInProgress = false

        // munculkan popup lagi
        showGoogleLoginDialog()
    }


    // ================= PERMISSION RESULT =================

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->

            isRequestingPermission = false
            hasRequestedNotification = true

            if (isGranted) {
                notificationDialog?.dismiss()
                notificationDialog = null
            } else {
                checkNotificationPermission()
            }
        }



    // ================= LIFECYCLE =================

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        swipeRefresh = findViewById(R.id.swipeRefresh)

        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }

        webView = findViewById(R.id.webView)
        webView.settings.setDomStorageEnabled(true)
        WebView.setWebContentsDebuggingEnabled(true)
        webView.settings.mixedContentMode =
            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.settings.setDatabaseEnabled(true)
        webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            swipeRefresh.isEnabled = scrollY == 0
        }

        swipeRefresh.setColorSchemeResources(
            R.color.primary
        )

        splashOverlay = findViewById(R.id.splashOverlay)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        cookieManager.flush();

        val cookies = CookieManager.getInstance().getCookie("https://inovasionline.com")
        Log.d(TAG, "Cookies: $cookies")

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        setupWebView()

        checkLoginFirst()
        checkAndRefreshFcmTokenIfNeeded()
        PushRegistrar.ensureRegistered(this)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()

        val loggedIn = getSharedPreferences(PREF_LOGIN, MODE_PRIVATE)
            .getBoolean(KEY_LOGGED_IN, false)

        if (loggedIn && !isRequestingPermission) {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED

                if (granted) {
                    notificationDialog?.dismiss()
                    settingsDialog?.dismiss()
                    notificationDialog = null
                    settingsDialog = null
                    return
                }
            }

            delayNotificationPermission()
        }
    }


    // ================= LOGIN =================

    private fun checkLoginFirst() {
        val loggedIn = getSharedPreferences(PREF_LOGIN, MODE_PRIVATE)
            .getBoolean(KEY_LOGGED_IN, false)

        if (!loggedIn) delayLoginDialog()
    }

    private fun showGoogleLoginDialog() {

        if (loginDialog?.isShowing == true) return
        if (isLoginInProgress) return

        loginDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Login Diperlukan")
            .setMessage("Silakan login menggunakan akun Google Anda.")
            .setCancelable(false)
            .setPositiveButton("Login dengan Google") { dialog, _ ->
                dialog.dismiss()
                startGoogleLogin()
            }
            .setNegativeButton("Keluar") { _, _ ->
                finish()
            }
            .create()

        loginDialog?.show()
    }

    private fun startGoogleLogin() {

        if (isLoginInProgress) return

        isLoginInProgress = true

//        googleSignInClient.signOut()

        val signInIntent = googleSignInClient.signInIntent
        googleLoginLauncher.launch(signInIntent)
    }

    private fun handleLoginFailed(message: String) {
        isLoginInProgress = false
        hideLoading()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        showGoogleLoginDialog()
    }

    // ================= BACKEND =================

    private fun sendTokenToBackend(idToken: String) {

        showLoading()

        lifecycleScope.launch(Dispatchers.IO) {

            try {

                val url = URL(BACKEND_URL)
                val connection = url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

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
                        .commit()

                    withContext(Dispatchers.Main) {
                        openBridgeLogin(accessToken)
                    }

                } else {
                    throw Exception("Backend error")
                }

            } catch (e: Exception) {

                Log.e(TAG, "Backend error", e)

                withContext(Dispatchers.Main) {
                    handleLoginFailed("Login gagal")
                }
            }
        }
    }

    private fun openBridgeLogin(accessToken: String) {

        val bridgeUrl =
            "https://api.inovasionline.com/auth/mobile-bridge?token=$accessToken"

        webView.loadUrl(bridgeUrl)

        webView.postDelayed({

            // 🔥 FLUSH COOKIE WAJIB
//            CookieManager.getInstance().flush()

            isLoginInProgress = false
            hideLoading()

            delayNotificationPermission()

//            webView.loadUrl(HOME_URL)

        }, 1200) // kasih delay sedikit lebih lama
    }

    // ================= NOTIFICATION =================

    private fun checkNotificationPermission() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            notificationDialog?.dismiss()
            notificationDialog = null
            return
        }

        val permanentlyDenied =
            hasRequestedNotification &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)

        if (permanentlyDenied) {
            showSettingsDialog()
        } else {
            showPermissionDialog()
        }
    }





    private fun showPermissionDialog() {

        if (notificationDialog?.isShowing == true) return

        notificationDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Aktifkan Notifikasi")
            .setMessage("Notifikasi wajib diaktifkan.")
            .setCancelable(false)
            .setPositiveButton("Aktifkan") { _, _ ->
                isRequestingPermission = true
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .create()

        notificationDialog?.show()
    }


    private fun showSettingsDialog() {

        if (settingsDialog?.isShowing == true) return

        settingsDialog = MaterialAlertDialogBuilder(this)
            .setTitle("Notifikasi Diblokir")
            .setMessage("Silakan aktifkan melalui Pengaturan.")
            .setCancelable(false)
            .setPositiveButton("Buka Pengaturan") { _, _ ->
                notificationDialog?.dismiss()
                notificationDialog = null
                openAppSettings()
            }
            .create()

        settingsDialog?.show()
    }


    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            "package:$packageName".toUri()
        )
        startActivity(intent)
    }

    // ================= WEBVIEW =================

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {

        webView.visibility = View.INVISIBLE
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.apply {

            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            // 🔥 Gunakan default browser cache behavior
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

            useWideViewPort = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = true

            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                swipeRefresh.isRefreshing = false

                if (url == "https://inovasionline.com/" ||
                    url == "https://inovasionline.com") {

                    Log.d("COOKIE_DEBUG", "Homepage loaded. Flushing cookie.")
                    CookieManager.getInstance().flush()
                }
            }

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
                webView.visibility = View.VISIBLE
                hideLoading()
            }
        }

        webView.loadUrl(HOME_URL)

        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {

                    val currentUrl = webView.url ?: ""
                    val isHome = currentUrl == "https://inovasionline.com/" ||
                            currentUrl == "https://inovasionline.com"

                    if (isHome) {
                        showExitConfirmation()
                    } else if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        showExitConfirmation()
                    }
                }
            }
        )

    }

    private fun showExitConfirmation() {

        MaterialAlertDialogBuilder(this)
            .setTitle("Keluar Aplikasi")
            .setMessage("Apakah Anda yakin ingin keluar?")
            .setCancelable(true)
            .setPositiveButton("Ya") { _, _ ->
                finish()
            }
            .setNegativeButton("Tidak", null)
            .show()
    }

    // ================= UI =================

    private fun showLoading() {
        splashOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        splashOverlay.visibility = View.GONE
    }

    private fun checkAndRefreshFcmTokenIfNeeded() {
        val prefs = getSharedPreferences(PREF, MODE_PRIVATE)
        val sent = prefs.getBoolean(KEY_SENT, false)
        if (!sent) FirebaseMessaging.getInstance().deleteToken()
    }

    private fun delayNotificationPermission() {
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1000) // 3 detik (ubah sesuai kebutuhan)
            checkNotificationPermission()
        }
    }

    private fun delayLoginDialog() {

        if (isLoginDelayRunning || isRequestingPermission) return

        isLoginDelayRunning = true

        lifecycleScope.launch {
            kotlinx.coroutines.delay(1000)

            isLoginDelayRunning = false

            if (!isRequestingPermission) {
                showGoogleLoginDialog()
            }
        }
    }



}
