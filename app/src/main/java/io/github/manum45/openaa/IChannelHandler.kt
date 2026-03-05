package io.github.manum45.openaa

interface IChannelHandler {
    fun disconnected(clientId: Int)
    fun handleMessage(message: Message)
}