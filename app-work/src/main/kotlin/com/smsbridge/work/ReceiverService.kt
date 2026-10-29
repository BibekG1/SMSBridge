package com.smsbridge.work

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.SocketException

class ReceiverService : Service() {
    companion object {
        private const val TAG = "SMSBridge-WorkReceiver"
        const val CHANNEL_ID = "smsbridge_work_service"
        const val NOTIF_ID = 2001
        const val PORT = 65432

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ReceiverService::class.java))
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
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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

    private fun CoroutineScope.startListening() {
        try {
            serverSocket = ServerSocket(PORT).also {
                it.reuseAddress = true
                Log.d(TAG, "Listening on port $PORT")
            }
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
            Log.d(TAG, "Server socket closed")
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

            mainHandler.post {
                try {
                    NotificationHelper.showMessageNotification(applicationContext, msg)
                    // Auto-copy OTP if detected
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
        scope.cancel()
        serverSocket?.close()
        Log.d(TAG, "ReceiverService stopped")
        super.onDestroy()
    }
}
