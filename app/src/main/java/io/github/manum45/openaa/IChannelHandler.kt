package io.github.manum45.openaa

import f1x.aasdk.proto.ids.ControlMessageIdsEnum

interface IChannelHandler {
    fun disconnected(clientId: Int)
    fun handleMessage(message: Message, messageType: ControlMessageIdsEnum.ControlMessage.Enum)
}