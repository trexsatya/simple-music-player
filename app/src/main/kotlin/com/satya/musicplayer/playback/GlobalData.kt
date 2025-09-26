package com.satya.musicplayer.playback

import androidx.lifecycle.MutableLiveData

object GlobalData {
    val playbackFileContent = MutableLiveData("")
    val playbackFileName = MutableLiveData("[Playback File]")
    val playbackFileEnabled = MutableLiveData(true)
}
