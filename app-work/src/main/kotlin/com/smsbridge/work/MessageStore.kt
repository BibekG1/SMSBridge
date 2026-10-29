package com.smsbridge.work

object MessageStore {
    private const val MAX = 3
    private val _messages = ArrayDeque<ReceivedMessage>(MAX)
    val messages: List<ReceivedMessage> get() = _messages.toList()
    var onNewMessage: ((ReceivedMessage) -> Unit)? = null

    fun addMessage(msg: ReceivedMessage) {
        synchronized(this) {
            if (_messages.size >= MAX) _messages.removeFirst()
            _messages.addLast(msg)
        }
        onNewMessage?.invoke(msg)
    }

    fun clear() {
        synchronized(this) { _messages.clear() }
    }
}
