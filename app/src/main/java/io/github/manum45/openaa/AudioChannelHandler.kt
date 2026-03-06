package io.github.manum45.openaa

import android.util.Log
import io.github.manum45.openaa.EncryptionType
import f1x.aasdk.proto.data.AudioConfigData.AudioConfig
import f1x.aasdk.proto.messages.ChannelOpenRequestMessage.ChannelOpenRequest
import f1x.aasdk.proto.messages.ChannelOpenResponseMessage.ChannelOpenResponse
import f1x.aasdk.proto.data.ChannelDescriptorData.ChannelDescriptor
import f1x.aasdk.proto.enums.StatusEnum

class AudioChannelHandler(channel: ChannelDescriptor, var messageHandler: MessageHandler): IChannelHandler {

    var audioConfigs: List<AudioConfig> = channel.avChannel.audioConfigsList
    var open: Boolean = false

    init {
        Log.d(TAG, "AudioChannelHandler: Initializing for channel ${channel.channelId}")
        val request = ChannelOpenRequest.newBuilder()
            .setChannelId(channel.channelId)
            .setPriority(1) // need to set this to some value, seems to be omitted when serializing if it is 0 because it is not marked as "required" in proto file?
            .build()
        Log.d(TAG, "AudioChannelHandler: Sending channel open request for channel ${channel.channelId}")
        messageHandler.sendProtoMessage(
            channel.channelId,
            FrameType.BULK.value or EncryptionType.ENCRYPTED.value or MessageTypeFlags.SPECIFIC.value,
            MessageType.CHANNELOPENREQUEST,
            request)
    }
    override fun handleMessage(message: Message, messageType: MessageType) {

        Log.e(
            TAG,
            "AudioChannelHandler: handleMessage called for channel ${message.channel}, type: ${messageType.name}"
        )

        when (messageType) {
            MessageType.CHANNELOPENRESPONSE -> {
                var response = messageHandler.parseProto(
                    message.content,
                    2,
                    ChannelOpenResponse.parser()
                )

                if(response.status == StatusEnum.Status.Enum.OK){
                    Log.d(TAG, "AudioChannelHandler: Channel opened successfully")
                    open = true
                } else {
                    Log.e(TAG, "AudioChannelHandler: Channel open failed with status ${response.status}")
                    open = false
                }
            }
            else -> {
                Log.e(TAG, "AudioChannelHandler: Unhandled message type: ${messageType.name}")
            }
        }
    }

    override fun disconnected(clientId: Int) {
        Log.d(TAG, "AudioChannelHandler: disconnected called for client $clientId")
        open = false
    }
}