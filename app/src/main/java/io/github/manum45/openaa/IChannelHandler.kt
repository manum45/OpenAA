package io.github.manum45.openaa

import f1x.aasdk.proto.ids.ControlMessageIdsEnum

interface IChannelHandler {
    fun disconnected()
    fun handleMessage(message: Message, messageType: Short)
}