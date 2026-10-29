package com.smsbridge.main

import java.util.UUID

data class BridgeMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sourceType: String // "SMS" or "NOTIFICATION"
)
