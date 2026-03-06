package io.github.manum45.openaa

import android.util.Log
import io.github.manum45.openaa.EncryptionType
import tag.aas.AudioConfigOuterClass
import tag.aas.ChannelOpenRequestOuterClass
import tag.aas.ChannelOpenResponseOuterClass
import tag.aas.ChannelOuterClass
import tag.aas.PingRequestOuterClass.PingRequest

class AudioChannelHandler(channel: ChannelOuterClass.Channel, var messageHandler: MessageHandler): IChannelHandler {

    var audioConfigs: List<AudioConfigOuterClass.AudioConfig> = channel.mediaChannel.audioConfigsList
    var open: Boolean = false

    init {
        Log.d(TAG, "AudioChannelHandler: Initializing for channel ${channel.channelId}")
        val request = ChannelOpenRequestOuterClass.ChannelOpenRequest.newBuilder()
            .setChannelId(channel.channelId)
            .setUnknownField(0)  // priority according to aasdk repo
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
                    ChannelOpenResponseOuterClass.ChannelOpenResponse.parser()
                )

                if(response.status == 0){
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