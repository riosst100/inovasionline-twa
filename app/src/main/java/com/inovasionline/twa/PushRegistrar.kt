package com.inovasionline.twa

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

object PushRegistrar {

    private const val TAG = "RIO_DEBUG"

    private const val PREF = "push"
    private const val KEY_TOKEN = "fcm_token"
    private const val KEY_SENT = "sent"
    private const val KEY_CODE = "bind_code"

    private const val REGISTER_URL =
        "https://api.inovasionline.com/auth/push/register-device"

    private val registering = AtomicBoolean(false)
    private val opening = AtomicBoolean(false)

    fun ensureRegistered(context: Context) {

        if (!registering.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->

                val oldToken = prefs.getString(KEY_TOKEN, null)
                val sent = prefs.getBoolean(KEY_SENT, false)

                if (token == oldToken && sent) {
                    registering.set(false)
                    return@addOnSuccessListener
                }

                sendToBackend(token) { success, code ->

                    registering.set(false)

                    if (success && !code.isNullOrBlank()) {

                        prefs.edit()
                            .putString(KEY_TOKEN, token)
                            .putBoolean(KEY_SENT, true)
                            .putString(KEY_CODE, code)
                            .apply()

                        if (!opening.compareAndSet(false, true)) return@sendToBackend

                        val url =
                            "https://inovasionline.com/bind-device?code=$code"

                        val intent = Intent(appContext, MainActivity::class.java).apply {
                            putExtra("open_url", url)
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                            )
                        }

                        Handler(Looper.getMainLooper()).post {
                            try {
                                appContext.startActivity(intent)
                                opening.set(false)
                            } catch (e: Exception) {
                                opening.set(false)
                                Log.e(TAG, "Failed to open bind page", e)
                            }
                        }

                    } else {
                        prefs.edit()
                            .putBoolean(KEY_SENT, false)
                            .apply()
                    }
                }
            }
            .addOnFailureListener {
                registering.set(false)
            }
    }

    private fun sendToBackend(
        token: String,
        cb: (Boolean, String?) -> Unit
    ) {

        val client = OkHttpClient()

        val json = JSONObject()
        json.put("token", token)

        val body = json.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(REGISTER_URL)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                cb(false, null)
            }

            override fun onResponse(call: Call, response: Response) {

                response.use {

                    val raw = try {
                        it.body?.string()
                    } catch (_: Exception) {
                        null
                    }

                    if (!it.isSuccessful || raw.isNullOrEmpty()) {
                        cb(false, null)
                        return
                    }

                    val code = try {
                        JSONObject(raw).optString("code", "")
                    } catch (_: Exception) {
                        ""
                    }

                    if (code.isBlank()) cb(false, null)
                    else cb(true, code)
                }
            }
        })
    }
}
