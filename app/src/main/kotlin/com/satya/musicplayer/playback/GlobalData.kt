package com.satya.musicplayer.playback

import androidx.lifecycle.MutableLiveData

object GlobalData {
    val playbackFileContent = MutableLiveData("")
    val playbackFileName = MutableLiveData("[Playback File]")
    val playbackFileEnabled = MutableLiveData(true)
    val randomSeekEnabled = MutableLiveData(true)
    val pauseDurationSeconds = MutableLiveData(50)
    val playDurationSeconds = MutableLiveData(30)
    val manualPlayPause = MutableLiveData(false)
}
