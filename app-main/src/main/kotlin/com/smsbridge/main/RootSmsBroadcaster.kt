package com.smsbridge.main

import android.content.Intent
import android.util.Log
import java.lang.reflect.Method

/**
 * Executed via app_process as UID 0 (root).
 * Dispatches android.provider.Telephony.SMS_RECEIVED directly to target Android user profiles (e.g. User 11 / Island Work Profile)
 * with the exact raw GSM PDUs so Google Play Services SMS Retriever and target apps can auto-verify without manual input.
 */
object RootSmsBroadcaster {

    @JvmStatic
    fun main(args: Array<String>) {
        println("RootSmsBroadcaster started. Args: ${args.joinToString(" ")}")
        if (args.size < 3) {
            println("Usage: RootSmsBroadcaster <hexPdu> <format> <targetUserId>")
            return
        }

        val hexPdu = args[0]
        val format = args[1]
        val targetUserId = args[2].toIntOrNull() ?: 11

        try {
            val pduBytes = hexToBytes(hexPdu)
            val pduArray = arrayOf(pduBytes)

            val intent = Intent("android.provider.Telephony.SMS_RECEIVED").apply {
                putExtra("pdus", pduArray)
                putExtra("format", format)
                addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND)
            }

            val success = broadcastAsUser(intent, targetUserId)
            println("Root broadcast to User $targetUserId result: $success")
        } catch (e: Throwable) {
            println("RootSmsBroadcaster error: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun broadcastAsUser(intent: Intent, userId: Int): Boolean {
        return try {
            val amClass = Class.forName("android.app.ActivityManager")
            val getServiceMethod = amClass.getMethod("getService")
            val am = getServiceMethod.invoke(null) ?: run {
                println("ActivityManager service is null")
                return false
            }

            // Find broadcastIntentWithFeature or broadcastIntent on IActivityManager
            val methods = am.javaClass.methods
            val broadcastMethod = methods.firstOrNull { it.name.startsWith("broadcastIntent") }
                ?: run {
                    println("No broadcastIntent method found")
                    return false
                }

            val paramTypes = broadcastMethod.parameterTypes
            val args = arrayOfNulls<Any>(paramTypes.size)

            for (i in paramTypes.indices) {
                val type = paramTypes[i]
                when {
                    type == Intent::class.java -> args[i] = intent
                    type == Int::class.javaPrimitiveType -> {
                        // The last int parameter in broadcastIntent is always userId!
                        if (i == paramTypes.size - 1) {
                            args[i] = userId
                        } else if (broadcastMethod.name.contains("WithFeature") && i == paramTypes.size - 5) {
                            args[i] = -1 // OP_NONE (-1)
                        } else {
                            args[i] = 0 // resultCode (0)
                        }
                    }
                    type == Boolean::class.javaPrimitiveType -> args[i] = false
                    type == Array<String>::class.java -> args[i] = null
                    else -> args[i] = null
                }
            }

            println("Invoking ${broadcastMethod.name} for user $userId (params: ${paramTypes.size})...")
            val result = broadcastMethod.invoke(am, *args)
            println("Invocation returned: $result")
            true
        } catch (e: Throwable) {
            println("Failed to broadcast as user $userId: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
