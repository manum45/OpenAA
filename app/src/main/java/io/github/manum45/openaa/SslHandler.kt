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

    // Buffers for SSLEngine
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




    fun performSslHandshake(handshakeData: ByteArray): ByteArray? {
        sslEngine.beginHandshake()

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
                        Log.d(TAG, "Error: not enough data, current implementation can't handle partial inpu")
                        return null // Need more data from peer
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

                        return bytesToSend
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
                    return null
                }
            }
        }
    }

}