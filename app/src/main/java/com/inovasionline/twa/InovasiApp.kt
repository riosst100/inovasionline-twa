package com.inovasionline.twa

import android.app.Application
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging

class InovasiApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val prefs = getSharedPreferences("push", MODE_PRIVATE)
        val sent = prefs.getBoolean("sent", false)

        Log.d("InovasiApp", "onCreate() called")
        Log.d("InovasiApp", "push.sent = $sent")

        // 🔧 testing only
        if (!sent) {

            Log.d("InovasiApp", "FCM token not sent yet -> deleting token for testing")

            FirebaseMessaging.getInstance().deleteToken()
                .addOnSuccessListener {
                    Log.d("InovasiApp", "FCM token deleted")
                }
                .addOnFailureListener { e ->
                    Log.e("InovasiApp", "Failed to delete FCM token", e)
                }
        }

        Log.d("InovasiApp", "Calling PushRegistrar.ensureRegistered()")
        PushRegistrar.ensureRegistered(this)
    }
}
