package com.smsbridge.main

object MessageStore {
    private const val MAX_MESSAGES = 3
    private val _messages = ArrayDeque<BridgeMessage>(MAX_MESSAGES)
    val messages: List<BridgeMessage> get() = _messages.toList()

    var onNewMessage: ((BridgeMessage) -> Unit)? = null

    fun addMessage(msg: BridgeMessage) {
        synchronized(this) {
            if (_messages.size >= MAX_MESSAGES) _messages.removeFirst()
            _messages.addLast(msg)
        }
        onNewMessage?.invoke(msg)
    }

    fun clear() {
        synchronized(this) { _messages.clear() }
    }
}
