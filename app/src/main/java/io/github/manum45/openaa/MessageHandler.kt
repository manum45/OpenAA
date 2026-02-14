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
import tag.aas.PingRequestOuterClass.PingRequest
import tag.aas.PingResponseOuterClass.PingResponse
import tag.aas.ServiceDiscoveryRequestOuterClass.ServiceDiscoveryRequest
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
import java.security.Security
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



    init {
        sslHandler.initializeSslContext()
    }

    private fun <T : GeneratedMessageLite<T, *>> parseProto(bytes: ByteArray, offset: Int, parser: Parser<T>): T {
        return parser.parseFrom(bytes, offset, bytes.size - offset)
    }


    /**
     * Sends a message to the head unit.
     */
    fun sendProtoMessage(channel: Int, flags: Byte, messageType: MessageType, message: GeneratedMessageLite<*, *>) {
        val payload = ByteBuffer.allocate(2 + message.serializedSize)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(messageType.value)
            .put(message.toByteArray())
            .array()

        sendMessage(channel, flags, payload)
    }

    fun sendMessage(channel: Int, flags: Byte, payload: ByteArray) {
        /// TODO: pass message type also to this function, create buffer here
        /// Caution: will need to encrypt payload including message type it seems
        /// How to achieve this without having even more buffer copying

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


        /// Log.d(TAG, "Handling message content: " + byteArrayToHex(message.content, message.content.size))

        val messageType = MessageType.fromShort(ByteBuffer.wrap(message.content, 0, 2)
            .order(ByteOrder.BIG_ENDIAN).short)

        Log.d(TAG, "MessageHandler: received message type: ${messageType.name}")

        when (messageType) {
            MessageType.VERSIONREQUEST -> handleVersionRequest(message)
            MessageType.SSLHANDSHAKE -> handleSslHandshake(message)
            MessageType.PINGREQUEST -> handlePingRequest(message)
            MessageType.AUTHCOMPLETE ->sendServiceDiscoveryRequest()
            else -> {
                Log.e(TAG, "Unhandled message type: $messageType")
                onMessageReceived?.invoke(message)
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
            .putShort(MessageType.VERSIONRESPONSE.value)
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

        val returnValue = sslHandler.performSslHandshake(handshakeData, bytesToSend)

        if(returnValue == -2) {
            Log.e(TAG, "AAServer: ssl handshake error")
        } else if(returnValue == -1) {
            /// TODO: this state will not be reached if performSslHandshake still produces data
            ///       in the last iteration. How to handle this better? Just be stuck in Ssl
            ///       handshake if headunit does not provide more data?
            Log.d(TAG, "AAServer: ssl handshake finished")
            Log.d(TAG, "Final sslEngine State: ${sslHandler.getSslEngineStatus()}")
        } else if(returnValue > 0) {
            var numBytes = returnValue
            val responsePayload = ByteBuffer.allocate(2 + numBytes)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort(MessageType.SSLHANDSHAKE.value)
                .put(bytesToSend, 0, numBytes)
                .array()
            sendMessage(0, FrameType.BULK.value or EncryptionType.PLAIN.value, responsePayload)
            Log.d(TAG, "SslEngine State: ${sslHandler.getSslEngineStatus()}")
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
            MessageType.PINGRESPONSE,
            response
        )
    }


    private fun sendServiceDiscoveryRequest() {
        Log.d(TAG, "AAServer: sending service discovery request")
        val request = ServiceDiscoveryRequest.newBuilder()
            .setManufacturer(Build.MANUFACTURER)
            .setModel(Build.MODEL)
            .build()

        /// TODO: this should actually be encrypted, but encryption does not seem to work
        /// payload in openauto is appears as all 0
        /// not sure if OEM headunits will accept this without encryption. seems to work
        /// for OpenAuto
        sendProtoMessage(
            0,
            EncryptionType.PLAIN.value or FrameType.BULK.value,
            MessageType.SERVICEDISCOVERYREQUEST,
            request
        )
    }

}
