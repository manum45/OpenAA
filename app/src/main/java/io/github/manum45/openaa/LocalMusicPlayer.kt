package io.github.manum45.openaa

import android.content.Context
import android.media.MediaPlayer

class LocalMusicPlayer(var context: Context) {
    var mediaPlayer : MediaPlayer? = null

    fun PlayTestMusic() {
        // https://developer.android.com/media/platform/mediaplayer/basics?hl=de
        mediaPlayer = MediaPlayer.create(context, R.raw.arcadia)
        mediaPlayer?.start() // no need to call prepare(); create() does that for you
    }
}