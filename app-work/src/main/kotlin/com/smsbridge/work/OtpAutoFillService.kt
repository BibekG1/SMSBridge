package com.smsbridge.work

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.*

class OtpAutoFillService : AccessibilityService() {
    companion object {
        private const val TAG = "SMSBridge-AutoFill"

        @Volatile
        var instance: OtpAutoFillService? = null
            private set

        val isRunning: Boolean
            get() = instance != null

        fun fillOtp(otp: String): Boolean {
            val service = instance ?: return false
            return service.tryFillOtp(otp)
        }

        fun fillOtpWithRetry(otp: String, attempts: Int = 4, delayMs: Long = 500) {
            val service = instance ?: return
            service.scope.launch {
                repeat(attempts) { index ->
                    val success = withContext(Dispatchers.Main) {
                        service.tryFillOtp(otp)
                    }
                    if (success) {
                        Log.d(TAG, "OTP auto-filled successfully on attempt ${index + 1}")
                        return@launch
                    }
                    delay(delayMs)
                }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "OtpAutoFillService connected and active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Passive observation; active filling triggered via fillOtp
    }

    override fun onInterrupt() {
        Log.d(TAG, "OtpAutoFillService interrupted")
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        Log.d(TAG, "OtpAutoFillService destroyed")
        super.onDestroy()
    }

    fun tryFillOtp(otp: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editableNodes = mutableListOf<AccessibilityNodeInfo>()

        try {
            findEditableNodes(root, editableNodes)
            if (editableNodes.isEmpty()) return false

            Log.d(TAG, "Found ${editableNodes.size} editable nodes on screen")

            // Case 1: Split OTP boxes (e.g. 4 or 6 separate digit boxes for 4 or 6-digit OTP)
            if (editableNodes.size == otp.length) {
                var allSucceeded = true
                for (i in otp.indices) {
                    val bundle = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            otp[i].toString()
                        )
                    }
                    val res = editableNodes[i].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                    if (!res) allSucceeded = false
                }
                if (allSucceeded) {
                    notifyAutoFillSuccess(otp)
                    return true
                }
            }

            // Case 2: Exactly 1 editable field on screen (standard single OTP field)
            if (editableNodes.size == 1) {
                val bundle = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        otp
                    )
                }
                if (editableNodes[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
                    notifyAutoFillSuccess(otp)
                    return true
                }
            }

            // Case 3: Multiple fields — find the one that is focused or has OTP keywords
            val targetNode = editableNodes.firstOrNull { it.isFocused }
                ?: editableNodes.firstOrNull { isLikelyOtpField(it) }
                ?: editableNodes.firstOrNull()

            if (targetNode != null) {
                val bundle = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        otp
                    )
                }
                if (targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
                    notifyAutoFillSuccess(otp)
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during OTP auto-fill: ${e.message}")
        }
        return false
    }

    private fun findEditableNodes(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (node.isEditable) {
            result.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditableNodes(child, result)
        }
    }

    private fun isLikelyOtpField(node: AccessibilityNodeInfo): Boolean {
        val hint = node.hintText?.toString().orEmpty().lowercase()
        val text = node.text?.toString().orEmpty().lowercase()
        val desc = node.contentDescription?.toString().orEmpty().lowercase()
        val viewId = node.viewIdResourceName?.toString().orEmpty().lowercase()

        val keywords = listOf("otp", "code", "pin", "verify", "verification", "passcode", "password")
        return keywords.any {
            hint.contains(it) || text.contains(it) || desc.contains(it) || viewId.contains(it)
        }
    }

    private fun notifyAutoFillSuccess(otp: String) {
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                "SMSBridge: Auto-filled OTP $otp \u2713",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
