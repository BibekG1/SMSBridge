package com.smsbridge.main

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

object RootHelper {
    private const val TAG = "SMSBridge-RootHelper"
    private val isRootAvailable = AtomicBoolean(false)
    private val rootChecked = AtomicBoolean(false)

    fun isRootGranted(): Boolean {
        if (rootChecked.get()) return isRootAvailable.get()
        return checkRoot()
    }

    fun checkRoot(): Boolean {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.waitFor()
            val available = line != null && line.contains("uid=0")
            isRootAvailable.set(available)
            rootChecked.set(true)
            Log.d(TAG, "Root check result: $available (output: $line)")
            return available
        } catch (e: Exception) {
            Log.w(TAG, "Root check failed: ${e.message}")
            isRootAvailable.set(false)
            rootChecked.set(true)
            return false
        }
    }

    suspend fun executeRoot(command: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Executing root command: $command")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exitCode = process.waitFor()
            Log.d(TAG, "Root command finished with exit code: $exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Error executing root command: ${e.message}", e)
            false
        }
    }

    suspend fun broadcastPduToWorkProfile(context: Context, hexPdu: String, format: String, targetUserId: Int = 11) {
        withContext(Dispatchers.IO) {
            try {
                val apkPath = context.packageCodePath
                val cmd = "app_process -Djava.class.path=$apkPath /system/bin com.smsbridge.main.RootSmsBroadcaster $hexPdu $format $targetUserId"
                executeRoot(cmd)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to broadcast PDU to work profile: ${e.message}", e)
            }
        }
    }
}

