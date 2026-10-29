package com.smsbridge.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SMSBridge-SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        FilterManager.init(context)
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        messages.forEach { sms ->
            val sender = sms.displayOriginatingAddress ?: "Unknown"
            val body = sms.messageBody ?: ""
            Log.d(TAG, "SMS from $sender: $body")
            val msg = BridgeMessage(sender = sender, body = body, sourceType = "SMS")
            MessageStore.addMessage(msg)
            BridgeService.sendMessage(context, msg)
        }
    }
}
