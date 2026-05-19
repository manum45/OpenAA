package io.github.manum45.openaa

import android.util.Log
import f1x.aasdk.proto.data.ChannelDescriptorData.ChannelDescriptor
import f1x.aasdk.proto.enums.AVChannelSetupStatusEnum
import f1x.aasdk.proto.enums.StatusEnum
import f1x.aasdk.proto.enums.VideoFocusModeEnum
import f1x.aasdk.proto.enums.VideoFocusReasonEnum
import f1x.aasdk.proto.ids.AVChannelMessageIdsEnum
import f1x.aasdk.proto.ids.ControlMessageIdsEnum
import f1x.aasdk.proto.messages.AVChannelSetupRequestMessage
import f1x.aasdk.proto.messages.AVChannelSetupResponseMessage
import f1x.aasdk.proto.messages.AVChannelStartIndicationMessage
import f1x.aasdk.proto.messages.ChannelOpenRequestMessage.ChannelOpenRequest
import f1x.aasdk.proto.messages.ChannelOpenResponseMessage.ChannelOpenResponse
import f1x.aasdk.proto.messages.VideoFocusRequestMessage

class VideoChannelHandler(var channel: ChannelDescriptor, var messageHandler: MessageHandler): IChannelHandler {

    var channelId = channel.channelId
    var open: Boolean = false
    var setup: Boolean = false
    var started: Boolean = false

    init {
        Log.d(TAG, "VideoChannelHandler: Initializing for channel ${channel.channelId}")
        val request = ChannelOpenRequest.newBuilder()
            .setChannelId(channel.channelId)
            .setPriority(1)
            .build()
        Log.d(TAG, "VideoChannelHandler: Sending channel open request for channel ${channel.channelId}")
        messageHandler.sendProtoMessage(
            channelId,
            FrameType.BULK.value or EncryptionType.ENCRYPTED.value or MessageTypeFlags.SPECIFIC.value,
            ControlMessageIdsEnum.ControlMessage.Enum.CHANNEL_OPEN_REQUEST.number.toShort(),
            request)
    }

    @OptIn(ExperimentalStdlibApi::class)
    override fun handleMessage(message: Message, messageType: Short) {
        when (messageType) {
            ControlMessageIdsEnum.ControlMessage.Enum.CHANNEL_OPEN_RESPONSE.number.toShort() -> {
                Log.d(TAG, "VideoChannelHandler: Handling channel open response")
                val response = messageHandler.parseProto(
                    message.content,
                    2,
                    ChannelOpenResponse.parser()
                )

                if (response.status == StatusEnum.Status.Enum.OK) {
                    Log.d(TAG, "VideoChannelHandler: Channel opened successfully")
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
                    Log.e(TAG, "VideoChannelHandler: Channel open failed with status ${response.status}")
                    open = false
                }
            }
            AVChannelMessageIdsEnum.AVChannelMessage.Enum.SETUP_RESPONSE.number.toShort() -> {
                Log.d(TAG, "VideoChannelHandler: Handling setup response")
                val response = messageHandler.parseProto(
                    message.content,
                    2,
                    AVChannelSetupResponseMessage.AVChannelSetupResponse.parser()
                )
                if (response.mediaStatus == AVChannelSetupStatusEnum.AVChannelSetupStatus.Enum.OK) {
                    Log.d(TAG, "VideoChannelHandler: Setup successful")
                    setup = true
                } else {
                    Log.e(TAG, "VideoChannelHandler: Setup failed with status ${response.mediaStatus}")
                    setup = false
                }
            }
            else -> {
                Log.e(TAG, "VideoChannelHandler: Unhandled message type: ${messageType.toHexString()}")
            }
        }
    }

    fun sendVideoData(data: ByteArray) {
        if (!open || !setup) {
            Log.e(TAG, "Cannot send video: Channel not ready (Open: $open, Setup: $setup)")
            return
        }

        if (!started) {
            Log.d(TAG, "Requesting Video Focus")

            val focusRequest = VideoFocusRequestMessage.VideoFocusRequest.newBuilder()
                .setFocusMode(VideoFocusModeEnum.VideoFocusMode.Enum.FOCUSED)
                .setFocusReason(VideoFocusReasonEnum.VideoFocusReason.Enum.UNK_1)
                .build()

            messageHandler.sendProtoMessage(
                channelId,
                FrameType.BULK.value or EncryptionType.ENCRYPTED.value or MessageTypeFlags.SPECIFIC.value,
                AVChannelMessageIdsEnum.AVChannelMessage.Enum.VIDEO_FOCUS_REQUEST.number.toShort(),
                focusRequest
            )

            Log.d(TAG, "VideoChannelHandler: Starting channel")

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

            started = true
        }

        messageHandler.sendProtoLikeMessage(
            channelId,
            FrameType.BULK.value or EncryptionType.ENCRYPTED.value,
            AVChannelMessageIdsEnum.AVChannelMessage.Enum.AV_MEDIA_INDICATION.number.toShort(),
            data,
            data.size
        )
    }

    override fun disconnected() {
        Log.d(TAG, "VideoChannelHandler: disconnected called")
        open = false
        setup = false
        started = false
    }
}
