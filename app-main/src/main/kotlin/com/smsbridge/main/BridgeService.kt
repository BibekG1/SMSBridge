package com.smsbridge.main

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.PrintWriter
import java.net.ConnectException
import java.net.Socket

class BridgeService : Service() {
    companion object {
        private const val TAG = "SMSBridge-BridgeService"
        private const val CHANNEL_ID = "smsbridge_main_service"
        private const val NOTIF_ID = 1001
        private const val ACTION_SEND = "com.smsbridge.main.ACTION_SEND"
        private const val EXTRA_JSON = "json"

        fun sendMessage(context: Context, msg: BridgeMessage) {
            val json = JSONObject().apply {
                put("id", msg.id)
                put("sender", msg.sender)
                put("body", msg.body)
                put("timestamp", msg.timestamp)
                put("sourceType", msg.sourceType)
            }.toString()
            val intent = Intent(context, BridgeService::class.java).apply {
                action = ACTION_SEND
                putExtra(EXTRA_JSON, json)
            }
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start BridgeService: ${e.message}")
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        FilterManager.init(this)
        createNotificationChannel()
        val notif = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SEND) {
            val json = intent.getStringExtra(EXTRA_JSON) ?: return START_STICKY
            scope.launch { sendViaSocket(json) }
        }
        return START_STICKY
    }

    private suspend fun sendViaSocket(json: String) {
        val port = FilterManager.bridgePort
        repeat(2) { attempt ->
            try {
                Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 3000
                    PrintWriter(socket.getOutputStream(), true).use { writer ->
                        writer.println(json)
                    }
                }
                Log.d(TAG, "Sent via socket port $port")
                return
            } catch (e: ConnectException) {
                Log.w(TAG, "Work profile app not listening on port $port (attempt ${attempt + 1})")
                if (attempt == 0) delay(1000)
            } catch (e: Exception) {
                Log.e(TAG, "Socket error: ${e.message}")
                return
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "SMS Bridge Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "SMS Bridge is actively running" }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SMS Bridge Active")
            .setContentText("Forwarding SMS & notifications to work profile")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
