package io.github.manum45.openaa

import android.util.Log
import tag.aas.AudioConfigOuterClass
import tag.aas.ChannelOuterClass

class AudioChannelHandler(channel: ChannelOuterClass.Channel): IChannelHandler {

    var audioConfigs: List<AudioConfigOuterClass.AudioConfig> = channel.mediaChannel.audioConfigsList

    init {
        Log.d(TAG, "AudioChannelHandler: Initializing for channel ${channel.channelId}")
    }
    override fun handleMessage(message: Message) {
        TODO("Not yet implemented")
    }

    override fun disconnected(clientId: Int) {
        TODO("Not yet implemented")
    }
}