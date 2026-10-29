package com.smsbridge.work

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.auth.api.phone.SmsRetriever
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException

class ReceiverService : Service() {
    companion object {
        private const val TAG = "SMSBridge-WorkReceiver"
        const val CHANNEL_ID = "smsbridge_work_service"
        const val NOTIF_ID = 2001
        const val PORT = 65432

        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            try {
                val intent = Intent(context, ReceiverService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start ReceiverService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ReceiverService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop ReceiverService: ${e.message}")
            }
        }

        fun isRunning(context: Context): Boolean {
            if (isRunning) return true
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            @Suppress("DEPRECATION")
            for (service in am.getRunningServices(Int.MAX_VALUE)) {
                if (ReceiverService::class.java.name == service.service.className) {
                    return true
                }
            }
            return false
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        NotificationHelper.createChannel(this)
        createServiceChannel()
        val notif = buildServiceNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        scope.launch { startListening() }
        Log.d(TAG, "ReceiverService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        return START_STICKY
    }

    private fun CoroutineScope.startListening() {
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", PORT))
            }
            Log.d(TAG, "Listening on port $PORT")
            while (isActive) {
                val socket = serverSocket?.accept() ?: break
                launch {
                    try {
                        socket.use { s ->
                            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                            val line = reader.readLine()
                            if (!line.isNullOrBlank()) processJson(line)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Client error: ${e.message}")
                    }
                }
            }
        } catch (e: SocketException) {
            Log.d(TAG, "Server socket closed: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Server error: ${e.message}")
        }
    }

    private fun processJson(json: String) {
        Log.d(TAG, "Received: $json")
        try {
            val obj = JSONObject(json)
            val sender = obj.getString("sender")
            val body = obj.getString("body")
            val timestamp = obj.getLong("timestamp")
            val sourceType = obj.getString("sourceType")
            val otp = OtpDetector.extractOtp(body)

            val msg = ReceivedMessage(sender, body, timestamp, sourceType, otp)
            MessageStore.addMessage(msg)

            // 1. Write directly to native Android Telephony provider (content://sms/inbox) as unread
            writeToTelephonyProvider(applicationContext, msg)

            // 2. Broadcast Google Play Services SMS Retriever to all work profile apps (PhonePe, Freecharge, etc.)
            broadcastSmsRetriever(applicationContext, msg)

            mainHandler.post {
                try {
                    // 3. Post high-priority notification with copy actions
                    NotificationHelper.showMessageNotification(applicationContext, msg)

                    // 4. Auto-copy OTP if detected
                    otp?.let { code ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("OTP", code))
                        Log.d(TAG, "OTP auto-copied: $code")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error displaying notification or copying to clipboard: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
        }
    }

    private fun broadcastSmsRetriever(context: Context, msg: ReceivedMessage) {
        try {
            val status = Status(CommonStatusCodes.SUCCESS)

            // 1. Implicit broadcast to any app dynamically registered for SMS_RETRIEVED
            val implicitIntent = Intent(SmsRetriever.SMS_RETRIEVED_ACTION).apply {
                putExtra(SmsRetriever.EXTRA_SMS_MESSAGE, msg.body)
                putExtra(SmsRetriever.EXTRA_STATUS, status)
            }
            context.sendBroadcast(implicitIntent)

            // 2. Explicit broadcast targeted to each installed launcher app in the work profile
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcherIntent, 0)
            for (app in apps) {
                val pkg = app.activityInfo.packageName
                if (pkg != context.packageName) {
                    val targeted = Intent(SmsRetriever.SMS_RETRIEVED_ACTION).apply {
                        setPackage(pkg)
                        putExtra(SmsRetriever.EXTRA_SMS_MESSAGE, msg.body)
                        putExtra(SmsRetriever.EXTRA_STATUS, status)
                    }
                    context.sendBroadcast(targeted)
                }
            }
            Log.d(TAG, "Broadcasted SmsRetriever.SMS_RETRIEVED to ${apps.size} work profile apps")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast SMS_RETRIEVED: ${e.message}")
        }
    }

    private fun writeToTelephonyProvider(context: Context, msg: ReceivedMessage) {
        try {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, msg.sender)
                put(Telephony.Sms.BODY, msg.body)
                put(Telephony.Sms.DATE, msg.timestamp)
                put(Telephony.Sms.DATE_SENT, msg.timestamp)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            val uri = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            Log.d(TAG, "Inserted into Telephony SMS provider: $uri")

            context.contentResolver.notifyChange(Uri.parse("content://sms/"), null)
            context.contentResolver.notifyChange(Uri.parse("content://sms/inbox"), null)
            context.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
            context.contentResolver.notifyChange(Telephony.Sms.Inbox.CONTENT_URI, null)
            uri?.let { context.contentResolver.notifyChange(it, null) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write to SMS inbox: ${e.message}")
        }
    }

    private fun createServiceChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SMS Bridge Receiver Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildServiceNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Bridge (Work)")
            .setContentText("Ready — waiting for forwarded messages on port $PORT")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing serverSocket: ${e.message}")
        }
        serverSocket = null
        Log.d(TAG, "ReceiverService stopped")
        super.onDestroy()
    }
}
