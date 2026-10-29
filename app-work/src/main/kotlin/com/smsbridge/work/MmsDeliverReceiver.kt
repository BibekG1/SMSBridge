package com.smsbridge.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Required for Android ROLE_SMS compliance
    }
}

