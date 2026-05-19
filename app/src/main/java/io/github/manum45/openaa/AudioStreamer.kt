package io.github.manum45.openaa

import android.content.Context
import android.util.Log

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.InputStream

class AudioStreamer(private val context: Context, private val messageHandler: MessageHandler) : Runnable {

    private var channelHandler: AudioChannelHandler? = null

    override fun run() {
        runBlocking {
            while (true) {
                if (channelHandler == null || !channelHandler!!.open) {
                    queryChannels()
                    // wait for some time
                    delay(200)
                } else {
                    streamRawFile(R.raw.arcadia)
                }
            }
        }
    }

    fun queryChannels() {
        channelHandler = messageHandler.getReadyMediaAudioChannel()
        if (channelHandler != null) {
            Log.d("AudioStreamer", "Audio channel found")
        }
    }

    suspend fun streamRawFile(resourceId: Int) {
        Log.d("AudioStreamer", "Streaming raw file with resource ID: $resourceId")
        withContext(Dispatchers.IO) {
            val inputStream: InputStream = context.resources.openRawResource(resourceId)
            val bufferSize = 8192 // Larger chunks for better efficiency
            val buffer = ByteArray(bufferSize)
            val bytesPerSecond = 192000.0 // 48kHz 16-bit stereo
            /// note: streamed file is lower sample rate, but since the channel is openend as
            /// 48kHz

            var totalBytesSent = 0L
            val startTimeNano = System.nanoTime()

            try {
                var bytesRead = inputStream.read(buffer)
                while (bytesRead != -1) {

                    if(channelHandler?.open != true){
                        Log.d("AudioStreamer", "AudioStreamer: Channel not open, stopping streaming")
                        break
                    }

                    val chunk = if (bytesRead == bufferSize) buffer else buffer.copyOf(bytesRead)
                    channelHandler?.sendAudioData(chunk)
                    totalBytesSent += bytesRead

                    // Calculate how much time should have passed for the bytes sent so far
                    val expectedTimeNano = (totalBytesSent * 1_000_000_000L / bytesPerSecond.toLong())
                    val actualTimeNano = System.nanoTime() - startTimeNano

                    // Allow for a 10ms "lead" buffer to absorb system jitter
                    val leadNano = 10_000_000L
                    val sleepNano = expectedTimeNano - actualTimeNano - leadNano

                    if (sleepNano > 0) {
                        delay(sleepNano / 1_000_000)
                    }

                    bytesRead = inputStream.read(buffer)
                }
            } catch (e: Exception) {
                Log.e("AudioStreamer", "Error streaming audio", e)
            } finally {
                inputStream.close()
            }
        }
    }
}