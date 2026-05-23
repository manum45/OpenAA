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
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
        val typeInt = messageType.toInt() and 0xFFFF
        when (typeInt) {
            ControlMessageIdsEnum.ControlMessage.Enum.CHANNEL_OPEN_RESPONSE.number -> {
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
            AVChannelMessageIdsEnum.AVChannelMessage.Enum.SETUP_RESPONSE.number -> {
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
            AVChannelMessageIdsEnum.AVChannelMessage.Enum.VIDEO_FOCUS_INDICATION.number -> {
                Log.d(TAG, "VideoChannelHandler: Handling video focus indication (0x8008)")
            }
            AVChannelMessageIdsEnum.AVChannelMessage.Enum.AV_MEDIA_ACK_INDICATION.number -> {
                // Ignore ACKs
            }
            32776 -> { // Literal 0x8008
                Log.d(TAG, "VideoChannelHandler: Handling video focus indication via literal 0x8008")
            }
            else -> {
                Log.e(TAG, "VideoChannelHandler: Unhandled message type: ${typeInt.toString(16)}")
            }
        }
    }

    fun sendVideoData(data: ByteArray, timestampUs: Long) {
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
                .setConfig(1)
                .build()
            messageHandler.sendProtoMessage(
                channelId,
                FrameType.BULK.value or EncryptionType.ENCRYPTED.value or MessageTypeFlags.SPECIFIC.value,
                AVChannelMessageIdsEnum.AVChannelMessage.Enum.START_INDICATION.number.toShort(),
                startRequest
            )

            started = true
        }

        // We fragment the video data into chunks small enough for the SslHandler to encrypt in a single wrap() call (max 16KB)
        val maxChunkSize = 15000
        var offset = 0
        
        while (offset < data.size) {
            val isFirst = (offset == 0)
            val remaining = data.size - offset
            
            if (isFirst) {
                // First fragment uses AV_MEDIA_WITH_TIMESTAMP_INDICATION (ID 0)
                val currentChunkSize = minOf(remaining, maxChunkSize - 8)
                val timestampedData = ByteBuffer.allocate(8 + currentChunkSize)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(timestampUs)
                    .put(data, offset, currentChunkSize)
                    .array()

                messageHandler.sendProtoLikeMessage(
                    channelId,
                    FrameType.BULK.value or EncryptionType.ENCRYPTED.value,
                    AVChannelMessageIdsEnum.AVChannelMessage.Enum.AV_MEDIA_WITH_TIMESTAMP_INDICATION.number.toShort(),
                    timestampedData,
                    timestampedData.size
                )
                offset += currentChunkSize
            } else {
                // Subsequent fragments use AV_MEDIA_INDICATION (ID 1)
                val currentChunkSize = minOf(remaining, maxChunkSize)
                val chunk = data.copyOfRange(offset, offset + currentChunkSize)
                
                messageHandler.sendProtoLikeMessage(
                    channelId,
                    FrameType.BULK.value or EncryptionType.ENCRYPTED.value,
                    AVChannelMessageIdsEnum.AVChannelMessage.Enum.AV_MEDIA_INDICATION.number.toShort(),
                    chunk,
                    currentChunkSize
                )
                offset += currentChunkSize
            }
        }
    }

    override fun disconnected() {
        Log.d(TAG, "VideoChannelHandler: disconnected called")
        open = false
        setup = false
        started = false
    }
}
