package com.smsbridge.work

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger

object NotificationHelper {
    const val CHANNEL_ID = "smsbridge_work_messages"
    const val CHANNEL_NAME = "Forwarded Messages"
    private val notifCounter = AtomicInteger(0)

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Messages forwarded from main profile"
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun showMessageNotification(context: Context, msg: ReceivedMessage) {
        val notifId = 3000 + (notifCounter.getAndIncrement() % 3)

        // Title: show sender exactly as received (VM-BANK, +919XXXXXXXXX, etc.)
        // If OTP detected, prefix the title so it's immediately visible in shade
        val title = if (msg.otp != null) "OTP ${msg.otp} \u2190 ${msg.sender}" else msg.sender

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            // Short preview in collapsed notification
            .setContentText(msg.body)
            // Full untruncated body when expanded — exactly as received
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(msg.body)
                    .setBigContentTitle(title)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        // Action 1: Copy OTP (only when OTP detected)
        msg.otp?.let { otp ->
            val copyOtpIntent = Intent(context, CopyReceiver::class.java).apply {
                action = CopyReceiver.ACTION_COPY
                putExtra(CopyReceiver.EXTRA_TEXT, otp)
                putExtra(CopyReceiver.EXTRA_LABEL, "OTP")
            }
            val pendingCopyOtp = PendingIntent.getBroadcast(
                context,
                notifId * 10 + 1,
                copyOtpIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_menu_edit,
                "Copy OTP: $otp",
                pendingCopyOtp
            )
        }

        // Action 2: Copy Full SMS (always present — copies entire raw body including hashes)
        val copyFullIntent = Intent(context, CopyReceiver::class.java).apply {
            action = CopyReceiver.ACTION_COPY
            putExtra(CopyReceiver.EXTRA_TEXT, msg.body)
            putExtra(CopyReceiver.EXTRA_LABEL, "Full SMS")
        }
        val pendingCopyFull = PendingIntent.getBroadcast(
            context,
            notifId * 10 + 2,
            copyFullIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            android.R.drawable.ic_menu_share,
            "Copy Full SMS",
            pendingCopyFull
        )

        context.getSystemService(NotificationManager::class.java)
            .notify(notifId, builder.build())
    }
}
