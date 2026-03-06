package io.github.manum45.openaa

import android.util.Log
import io.github.manum45.openaa.EncryptionType
import f1x.aasdk.proto.data.AudioConfigData.AudioConfig
import f1x.aasdk.proto.messages.ChannelOpenRequestMessage.ChannelOpenRequest
import f1x.aasdk.proto.messages.ChannelOpenResponseMessage.ChannelOpenResponse
import f1x.aasdk.proto.data.ChannelDescriptorData.ChannelDescriptor
import f1x.aasdk.proto.enums.StatusEnum
import f1x.aasdk.proto.ids.AVChannelMessageIdsEnum
import f1x.aasdk.proto.ids.ControlMessageIdsEnum
import f1x.aasdk.proto.messages.AVChannelSetupRequestMessage
import f1x.aasdk.proto.messages.AVChannelSetupResponseMessage

class AudioChannelHandler(channel: ChannelDescriptor, var messageHandler: MessageHandler): IChannelHandler {

    var channelId = channel.channelId
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
            channelId,
            FrameType.BULK.value or EncryptionType.ENCRYPTED.value or MessageTypeFlags.SPECIFIC.value,
            ControlMessageIdsEnum.ControlMessage.Enum.CHANNEL_OPEN_REQUEST.number.toShort(),
            request)
    }
    override fun handleMessage(message: Message, messageType: Short) {

        when (messageType) {
            ControlMessageIdsEnum.ControlMessage.Enum.CHANNEL_OPEN_RESPONSE.number.toShort() -> {
                Log.d(TAG, "Handling channel open response")
                var response = messageHandler.parseProto(
                    message.content,
                    2,
                    ChannelOpenResponse.parser()
                )

                if(response.status == StatusEnum.Status.Enum.OK){
                    Log.d(TAG, "AudioChannelHandler: Channel opened successfully")
                    open = true

                    val request = AVChannelSetupRequestMessage.AVChannelSetupRequest.newBuilder()
                        // TODO: send a value that makes sense here. Issue with proto3: value of 0 will not be sent.
                        /// OpenAuto apparently does not like this, not sure why though, as aasdk protos already have proto3
                        /// see https://protobuf.dev/programming-guides/proto3/ -> implicit
                        /// how to solve this? open auto seems to expect 0 values
                        .setConfigIndex(1)
                        .build()
                    messageHandler.sendProtoMessage(
                        channelId,
                        FrameType.BULK.value or EncryptionType.ENCRYPTED.value or MessageTypeFlags.SPECIFIC.value,
                        AVChannelMessageIdsEnum.AVChannelMessage.Enum.SETUP_REQUEST.number.toShort(),
                        request
                    )
                } else {
                    Log.e(TAG, "AudioChannelHandler: Channel open failed with status ${response.status}")
                    open = false
                }
            }
            AVChannelMessageIdsEnum.AVChannelMessage.Enum.SETUP_RESPONSE.number.toShort() -> {
                Log.d(TAG, "Handling setup response")
                Log.e(TAG, "Not implemented")
            }
            else -> {
                Log.e(TAG, "AudioChannelHandler: Unhandled message type: ${messageType.toHexString()}")
            }
        }
    }

    override fun disconnected(clientId: Int) {
        Log.d(TAG, "AudioChannelHandler: disconnected called for client $clientId")
        open = false
    }
}