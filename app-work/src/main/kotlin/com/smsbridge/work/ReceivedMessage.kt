package com.smsbridge.work

data class ReceivedMessage(
    val sender: String,
    val body: String,
    val timestamp: Long,
    val sourceType: String,
    val otp: String? = null
)
