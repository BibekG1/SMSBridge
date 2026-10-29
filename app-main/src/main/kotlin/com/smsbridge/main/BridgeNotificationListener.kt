package com.smsbridge.main

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class BridgeNotificationListener : NotificationListenerService() {
    companion object {
        private const val TAG = "SMSBridge-NotifListener"

        // Packages that handle SMS directly. We ignore their notifications to avoid duplicate loops
        // since SMS is already captured natively by SmsReceiver.
        private val SMS_APP_PACKAGES = setOf(
            "com.google.android.apps.messaging",
            "com.truecaller",
            "com.android.mms",
            "com.samsung.android.messaging",
            "cn.nubia.mms",
            "com.android.providers.telephony"
        )

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val cn = ComponentName(context, BridgeNotificationListener::class.java)
            return flat.contains(cn.flattenToString())
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return // skip own notifications
        if (SMS_APP_PACKAGES.contains(pkg)) {
            Log.d(TAG, "Ignoring notification from SMS app $pkg to prevent feedback loop")
            return
        }
        if (!FilterManager.shouldForward(pkg)) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

        val body = bigText?.takeIf { it.isNotBlank() } ?: text
        if (body.isBlank()) return

        val sender = if (title.isNotBlank()) "$title ($pkg)" else pkg
        Log.d(TAG, "Notification from $pkg: $body")

        val msg = BridgeMessage(sender = sender, body = body, sourceType = "NOTIFICATION")
        MessageStore.addMessage(msg)
        BridgeService.sendMessage(applicationContext, msg)
    }
}
