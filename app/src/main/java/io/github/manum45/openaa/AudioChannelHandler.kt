package io.github.manum45.openaa

import android.util.Log
import io.github.manum45.openaa.EncryptionType
import f1x.aasdk.proto.data.AudioConfigData.AudioConfig
import f1x.aasdk.proto.messages.ChannelOpenRequestMessage.ChannelOpenRequest
import f1x.aasdk.proto.messages.ChannelOpenResponseMessage.ChannelOpenResponse
import f1x.aasdk.proto.data.ChannelDescriptorData.ChannelDescriptor
import f1x.aasdk.proto.enums.AVChannelSetupStatusEnum
import f1x.aasdk.proto.enums.AudioFocusTypeEnum
import f1x.aasdk.proto.enums.StatusEnum
import f1x.aasdk.proto.ids.AVChannelMessageIdsEnum
import f1x.aasdk.proto.ids.ControlMessageIdsEnum
import f1x.aasdk.proto.messages.AVChannelSetupRequestMessage
import f1x.aasdk.proto.messages.AVChannelSetupResponseMessage
import f1x.aasdk.proto.messages.AVChannelStartIndicationMessage
import f1x.aasdk.proto.messages.AudioFocusRequestMessage

class AudioChannelHandler(var channel: ChannelDescriptor, var messageHandler: MessageHandler): IChannelHandler {

    var channelId = channel.channelId
    var audioConfigs: List<AudioConfig> = channel.avChannel.audioConfigsList
    var open: Boolean = false
    var setup: Boolean = false
    var started: Boolean = false

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
                    Log.d(TAG, "AudioChannelHandler: Channel opened successfully for channel $channelId")
                    Log.d(TAG, "Audio Type: ${channel.avChannel.audioType}")
                    open = true

                    Log.d(TAG, "Available Audio Configs: ${audioConfigs.size}")
                    audioConfigs.forEachIndexed { index, config ->
                        Log.d(TAG, "Config $index: SampleRate=${config.sampleRate}, Channels=${config.channelCount}, Bits=${config.bitDepth}")
                    }

                    val request = AVChannelSetupRequestMessage.AVChannelSetupRequest.newBuilder()
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
                var response = messageHandler.parseProto(
                    message.content,
                    2,
                    AVChannelSetupResponseMessage.AVChannelSetupResponse.parser()
                )
                if(response.mediaStatus == AVChannelSetupStatusEnum.AVChannelSetupStatus.Enum.OK) {
                    Log.d(TAG, "AudioChannelHandler: Setup successful")
                    Log.d(TAG, "AudioChannelHandler: Max unacked: ${response.maxUnacked}")
                    Log.d(TAG, "AudioChannelHandler: Configs: ${response.configsCount}")
                    setup = true
                }
                else {
                    Log.e(TAG, "AudioChannelHandler: Setup failed with status ${response.mediaStatus}")
                    setup = false
                }
            }
            AVChannelMessageIdsEnum.AVChannelMessage.Enum.AV_MEDIA_ACK_INDICATION.number.toShort() -> {
                // Head unit acknowledged receiving media. We can use this for flow control if needed.
                // Log.v(TAG, "Audio data ACK received")
            }
            else -> {
                Log.e(TAG, "AudioChannelHandler: Unhandled message type: ${messageType.toHexString()}")
            }
        }
    }

    fun sendAudioData(data: ByteArray) {
        if (!open || !setup) {
            Log.e(TAG, "Cannot send audio: Channel not ready (Open: $open, Setup: $setup)")
            return
        }

        if(!started) {
            Log.d(TAG, "Requesting Audio Focus")

            val focusRequest = AudioFocusRequestMessage.AudioFocusRequest.newBuilder()
                .setAudioFocusType(AudioFocusTypeEnum.AudioFocusType.Enum.GAIN)
                .build()

            messageHandler.sendProtoMessage(
                0,
                FrameType.BULK.value or EncryptionType.ENCRYPTED.value,
                ControlMessageIdsEnum.ControlMessage.Enum.AUDIO_FOCUS_REQUEST.number.toShort(),
                focusRequest
            )

            Log.d(TAG, "AudioChannelHandler: Starting channel")

            val startRequest = AVChannelStartIndicationMessage.AVChannelStartIndication.newBuilder()
                .setSession(0)
                .setConfig(0)
                .build()
            messageHandler.sendProtoMessage(
                channelId,
                FrameType.BULK.value or EncryptionType.ENCRYPTED.value or MessageTypeFlags.SPECIFIC.value,
                AVChannelMessageIdsEnum.AVChannelMessage.Enum.START_INDICATION.number.toShort(),
                startRequest
            )

            /// apparently no response for this message?
            started = true
        }

        // Send the raw audio frame
        // Most AA implementations use MessageType 0 or a specific DATA flag for raw streams
        messageHandler.sendProtoLikeMessage(
            channelId,
            FrameType.BULK.value or EncryptionType.ENCRYPTED.value,
            AVChannelMessageIdsEnum.AVChannelMessage.Enum.AV_MEDIA_INDICATION.number.toShort(),
            data,
            data.size
        )

    }

    override fun disconnected() {
        Log.d(TAG, "AudioChannelHandler: disconnected called")
        open = false
        setup = false
        started = false
    }
}