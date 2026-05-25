package io.github.manum45.openaa

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.app.Presentation
import android.os.Bundle
import android.widget.TextView
import android.view.Gravity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
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
    private var wakeLock: PowerManager.WakeLock? = null
    private var presentation: Presentation? = null

    private var width = 800
    private var height = 480
    private var dpi = 160
    
    private var csd0: ByteArray? = null
    private var csd1: ByteArray? = null

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

    private fun prepareEncoder() {
        Log.d("SystemVideoStreamer", "Preparing encoder for $width x $height")
        
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1000000) // 1 Mbps
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second between I-frames
        
        // Use Baseline profile for maximum compatibility
        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel3)

        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = mediaCodec?.createInputSurface()
        
        mediaCodec?.start()
        Log.d("SystemVideoStreamer", "MediaCodec started")
    }

    private suspend fun captureAndStream() {
        Log.d("SystemVideoStreamer", "Starting screen capture and stream")
        
        val projection = mediaProjection ?: return
        
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenAA:StreamWakeLock")
            wakeLock?.acquire(10 * 60 * 1000L)
            Log.d("SystemVideoStreamer", "WakeLock acquired")

            prepareEncoder()
            
            Log.d("SystemVideoStreamer", "Creating VirtualDisplay...")
            virtualDisplay = projection.createVirtualDisplay(
                "OpenAA-VirtualDisplay",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or 
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                inputSurface, null, null
            )
            
            val display = virtualDisplay?.display
            Log.d("SystemVideoStreamer", "VirtualDisplay created with ID: ${display?.displayId}")

            if (display != null) {
                withContext(Dispatchers.Main) {
                    Log.d("SystemVideoStreamer", "Showing presentation on Display ${display.displayId}")
                    presentation = object : Presentation(context, display, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
                        private var timerTv: TextView? = null
                        private val handler = Handler(Looper.getMainLooper())
                        private val updateRunnable = object : Runnable {
                            override fun run() {
                                timerTv?.text = "OpenAA: ACTIVE STREAM\nTime: ${System.currentTimeMillis() / 1000}\n${width}x${height}"
                                handler.postDelayed(this, 1000)
                            }
                        }

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            val tv = TextView(context)
                            timerTv = tv
                            tv.setTextColor(Color.WHITE)
                            tv.textSize = 32f
                            tv.gravity = Gravity.CENTER
                            tv.setBackgroundColor(Color.MAGENTA) // Magenta is hard to miss
                            setContentView(tv)
                            handler.post(updateRunnable)
                        }

                        override fun onStop() {
                            handler.removeCallbacks(updateRunnable)
                            super.onStop()
                        }
                    }
                    presentation?.show()
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var frameCount = 0L
            var firstFrameTimeUs: Long = -1

            withContext(Dispatchers.IO) {
                while (channelHandler?.open == true && mediaProjection != null) {
                    // Force a keyframe every 2 seconds to ensure the car picks up the stream
                    if (frameCount % 60 == 0L) {
                        val bundle = Bundle()
                        bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        mediaCodec?.setParameters(bundle)
                    }

                    val outputBufferId = try {
                        mediaCodec?.dequeueOutputBuffer(bufferInfo, 10000) ?: -1
                    } catch (e: Exception) {
                        Log.e("SystemVideoStreamer", "Error dequeuing buffer", e)
                        -1
                    }

                    if (outputBufferId >= 0) {
                        val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferId)
                        if (outputBuffer != null) {
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.get(data)
                            
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                Log.d("SystemVideoStreamer", "Captured Codec Config (SPS/PPS)")
                                csd0 = data
                                // Also send it immediately
                                channelHandler?.sendVideoData(data, 0)
                            } else {
                                if (firstFrameTimeUs == -1L) {
                                    firstFrameTimeUs = bufferInfo.presentationTimeUs
                                }
                                val pts = bufferInfo.presentationTimeUs - firstFrameTimeUs

                                frameCount++
                                if (frameCount % 60 == 0L) {
                                    Log.d("SystemVideoStreamer", "Streaming frame $frameCount, size: ${data.size}, pts: $pts")
                                }

                                val audData = byteArrayOf(0, 0, 0, 1, 0x09, 0xf0.toByte())
                                val combined = ByteArray(audData.size + data.size)
                                System.arraycopy(audData, 0, combined, 0, audData.size)
                                System.arraycopy(data, 0, combined, audData.size, data.size)

                                channelHandler?.sendVideoData(combined, pts)
                            }
                        }
                        mediaCodec?.releaseOutputBuffer(outputBufferId, false)
                    } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d("SystemVideoStreamer", "MediaCodec format changed: ${mediaCodec?.outputFormat}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SystemVideoStreamer", "Error in stream loop", e)
        } finally {
            stopCapture()
        }
    }

    private fun stopCapture() {
        Log.d("SystemVideoStreamer", "Stopping screen capture")
        try {
            presentation?.dismiss()
            presentation = null

            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d("SystemVideoStreamer", "WakeLock released")
            }
            wakeLock = null

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
