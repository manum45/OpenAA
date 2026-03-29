/**
 * this file is generated with Gemini based on
 * https://github.com/tomasz-grobelny/AACS
 *
 * License: GPLv3
 */


package io.github.manum45.openaa

import android.content.Context
import android.net.nsd.DiscoveryRequest
import android.os.Build
import android.util.Log
import com.google.protobuf.GeneratedMessageLite
import com.google.protobuf.Parser
import f1x.aasdk.proto.messages.PingRequestMessage.PingRequest
import f1x.aasdk.proto.messages.PingResponseMessage.PingResponse
import f1x.aasdk.proto.messages.ServiceDiscoveryRequestMessage.ServiceDiscoveryRequest
import f1x.aasdk.proto.messages.ServiceDiscoveryResponseMessage.ServiceDiscoveryResponse
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.*
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.io.pem.PemReader
import f1x.aasdk.proto.enums.AVStreamTypeEnum.AVStreamType
import f1x.aasdk.proto.enums.AudioTypeEnum
import f1x.aasdk.proto.ids.ControlMessageIdsEnum
import java.security.Security
import java.util.Dictionary
import kotlin.experimental.or



/**
 * Represents a message exchanged with the head unit.
 */
data class Message(val channel: Byte, val flags: Byte, val content: ByteArray) {
    // For debugging and logging
    override fun toString(): String {
        return "Message(channel=$channel, flags=$flags, content.size=${content.size})"
    }
}




/**
 * Handles communication with an Android Auto Head Unit.
 * Manages message framing, SSL/TLS encryption, and basic message handling.
 *
 * @param transport The transport layer to use for sending data.
 * @param context Android context to access assets.
 */
