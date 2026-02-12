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


    private var handshakeStarted = false
    // this function is supposed to be called until the return value is -1
    // return value is the number of bytes provided by the sslengine to be sent
    // if an error occurs, the return value is -2
    fun performSslHandshake(handshakeData: ByteArray, sendData: ByteArray): Int {
        //netReceiveBuffer.compact()
        //netReceiveBuffer.put(handshakeData)
        //netReceiveBuffer.flip()

        //sslEngine.unwrap(netReceiveBuffer, appReceiveBuffer)

        //if(sslEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
        //    /// according to Gemini, the appSendBuffer is ignored when wrap is called during
        //    /// handshake, because ssl produces data for the handshake itself
        //    val result = sslEngine.wrap(appSendBuffer, netSendBuffer)
        //    if (result.bytesProduced() > 0) {
        //        netSendBuffer.flip()
        //        val bytesToSend = ByteArray(netSendBuffer.remaining())
        //        netSendBuffer.get(bytesToSend)
        //        return bytesToSend
        //    }
        //    else {
        //        Log.e(TAG, "Ssl handshake: Error: no data to be sent")
        //        return null
        //    }
        //}
        //else {
        //    Log.e(TAG, "Ssl handshake: Error: wrong status")
        //    return null
        //}

        var returnValue = 0

        if (!handshakeStarted) {
            sslEngine.beginHandshake()
            handshakeStarted = true
        }

        netReceiveBuffer.compact()
        netReceiveBuffer.put(handshakeData)
        netReceiveBuffer.flip()

        // sslEngine.unwrap(netReceiveBuffer, appReceiveBuffer)

        if(sslEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
            Log.d(TAG, "Ssl handshake: Unwrap")
            if (netReceiveBuffer.hasRemaining()) {
                sslEngine.unwrap(netReceiveBuffer, appReceiveBuffer)
            } else {
                netReceiveBuffer.compact()
            }
        }

        if(sslEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
            Log.d(TAG, "Ssl handshake: Need more data")
        }

        if(sslEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
            Log.d(TAG, "Ssl handshake: Wrap")
            netSendBuffer.clear()
            /// according to Gemini, the appSendBuffer is ignored when wrap is called during
            /// handshake, because ssl produces data for the handshake itself
            val result = sslEngine.wrap(appSendBuffer, netSendBuffer)
            if (result.bytesProduced() > 0) {
                netSendBuffer.flip()
                returnValue = netSendBuffer.remaining()
                if(returnValue <= sendData.size) {
                    netSendBuffer.get(sendData, 0, returnValue)
                } else {
                    Log.e(TAG, "Ssl handshake: Error: not enough space in sendData")
                    returnValue = -2
                }
            }
        }

        if(sslEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            // Log.d(TAG, "Ssl handshake: Task")
            // sslEngine.delegatedTask?.run()
            // returnValue = 0
            Log.e(TAG, "Ssl handshake: Task not implemented")
            returnValue = -2
        }

        if(sslEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
            Log.d(TAG, "Ssl handshake: Finished")
            if(returnValue == 0) {
                returnValue = -1
            }
        }

        if(sslEngine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            Log.e(TAG, "Ssl handshake: Not Handshaking (unexpected sequence)")
            returnValue = -2
        }

        return returnValue
    }

}