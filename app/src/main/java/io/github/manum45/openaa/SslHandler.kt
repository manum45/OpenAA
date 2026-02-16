/**
 * this file is generated with Gemini based on
 * https://github.com/tomasz-grobelny/AACS
 *
 * License: GPLv3
 */

package io.github.manum45.openaa

import android.content.Context
import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.util.io.pem.PemReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.X509TrustManager
import kotlin.collections.get
import kotlin.compareTo
import kotlin.experimental.or
import kotlin.run
import kotlin.text.clear

class SslHandler (private val context: Context) {

    // Buffers for SSLEngine: appbuffers are unencrypted, netbuffers are encrypted
    private val appSendBuffer: ByteBuffer = ByteBuffer.allocate(16384)
    private val netSendBuffer: ByteBuffer = ByteBuffer.allocate(32768)
    private val appReceiveBuffer: ByteBuffer = ByteBuffer.allocate(16384)
    private val netReceiveBuffer: ByteBuffer = ByteBuffer.allocate(32768)

    private lateinit var sslEngine: SSLEngine

    fun getSslEngineStatus() : SSLEngineResult.HandshakeStatus {
        return sslEngine.handshakeStatus;
    }

    fun initializeSslContext() {
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



    fun encryptPayload(payload: ByteArray): ByteArray? {
        val status = sslEngine.handshakeStatus
        if (status != SSLEngineResult.HandshakeStatus.FINISHED && status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            Log.e(TAG, "Cannot encrypt payload: Handshake is not complete. Status: $status")
            return null // Fail explicitly if handshake is still in progress
        }

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

    fun decryptMessage(encryptedMsg: ByteArray): ByteArray? {
        /// TODO: should we clear() here instead of compact? Can there be partial messages here?
        netReceiveBuffer.compact()
        netReceiveBuffer.put(encryptedMsg)
        netReceiveBuffer.flip()

        appReceiveBuffer.clear()

        val result = sslEngine.unwrap(netReceiveBuffer, appReceiveBuffer)
        return when (result.status) {
            SSLEngineResult.Status.OK -> {
                val decrypted = ByteArray(appReceiveBuffer.remaining())
                appReceiveBuffer.get(decrypted)
                decrypted
            }
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                netReceiveBuffer.compact()
                null // Need more data
            }
            else -> throw SSLException("SSL unwrap error: ${result.status}")
        }
    }


    private var handshakeStarted = false
    // returns 0 if handshake is still ongoing, 1 if it is done, and -1 if an error occurred
    // this function is supposed to be called until the return value is 1
    // second return value is the number of bytes provided by the sslengine to be sent
    fun performSslHandshake(handshakeData: ByteArray, sendData: ByteArray, ): Pair<Int, Int> {

        var numSendBytes = 0

        if (!handshakeStarted) {
            // this is needed so that the ssl engine status is not always NOT_HANDSHAKING
            sslEngine.beginHandshake()
            handshakeStarted = true
            Log.d(TAG, "SslHandshake: Beginning. Initial status: ${sslEngine.handshakeStatus}")
        }

        // Put the newly received data into the network buffer for the engine to process.
        if (handshakeData.isNotEmpty()) {
            netReceiveBuffer.compact() // Make space for new data
            netReceiveBuffer.put(handshakeData)
            netReceiveBuffer.flip() // Prepare for reading
            Log.d(TAG, "SslHandshake: Fed ${handshakeData.size} bytes to netReceiveBuffer.")
        }

        // The handshake loop. This will run as long as the engine can make progress
        // without waiting for more network data.
        while (true) {
            val status = sslEngine.handshakeStatus
            Log.d(TAG, "SslHandshake: Loop start. Status: $status, Bytes produced: $numSendBytes")


            when (status) {
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    // The engine needs to process incoming data.
                    if (!netReceiveBuffer.hasRemaining()) {
                        // We need to unwrap, but we have no data. This means we must wait
                        // for the other side to send the next message.
                        Log.d(TAG, "SslHandshake: NEED_UNWRAP but no data. Waiting for network.")
                        // We cannot make any more progress, so we exit and indicate no data was produced.
                        return Pair(0, numSendBytes)
                    }

                    val result = sslEngine.unwrap(netReceiveBuffer, appReceiveBuffer)
                    Log.d(TAG,"SslHandshake: Unwrap result: ${result.status}, new handshake status: ${result.handshakeStatus}"                    )

                    if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        // The data packet we have is incomplete. We must wait for more data.
                        Log.d(
                            TAG,
                            "SslHandshake: Unwrap got BUFFER_UNDERFLOW. Waiting for more network data."
                        )
                        return Pair(0, numSendBytes)
                    }
                    // After a successful unwrap, the status might have changed (e.g., to NEED_WRAP).
                    // The 'while(true)' loop will now re-evaluate the new status.
                }

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    appSendBuffer.clear()
                    appSendBuffer.flip()
                    netSendBuffer.clear()
                    val result = sslEngine.wrap(appSendBuffer, netSendBuffer)
                    Log.d(TAG,"SslHandshake: Wrap result: ${result.status}, new handshake status: ${result.handshakeStatus}")

                    if (result.bytesProduced() > 0) {
                        netSendBuffer.flip()
                        val bytesToSend = netSendBuffer.remaining()
                        if (numSendBytes + bytesToSend > sendData.size) {
                            Log.e(TAG, "SslHandshake: Error: not enough space in sendData buffer.")
                            return Pair(-1, numSendBytes) // Error and exit
                        }
                        // Copy new data into the outgoing buffer
                        netSendBuffer.get(sendData, numSendBytes, bytesToSend)
                        numSendBytes += bytesToSend
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    Log.d(TAG, "SslHandshake: NEED_TASK. Running delegated task.")
                    sslEngine.delegatedTask?.run()
                    // The status will change after the task is run. The loop continues.
                    /// TODO: should we not block here?
                }

                SSLEngineResult.HandshakeStatus.FINISHED,
                SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> {
                    /// note: this case can never occur, only the wrap result can contain status FINISHED
                    /// handling see in case NEED WRAP
                    Log.i(TAG, "SslHandshake: FINISHED.")
                    return Pair(1, numSendBytes)
                }
            }
        }
    }

}