class MessageHandler(
    private val transport: IUsbStreamer,
    private val context: Context
) {
    var onMessageReceived: ((Message) -> Unit)? = null

    private var receiveBuffer = byteArrayOf()

    private var sslHandler: SslHandler = SslHandler(context)

    private var channelHandlers: MutableMap<Int, IChannelHandler> = mutableMapOf()

    init {
        sslHandler.initializeSslContext()
    }

    fun <T : GeneratedMessageLite<T, *>> parseProto(bytes: ByteArray, offset: Int, parser: Parser<T>): T {
        return parser.parseFrom(bytes, offset, bytes.size - offset)
    }


    /**
     * Sends a message to the head unit.
     */
    fun sendProtoMessage(channel: Int, flags: Byte, messageType: Short, message: GeneratedMessageLite<*, *>) {
        sendProtoLikeMessage(
            channel,
            flags,
            messageType,
            message.toByteArray(),
            message.serializedSize
        )
    }

    fun sendProtoLikeMessage(channel: Int, flags: Byte, messageType: Short, payload: ByteArray, payloadLength: Int) {
        val payload = ByteBuffer.allocate(2 + payloadLength)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(messageType)
            .put(payload)
            .array()

        sendMessage(channel, flags, payload)
    }

    fun sendMessage(channel: Int, flags: Byte, payload: ByteArray) {
        /// TODO: pass message type also to this function, create buffer here
        /// Caution: will need to encrypt payload including message type it seems
        /// How to achieve this without having even more buffer copying

        /// Log.d(TAG, "MessageHandler: sending message, channel: $channel, flags: $flags, payload: ${byteArrayToHex(payload, payload.size)}")

        val dataToSend = if ((flags and EncryptionType.ENCRYPTED.value).toInt() != 0) {
            /// From AaCommunicator::prepareMessage it looks like channel, flags and length is not encrypted,
            /// but message type and data is (both are part of payload already here)
            sslHandler.encryptPayload(payload)
        } else {
            payload
        }

        if (dataToSend != null) {
            val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                .put(channel.toByte())
                .put(flags)
                .putShort(dataToSend.size.toShort())
                .array()
            transport.write(header + dataToSend)
        }
    }


    /**
     * Processes raw data received from the transport layer.
     */
    fun receiveData(data: ByteArray, numBytes: Int) {
        /// TODO: don't copy that much data all the time everywhere
        receiveBuffer += data.copyOfRange(0, numBytes)
        processReceiveBuffer()
    }

    private fun processReceiveBuffer() {
        // data has to be at least 4 bytes: 1 byte channel, 1 byte flags, 2 bytes length
        while (receiveBuffer.size >= 4) {
            val length = ((receiveBuffer[2].toInt() and 0xFF) shl 8) or (receiveBuffer[3].toInt() and 0xFF)
            if (receiveBuffer.size >= 4 + length) {
                val channel = receiveBuffer[0]
                val flags = receiveBuffer[1]
                val payload = receiveBuffer.copyOfRange(4, 4 + length)

                val messageContent = if ((flags and EncryptionType.ENCRYPTED.value).toInt() != 0) {
                    sslHandler.decryptMessage(payload)
                } else {
                    payload
                }
                
                if(messageContent != null) {
                    val message = Message(channel, flags, messageContent)
                    handleMessage(message)
                }

                receiveBuffer = receiveBuffer.copyOfRange(4 + length, receiveBuffer.size)
            } else {
                break // Wait for more data
            }
        }
    }


    /**
     * The usual sequence seems to be this:
     * - Headunit sends version request, Server (Phone) sends version response
     * - Headunit initiates SSL handshake, some back and forth
     * - Headunit sends AUTHCOMPLETE
     * - Server sends SERVICEDISCOVERYREQUEST
     * - Server sends SERVICEDISCOVERYRESPONSE
     * - TBD
     *
     */
    private fun handleMessage(message: Message) {
        if (message.content.size < 2) return

        /// corresponds to AaCommunicator::handleMessageContent
        val messageType: ControlMessageIdsEnum.ControlMessage.Enum?


        /// Log.d(TAG, "Handling message content: " + byteArrayToHex(message.content, message.content.size))
        val msgTypeRaw = ByteBuffer.wrap(message.content, 0, 2).order(ByteOrder.BIG_ENDIAN).short


        if (message.channel != 0.toByte()){
            if(channelHandlers.contains(message.channel.toInt())) {
                Log.d(TAG, "MessageHandler: received channel message for initialized channel handler: ${message.channel}, type: ${msgTypeRaw.toHexString()}")
                channelHandlers[message.channel.toInt()]?.handleMessage(message, msgTypeRaw)
            } else {
                Log.e(TAG,"MessageHandler: received channel message for non-initialized channel handler: ${message.channel}")
            }
        }
        else {
            try {
                messageType = ControlMessageIdsEnum.ControlMessage.Enum.forNumber(msgTypeRaw.toInt())
            } catch (e: NoSuchElementException) {
                Log.e(TAG, "Invalid message type: ${msgTypeRaw.toHexString()}")
                return
            }

            if(messageType == null){
                Log.e(TAG, "Invalid message type: ${msgTypeRaw.toHexString()}")
                return
            }
            Log.d(TAG, "MessageHandler: received message, type: ${messageType.name}, channel: ${message.channel}")

            when (messageType) {
                ControlMessageIdsEnum.ControlMessage.Enum.VERSION_REQUEST -> handleVersionRequest(message)
                ControlMessageIdsEnum.ControlMessage.Enum.SSL_HANDSHAKE -> handleSslHandshake(message)
                ControlMessageIdsEnum.ControlMessage.Enum.PING_REQUEST -> handlePingRequest(message)
                ControlMessageIdsEnum.ControlMessage.Enum.AUTH_COMPLETE -> sendServiceDiscoveryRequest()
                ControlMessageIdsEnum.ControlMessage.Enum.SERVICE_DISCOVERY_RESPONSE -> handleServiceDiscoveryResponse(message)
                else -> {
                    Log.e(TAG, "Unhandled message type: $messageType")
                    onMessageReceived?.invoke(message)
                }
            }
        }
    }








    private fun handleVersionRequest(message: Message) {
        Log.d(TAG, "AAServer: handling version request")
        if (message.content.size < 6) return
        val bb = ByteBuffer.wrap(message.content).order(ByteOrder.BIG_ENDIAN)
        bb.getShort() // skip message type
        val major = bb.short
        val minor = bb.short
        if (major == 1.toShort()) {
            sendVersionResponse(1, 5)
        } else {
            // Handle unsupported version
        }
    }

    private fun sendVersionResponse(major: Short, minor: Short) {
        val payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putShort(ControlMessageIdsEnum.ControlMessage.Enum.VERSION_RESPONSE.number.toShort())
            .putShort(major)
            .putShort(minor)
            .putShort(0) // version match
            .array()
        sendMessage(0, FrameType.BULK.value or EncryptionType.PLAIN.value, payload)
    }

    private fun handleSslHandshake(message: Message) {

        Log.d(TAG, "AAServer: handling ssl handshake")
        val handshakeData = message.content.copyOfRange(2, message.content.size)

        /// TODO: improve buffer handling, reduce allocating and copying
        val bytesToSend = ByteArray(17000)

        val (returnValue, numSendBytes) = sslHandler.performSslHandshake(handshakeData, bytesToSend)

        if(returnValue == -1) {
            Log.e(TAG, "AAServer: ssl handshake error")
        } else if(returnValue == 0) {
            Log.d(TAG, "AAServer: ssl handshake still ongoing")
        } else if(returnValue == 1) {
            Log.d(TAG, "AAServer: ssl handshake finished")
            Log.d(TAG, "Final sslEngine State: ${sslHandler.getSslEngineStatus()}")
        } else {
            Log.e(TAG, "AAServer: ssl handshake invalid return value: $returnValue")
        }

        if(numSendBytes > 0) {
            val responsePayload = ByteBuffer.allocate(2 + numSendBytes)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort(ControlMessageIdsEnum.ControlMessage.Enum.SSL_HANDSHAKE.number.toShort())
                .put(bytesToSend, 0, numSendBytes)
                .array()
            sendMessage(0, FrameType.BULK.value or EncryptionType.PLAIN.value, responsePayload)
        } else {
            Log.d(TAG, "AAServer: ssl handshake still ongoing")
        }
    }

    private fun handlePingRequest(message: Message) {
        Log.d(TAG, "AAServer: handling ping request")
        val request = parseProto(message.content, 2, PingRequest.parser())
        val response = PingResponse.newBuilder().setTimestamp(request.timestamp).build()

        sendProtoMessage(
            0,
            FrameType.BULK.value or EncryptionType.ENCRYPTED.value,
            ControlMessageIdsEnum.ControlMessage.Enum.PING_RESPONSE.number.toShort(),
            response
        )
    }


    private fun sendServiceDiscoveryRequest() {
        Log.d(TAG, "AAServer: sending service discovery request")
        val request = ServiceDiscoveryRequest.newBuilder()
            .setDeviceBrand(Build.MANUFACTURER)
            .setDeviceName(Build.MODEL)
            .build()

        sendProtoMessage(
            0,
            EncryptionType.ENCRYPTED.value or FrameType.BULK.value,
            ControlMessageIdsEnum.ControlMessage.Enum.SERVICE_DISCOVERY_REQUEST.number.toShort(),
            request
        )
    }

    private fun handleServiceDiscoveryResponse(message: Message) {
        Log.d(TAG, "AAServer: handling service discovery response")
        val response: ServiceDiscoveryResponse = parseProto(message.content, 2, ServiceDiscoveryResponse.parser())
        Log.d(TAG, "AAServer: service discovery response: ${response.channelsCount} channels")

        for (channel in response.channelsList) {
            var handled = false
            if (channel.hasAvChannel()) {
                when (channel.avChannel.streamType) {
                    AVStreamType.Enum.VIDEO -> {
                        Log.d(TAG, "AAServer: found video channel: id ${channel.channelId}")
                    }
                    AVStreamType.Enum.AUDIO -> {
                        Log.d(TAG, "AAServer: found audio channel: id ${channel.channelId}")
                        var handler = AudioChannelHandler(channel, this)
                        channelHandlers[channel.channelId] = handler
                        handled = true
                    }
                    else -> {
                        Log.d(TAG, "AAServer: found unknown media channel: id ${channel.channelId}")
                    }
                }
            }
            else if(channel.hasAvInputChannel()) {
                if (channel.avInputChannel.hasAudioConfig()) {
                    Log.d(TAG, "AAServer: found audio input channel: id ${channel.channelId}")
                } else {
                    Log.d(TAG, "AAServer: found unknown media input channel: id ${channel.channelId}")
                }
            } else if (channel.hasSensorChannel()) {
                var sensorsString: String = ""
                for (sensor in channel.sensorChannel.sensorsList) {
                    sensorsString += "${sensor.type.name}, "
                }
                Log.d(TAG, "AAServer: found sensor channel: id ${channel.channelId}, sensors: $sensorsString")
            } else if (channel.hasInputChannel()) {
                var buttonsStr: String = ""
                for (button in channel.inputChannel.supportedKeycodesList) {
                    buttonsStr += "${button}, "
                }
                var screenConfigStr: String = "None"
                if(channel.inputChannel.hasTouchScreenConfig()) {
                    screenConfigStr = "width: ${channel.inputChannel.touchScreenConfig.width}, height: ${channel.inputChannel.touchScreenConfig.height}"
                }
                Log.d(TAG, "AAServer: found input channel: id ${channel.channelId}, buttons: $buttonsStr, screen config: $screenConfigStr")
            } else {
                Log.d(TAG, "AAServer: found unknown channel: id ${channel.channelId}")
            }

            if(!handled) {
                Log.w(TAG, "AAServer: channel was not handled: ${channel.channelId}")
            }
        }
    }


    fun getReadyMediaAudioChannel() : AudioChannelHandler? {
        for (channelHdnlr in channelHandlers.values) {
            if (channelHdnlr is AudioChannelHandler) {
                if (channelHdnlr.channel.avChannel.audioType == AudioTypeEnum.AudioType.Enum.MEDIA) {
                    if (channelHdnlr.open && channelHdnlr.setup) {
                        return channelHdnlr
                    }
                }
            }
        }
        return null
    }


    fun disconnected() {
        for (channelHdnlr in channelHandlers.values) {
            channelHdnlr.disconnected()
        }
    }
}
