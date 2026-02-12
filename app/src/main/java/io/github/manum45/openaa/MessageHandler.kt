/**
 * this file is generated with Gemini based on
 * https://github.com/tomasz-grobelny/AACS
 *
 * License: GPLv3
 */


package io.github.manum45.openaa

import android.content.Context
import android.util.Log
import com.google.protobuf.GeneratedMessageLite
import com.google.protobuf.Parser
import io.github.manum45.openaa.AAServer.proto.PingRequestProto
import io.github.manum45.openaa.AAServer.proto.PingResponseProto
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
    fun sendMessage(channel: Int, flags: Byte, payload: ByteArray) {
        val dataToSend = if ((flags and EncryptionType.ENCRYPTED.value).toInt() != 0) {
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


    private fun handleMessage(message: Message) {
        if (message.content.size < 2) return


        /// Log.d(TAG, "Handling message content: " + byteArrayToHex(message.content, message.content.size))

        val messageType = ByteBuffer.wrap(message.content, 0, 2)
            .order(ByteOrder.BIG_ENDIAN).short

        when (messageType) {
            MessageType.VERSIONREQUEST.value -> handleVersionRequest(message)
            MessageType.SSLHANDSHAKE.value -> handleSslHandshake(message)
            MessageType.PINGREQUEST.value -> handlePingRequest(message)
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
        /// TODO: use protobuffers
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

        val bytesToSend = ByteArray(4096)

        val returnValue = sslHandler.performSslHandshake(handshakeData, bytesToSend)

        if(returnValue == -2) {
            Log.e(TAG, "AAServer: ssl handshake error")
        } else if(returnValue == -1) {
            Log.d(TAG, "AAServer: ssl handshake finished")
        } else if(returnValue > 0) {
            var numBytes = returnValue
            val responsePayload = ByteBuffer.allocate(2 + numBytes)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort(MessageType.SSLHANDSHAKE.value)
                .put(bytesToSend, 0, numBytes)
                .array()
            sendMessage(0, FrameType.BULK.value or EncryptionType.PLAIN.value, responsePayload)
        } else {
            Log.d(TAG, "AAServer: ssl handshake still ongoing")
        }
    }

    private fun handlePingRequest(message: Message) {
        Log.d(TAG, "AAServer: handling ping request")
        val request = parseProto(message.content, 2, PingRequestProto.PingRequest.parser())
        val response = PingResponseProto.PingResponse.newBuilder().setTimestamp(request.timestamp).build()

        /// TODO: use protobuffers
        val payload = ByteBuffer.allocate(2 + response.serializedSize)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(MessageType.PINGRESPONSE.value)
            .put(response.toByteArray())
            .array()

        sendMessage(0, FrameType.BULK.value or EncryptionType.ENCRYPTED.value, payload)
    }

}
