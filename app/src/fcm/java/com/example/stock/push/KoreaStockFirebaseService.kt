package com.example.stock.push

import com.example.stock.ServiceLocator
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class KoreaStockFirebaseService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val value = token.trim()
        if (value.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch {
            ServiceLocator.repository(applicationContext).registerDevice(value)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val title = message.notification?.title ?: message.data["title"] ?: "KoreaStockDash"
        val body = message.notification?.body ?: message.data["body"] ?: "새 알림"
        val type = message.data["type"] ?: "TRIGGER"
        val route = message.data["route"]?.trim()?.takeIf { it.isNotBlank() } ?: when (type) {
            "PREMARKET" -> "premarket"
            "EOD" -> "eod"
            else -> "alerts"
        }
        NotificationHelper.show(this, title, body, route)
        CoroutineScope(Dispatchers.IO).launch {
            ServiceLocator.repository(applicationContext).cacheIncomingAlert(type, title, body, message.data)
        }
    }
}
