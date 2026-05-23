package io.github.manum45.openaa

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Streams system audio capture to an Android Auto audio channel.
 * Requires API level 29+ and a valid MediaProjection.
 */
class SystemAudioStreamer(
    private val context: Context,
    private val messageHandler: MessageHandler,
    @Volatile private var mediaProjection: MediaProjection? = null
) : Runnable {

    private var channelHandler: AudioChannelHandler? = null

    init {
        Log.d("SystemAudioStreamer", "SystemAudioStreamer initialized")
    }

    fun setMediaProjection(projection: MediaProjection) {
        Log.d("SystemAudioStreamer", "setMediaProjection called with $projection")
        this.mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w("SystemAudioStreamer", "MediaProjection STOPPED")
                mediaProjection = null
            }
        }, null)
    }

    override fun run() {
        Log.d("SystemAudioStreamer", "SystemAudioStreamer thread started")
        runBlocking {
            while (true) {
                if (channelHandler == null || !channelHandler!!.open) {
                    queryChannels()
                    if (channelHandler == null) {
                        Log.v("SystemAudioStreamer", "No audio channel yet...")
                    }
                    // wait for some time
                    delay(1000)
                } else {
                    Log.d("SystemAudioStreamer", "Channel open, checking MediaProjection. Has projection: ${mediaProjection != null}")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mediaProjection != null) {
                        captureAndStream()
                    } else {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            Log.e("SystemAudioStreamer", "System audio capture requires Android 10 (API 29)+")
                            delay(5000)
                        } else if (mediaProjection == null) {
                            Log.d("SystemAudioStreamer", "Waiting for MediaProjection...")
                            delay(2000)
                        }
                    }
                }
            }
        }
    }

    private fun queryChannels() {
        channelHandler = messageHandler.getReadyMediaAudioChannel()
        if (channelHandler != null) {
            Log.d("SystemAudioStreamer", "Audio channel found")
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun captureAndStream() {
        Log.d("SystemAudioStreamer", "Starting system audio capture")

        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_IN_STEREO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = Math.max(minBufferSize, 8192)

        val projection = mediaProjection ?: return

        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION)
            .addMatchingUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .addMatchingUsage(AudioAttributes.USAGE_ALARM)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
            .excludeUid(android.os.Process.myUid())
            .build()

        val audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setAudioPlaybackCaptureConfig(config)
            .setBufferSizeInBytes(bufferSize)
            .build()

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("SystemAudioStreamer", "AudioRecord initialization failed")
            delay(2000)
            return
        }

        val buffer = ByteArray(bufferSize)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            audioRecord.startRecording()
            Log.d("SystemAudioStreamer", "AudioRecord started recording")
            
            // Mute the phone speaker
            Log.d("SystemAudioStreamer", "Muting phone speakers (STREAM_MUSIC)")
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)

            var totalBytesRead = 0L
            withContext(Dispatchers.IO) {
                while (channelHandler?.open == true && mediaProjection != null) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        totalBytesRead += bytesRead
                        
                        // Check if it's silence
                        var sum = 0L
                        for (i in 0 until bytesRead step 2) {
                            val sample = (if (i + 1 < bytesRead) (buffer[i].toInt() and 0xFF) or (buffer[i+1].toInt() shl 8) else buffer[i].toInt()).toShort()
                            sum += Math.abs(sample.toInt())
                        }
                        val avg = if (bytesRead > 0) sum / (bytesRead / 2) else 0

                        if (totalBytesRead % 192000 < buffer.size) { // Log every ~1 second of audio
                             Log.d("SystemAudioStreamer", "Read $bytesRead bytes. Total: $totalBytesRead. Avg Amplitude: $avg")
                             if (avg == 0L) {
                                 Log.w("SystemAudioStreamer", "Captured SILENCE! Check permissions and if another app is playing.")
                             }
                        }

                        val chunk = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                        channelHandler?.sendAudioData(chunk)
                    } else if (bytesRead < 0) {
                        Log.e("SystemAudioStreamer", "Error reading audio: $bytesRead")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SystemAudioStreamer", "Error streaming system audio", e)
        } finally {
            // Unmute the phone speaker
            Log.d("SystemAudioStreamer", "Unmuting phone speakers")
            try {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            } catch (e: Exception) {
                Log.e("SystemAudioStreamer", "Error unmuting speakers", e)
            }

            try {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
            } catch (e: Exception) {
                Log.e("SystemAudioStreamer", "Error stopping audioRecord", e)
            }
            audioRecord.release()
            Log.d("SystemAudioStreamer", "Stopped system audio capture")
        }
    }
}
