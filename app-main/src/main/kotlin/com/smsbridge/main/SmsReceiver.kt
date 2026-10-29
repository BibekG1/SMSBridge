package com.smsbridge.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SMSBridge-SmsReceiver"
        private const val DEFAULT_WORK_PROFILE_ID = 11
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        FilterManager.init(context)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Extract raw PDUs and forward directly via root to Work Profile
                val pdus = intent.extras?.get("pdus") as? Array<*>
                val format = intent.getStringExtra("format") ?: "3gpp"

                if (pdus != null && pdus.isNotEmpty()) {
                    for (pdu in pdus) {
                        if (pdu is ByteArray) {
                            val hexPdu = pdu.joinToString("") { "%02x".format(it) }
                            RootHelper.broadcastPduToWorkProfile(context, hexPdu, format, DEFAULT_WORK_PROFILE_ID)
                        }
                    }
                }

                // 2. Also forward over local socket to app-work (for UI log, notifications, 1-tap OTP copy)
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                messages?.forEach { sms ->
                    val sender = sms.displayOriginatingAddress ?: "Unknown"
                    val body = sms.messageBody ?: ""
                    Log.d(TAG, "SMS from $sender: $body")
                    val msg = BridgeMessage(sender = sender, body = body, sourceType = "SMS")
                    MessageStore.addMessage(msg)
                    BridgeService.sendMessage(context, msg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling SMS: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
