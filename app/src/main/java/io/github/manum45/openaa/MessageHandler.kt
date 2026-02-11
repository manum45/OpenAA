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



// These constants would typically be in their own files.
object MessageType {
    const val VersionRequest: Short = 0x0001
    const val VersionResponse: Short = 0x0002
    const val SslHandshake: Short = 0x0003
    const val PingRequest: Short = 0x0006
    const val PingResponse: Short = 0x0007
}

object EncryptionType {
    const val PLAIN: Byte = 0x4
    const val ENCRYPTED: Byte = 0x8
}

object FrameType {
    const val BULK: Byte = 0x0
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
    private lateinit var sslEngine: SSLEngine

    // Buffers for SSLEngine
    private val appSendBuffer: ByteBuffer = ByteBuffer.allocate(16384)
    private val netSendBuffer: ByteBuffer = ByteBuffer.allocate(32768)
    private val appReceiveBuffer: ByteBuffer = ByteBuffer.allocate(16384)
    private val netReceiveBuffer: ByteBuffer = ByteBuffer.allocate(32768)


    init {
        initializeSslContext()
    }

    private fun <T : GeneratedMessageLite<T, *>> parseProto(bytes: ByteArray, offset: Int, parser: Parser<T>): T {
        return parser.parseFrom(bytes, offset, bytes.size - offset)
    }


    /**
     * Sends a message to the head unit.
     */
    fun sendMessage(channel: Int, flags: Byte, payload: ByteArray) {
        val dataToSend = if ((flags.toInt() and EncryptionType.ENCRYPTED.toInt()) != 0) {
            encryptPayload(payload)
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

                val messageContent = if ((flags.toInt() and EncryptionType.ENCRYPTED.toInt()) != 0) {
                    decryptMessage(payload)
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
            MessageType.VersionRequest -> handleVersionRequest(message)
            MessageType.SslHandshake -> handleSslHandshake(message)
            MessageType.PingRequest -> handlePingRequest(message)
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
            .putShort(MessageType.VersionResponse)
            .putShort(major)
            .putShort(minor)
            .putShort(0) // version match
            .array()
        //sendMessage(0, FrameType.BULK or EncryptionType.PLAIN, payload)
        //flag 0x2 = LIBUSB_ENDPOINT_OUT | USB_TYPE_VENDOR with flipped endianness. just a guess
        sendMessage(0, 0x2, payload)
    }


    
    private fun handleSslHandshake(message: Message) {
        Log.d(TAG, "AAServer: handling ssl handshake")
        val handshakeData = message.content.copyOfRange(2, message.content.size)
        
        netReceiveBuffer.compact()
        netReceiveBuffer.put(handshakeData)
        netReceiveBuffer.flip()

        while (true) {
            when (sslEngine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    Log.d(TAG, "Ssl handshake: Unwrap")
                    if (netReceiveBuffer.hasRemaining()) {
                        sslEngine.unwrap(netReceiveBuffer, appReceiveBuffer)
                    } else {
                        netReceiveBuffer.compact()
                        return // Need more data from peer
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    Log.d(TAG, "Ssl handshake: Wrap")
                    netSendBuffer.clear()
                    val result = sslEngine.wrap(appSendBuffer, netSendBuffer)
                    if (result.bytesProduced() > 0) {
                        netSendBuffer.flip()
                        val bytesToSend = ByteArray(netSendBuffer.remaining())
                        netSendBuffer.get(bytesToSend)

                        val responsePayload = ByteBuffer.allocate(2 + bytesToSend.size)
                            .order(ByteOrder.BIG_ENDIAN)
                            .putShort(MessageType.SslHandshake)
                            .put(bytesToSend)
                            .array()
                        
                        sendMessage(0, FrameType.BULK or EncryptionType.PLAIN, responsePayload)
                    }
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    Log.d(TAG, "Ssl handshake: Task")
                    sslEngine.delegatedTask?.run()
                }
                SSLEngineResult.HandshakeStatus.FINISHED -> {
                    Log.d(TAG, "Ssl handshake: Finished")
                }
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                    Log.d(TAG, "Ssl handshake: Not Handshaking")
                    return
                }
            }
        }
    }



    private fun handlePingRequest(message: Message) {
        Log.d(TAG, "AAServer: handling ping request")
        val request = parseProto(message.content, 2, PingRequestProto.PingRequest.parser())
        val response = PingResponseProto.PingResponse.newBuilder().setTimestamp(request.timestamp).build()

        /// TODO: use protobuffers
        val payload = ByteBuffer.allocate(2 + response.serializedSize)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(MessageType.PingResponse)
            .put(response.toByteArray())
            .array()

        sendMessage(0, FrameType.BULK or EncryptionType.ENCRYPTED, payload)
    }







    private fun initializeSslContext() {
        // Load certificate and private key from assets
        val certInputStream = context.assets.open("ssl/android_auto.crt")
        val keyInputStream = context.assets.open("ssl/android_auto.key")

        val certificate = loadCertificate(certInputStream)
        val privateKey = loadPrivateKey(keyInputStream)

        // Set up KeyStore
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null, null)
        keyStore.setKeyEntry("default", privateKey, "".toCharArray(), arrayOf(certificate))

        // Set up KeyManagerFactory
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, "".toCharArray())

        // Set up a permissive TrustManager that trusts all certificates
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }

        // Set up SSLContext
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, arrayOf(trustAllManager), null)

        // Configure SSLEngine
        sslEngine = sslContext.createSSLEngine()
        sslEngine.useClientMode = false
        sslEngine.enabledProtocols = arrayOf("TLSv1.2") // As per C++ code disabling TLSv1.3

        netReceiveBuffer.limit(0) // Initially no data to read
    }

    private fun loadCertificate(certInputStream: InputStream): X509Certificate {
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(certInputStream) as X509Certificate
    }

    private fun loadPrivateKey(keyInputStream: InputStream): PrivateKey {
        val pemReader = PemReader(InputStreamReader(keyInputStream))
        val pemObject = pemReader.readPemObject()
        val keySpec = PKCS8EncodedKeySpec(pemObject.content)
        val kf = KeyFactory.getInstance("RSA")
        return kf.generatePrivate(keySpec)
    }



    private fun encryptPayload(payload: ByteArray): ByteArray? {
        appSendBuffer.clear()
        netSendBuffer.clear()
        appSendBuffer.put(payload)
        appSendBuffer.flip()

        val result = sslEngine.wrap(appSendBuffer, netSendBuffer)
        return if (result.status == SSLEngineResult.Status.OK) {
            netSendBuffer.flip()
            val encrypted = ByteArray(netSendBuffer.remaining())
            netSendBuffer.get(encrypted)
            encrypted
        } else {
            throw SSLException("SSL wrap error: ${result.status}")
        }
    }
    
    companion object {
        init {
            // Required for loading PEM private key
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun decryptMessage(encryptedMsg: ByteArray): ByteArray? {
        netReceiveBuffer.compact()
        netReceiveBuffer.put(encryptedMsg)
        netReceiveBuffer.flip()

        val result = sslEngine.unwrap(netReceiveBuffer, appReceiveBuffer)
        return when (result.status) {
            SSLEngineResult.Status.OK -> {
                appReceiveBuffer.flip()
                val decrypted = ByteArray(appReceiveBuffer.remaining())
                appReceiveBuffer.get(decrypted)
                appReceiveBuffer.compact()
                decrypted
            }
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                netReceiveBuffer.compact()
                null // Need more data
            }
            else -> throw SSLException("SSL unwrap error: ${result.status}")
        }
    }
}
