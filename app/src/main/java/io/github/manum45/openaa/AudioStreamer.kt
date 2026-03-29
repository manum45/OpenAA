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
            val buffer = ByteArray(2048) // Small chunks to prevent jitter

            try {
                var bytesRead = inputStream.read(buffer)
                while (bytesRead != -1) {

                    if(!channelHandler!!.open){
                        Log.d(TAG, "AudioStreamer: Channel not open, stopping streaming")
                        break
                    }

                    val chunk = buffer.copyOf(bytesRead)

                    channelHandler?.sendAudioData(chunk)

                    // Throttle the sending to match audio playback speed
                    // (e.g., if sending 48kHz 16bit stereo, that's ~192KB/s)
                    // For a 2048 byte buffer, wait ~10ms
                    /// TODO: how to do real time streaming?
                    //delay(1)

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