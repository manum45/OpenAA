package io.github.manum45.openaa

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * Captures the phone screen and streams it to the Android Auto video channel.
 * Requires API 21+ for MediaProjection and API 23+ for some MediaCodec features.
 */
class SystemVideoStreamer(
    private val context: Context,
    private val messageHandler: MessageHandler,
    @Volatile private var mediaProjection: MediaProjection? = null
) : Runnable {

    private var channelHandler: VideoChannelHandler? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var width = 800
    private var height = 480
    private var dpi = 160

    fun setMediaProjection(projection: MediaProjection) {
        Log.d("SystemVideoStreamer", "setMediaProjection called")
        this.mediaProjection = projection
    }

    override fun run() {
        Log.d("SystemVideoStreamer", "SystemVideoStreamer thread started")
        runBlocking {
            while (true) {
                if (channelHandler == null || !channelHandler!!.open) {
                    queryChannels()
                    delay(500)
                } else {
                    if (mediaProjection != null && channelHandler?.setup == true) {
                        captureAndStream()
                    } else {
                        if (mediaProjection == null) {
                            Log.v("SystemVideoStreamer", "Waiting for MediaProjection...")
                        }
                        delay(1000)
                    }
                }
            }
        }
    }

    private fun queryChannels() {
        channelHandler = messageHandler.getReadyVideoChannel()
        if (channelHandler != null) {
            Log.d("SystemVideoStreamer", "Video channel found")
            // Try to extract resolution from the channel descriptor if possible
            // For now, using defaults. In a real scenario, we'd parse channel.avChannel.videoConfigsList
        }
    }

    private suspend fun captureAndStream() {
        Log.d("SystemVideoStreamer", "Starting screen capture and stream")
        
        val projection = mediaProjection ?: return
        
        try {
            prepareEncoder()
            
            virtualDisplay = projection.createVirtualDisplay(
                "OpenAA-ScreenCapture",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            mediaCodec?.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var frameCount = 0L
            var firstFrameTimeUs: Long = -1

            withContext(Dispatchers.IO) {
                while (channelHandler?.open == true && mediaProjection != null) {
                    val outputBufferId = mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    if (outputBufferId >= 0) {
                        val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferId)
                        if (outputBuffer != null) {
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)
                            
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                Log.d("SystemVideoStreamer", "Sending Codec Config (SPS/PPS): ${data.size} bytes")
                                channelHandler?.sendVideoData(data, 0)
                            } else {
                                if (firstFrameTimeUs == -1L) {
                                    firstFrameTimeUs = bufferInfo.presentationTimeUs
                                }
                                val presentationTimeUs = bufferInfo.presentationTimeUs - firstFrameTimeUs

                                frameCount++
                                if (frameCount % 60 == 0L) {
                                    Log.d("SystemVideoStreamer", "Streaming frame $frameCount, size: ${data.size} bytes, pts: $presentationTimeUs")
                                }

                                // Prepend AUD (00 00 00 01 09 f0)
                                val audData = byteArrayOf(0, 0, 0, 1, 0x09.toByte(), 0xf0.toByte())
                                val frameWithAud = ByteArray(audData.size + data.size)
                                System.arraycopy(audData, 0, frameWithAud, 0, audData.size)
                                System.arraycopy(data, 0, frameWithAud, audData.size, data.size)

                                channelHandler?.sendVideoData(frameWithAud, presentationTimeUs)
                            }
                        }
                        mediaCodec?.releaseOutputBuffer(outputBufferId, false)
                    } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d("SystemVideoStreamer", "MediaCodec output format changed: ${mediaCodec?.outputFormat}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SystemVideoStreamer", "Error in screen capture/stream", e)
        } finally {
            stopCapture()
        }
    }

    private fun prepareEncoder() {
        Log.d("SystemVideoStreamer", "Preparing encoder for $width x $height")
        
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1000000) // 1 Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second between I-frames

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec?.createInputSurface()
    }

    private fun stopCapture() {
        Log.d("SystemVideoStreamer", "Stopping screen capture")
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            
            mediaCodec?.stop()
            mediaCodec?.release()
            mediaCodec = null
            
            inputSurface?.release()
            inputSurface = null
        } catch (e: Exception) {
            Log.e("SystemVideoStreamer", "Error stopping capture", e)
        }
    }
}
