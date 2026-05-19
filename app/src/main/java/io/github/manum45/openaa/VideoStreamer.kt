package io.github.manum45.openaa

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class VideoStreamer(private val context: Context, private val messageHandler: MessageHandler) : Runnable {

    private var channelHandler: VideoChannelHandler? = null

    override fun run() {
        runBlocking {
            while (true) {
                if (channelHandler == null || !channelHandler!!.open) {
                    queryChannels()
                    delay(500)
                } else {
                    // Try to stream from assets. Make sure there is a video.mov file there.
                    streamVideoFromAssets(R.raw.big_buck_bunny_480p_h264)
                    delay(1000)
                }
            }
        }
    }

    private fun queryChannels() {
        channelHandler = messageHandler.getReadyVideoChannel()
        if (channelHandler != null) {
            Log.d("VideoStreamer", "Video channel found")
        }
    }

    suspend fun streamVideoFromAssets(resourceId: Int) {
        Log.d("VideoStreamer", "Streaming raw file with resource ID: $resourceId")
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                val assetFileDescriptor = context.resources.openRawResourceFd(resourceId)
                extractor.setDataSource(assetFileDescriptor.fileDescriptor, assetFileDescriptor.startOffset, assetFileDescriptor.length)
                
                var videoTrackIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME)
                    if (mime?.startsWith("video/") == true) {
                        videoTrackIndex = i
                        extractor.selectTrack(i)
                        
                        // Send SPS/PPS if available
                        sendCsd(format)
                        break
                    }
                }

                if (videoTrackIndex == -1) {
                    Log.e("VideoStreamer", "No video track found in raw file with resource ID: $resourceId")
                    return@withContext
                }

                val buffer = ByteBuffer.allocate(1024 * 1024)
                val startTimeNano = System.nanoTime()
                var firstSampleTimeUs: Long = -1

                while (true) {
                    if (channelHandler?.open != true) {
                        Log.d("VideoStreamer", "Channel closed, stopping video stream")
                        break
                    }

                    val sampleTimeUs = extractor.sampleTime
                    if (sampleTimeUs < 0) break
                    
                    if (firstSampleTimeUs == -1L) firstSampleTimeUs = sampleTimeUs

                    buffer.clear()
                    val bytesRead = extractor.readSampleData(buffer, 0)
                    if (bytesRead < 0) break

                    val data = ByteArray(bytesRead)
                    buffer.get(data)
                    
                    // Convert length-prefixed NALUs to Annex-B if necessary
                    // (MP4/MOV uses length-prefixed)
                    val annexBData = convertToAnnexB(data)
                    channelHandler?.sendVideoData(annexBData)

                    // Pacing
                    val expectedTimeUs = sampleTimeUs - firstSampleTimeUs
                    val actualTimeUs = (System.nanoTime() - startTimeNano) / 1000
                    val sleepUs = expectedTimeUs - actualTimeUs
                    
                    if (sleepUs > 0) {
                        delay(sleepUs / 1000)
                    }

                    extractor.advance()
                }

            } catch (e: Exception) {
                Log.e("VideoStreamer", "Error streaming video", e)
            } finally {
                extractor.release()
            }
        }
    }

    private fun sendCsd(format: MediaFormat) {
        val csd0 = format.getByteBuffer("csd-0") // SPS
        val csd1 = format.getByteBuffer("csd-1") // PPS
        
        csd0?.let {
            val sps = ByteArray(it.remaining())
            it.get(sps)
            channelHandler?.sendVideoData(ensureAnnexB(sps))
        }
        csd1?.let {
            val pps = ByteArray(it.remaining())
            it.get(pps)
            channelHandler?.sendVideoData(ensureAnnexB(pps))
        }
    }

    private fun ensureAnnexB(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return data
        }
        val result = ByteArray(data.size + 4)
        result[0] = 0
        result[1] = 0
        result[2] = 0
        result[3] = 1
        System.arraycopy(data, 0, result, 4, data.size)
        return result
    }

    private fun convertToAnnexB(data: ByteArray): ByteArray {
        val result = data.copyOf()
        var offset = 0
        while (offset + 4 <= result.size) {
            val length = ((result[offset].toInt() and 0xFF) shl 24) or
                         ((result[offset + 1].toInt() and 0xFF) shl 16) or
                         ((result[offset + 2].toInt() and 0xFF) shl 8) or
                         (result[offset + 3].toInt() and 0xFF)
            
            if (offset + 4 + length > result.size) break

            result[offset] = 0
            result[offset + 1] = 0
            result[offset + 2] = 0
            result[offset + 3] = 1
            offset += 4 + length
        }
        return result
    }
}
