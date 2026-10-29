package com.smsbridge.work

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

class CopyReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_COPY = "com.smsbridge.work.ACTION_COPY"
        const val EXTRA_TEXT = "text_to_copy"
        const val EXTRA_LABEL = "copy_label"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY) return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val label = intent.getStringExtra(EXTRA_LABEL) ?: "SMS"
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copied!", Toast.LENGTH_SHORT).show()
    }
}
