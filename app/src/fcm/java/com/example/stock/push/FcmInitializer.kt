package com.example.stock.push

import android.content.Context
import android.util.Log
import com.example.stock.ServiceLocator
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object FcmInitializer {
    private const val TAG = "FcmInitializer"

    @JvmStatic
    fun init(context: Context) {
        val app = FirebaseApp.initializeApp(context)
        if (app == null) {
            Log.w(TAG, "FirebaseApp initialize returned null (google-services config missing?)")
            return
        }
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                if (token.isBlank()) {
                    Log.w(TAG, "FCM token fetch success but token is blank")
                    return@addOnSuccessListener
                }
                CoroutineScope(Dispatchers.IO).launch {
                    ServiceLocator.repository(context).registerDevice(token)
                }
            }
            .addOnFailureListener { err ->
                Log.w(TAG, "FCM token fetch failed: ${err.javaClass.simpleName}")
            }
    }
}
