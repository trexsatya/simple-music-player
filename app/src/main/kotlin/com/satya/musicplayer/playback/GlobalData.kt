package com.satya.musicplayer.playback

import androidx.lifecycle.MutableLiveData
import com.satya.musicplayer.FixedSizeQueue
import com.satya.musicplayer.PlaybackCommand

object GlobalData {
    val playbackCountdown = MutableLiveData("")
    val playbackFileContent = MutableLiveData("")
    val playbackFileName = MutableLiveData("[Playback File]")
    val playbackFileEnabled = MutableLiveData(true)
    val randomSeekEnabled = MutableLiveData(true)
    val pauseDurationSeconds = MutableLiveData(50)
    val playDurationSeconds = MutableLiveData(30)
    val manualPlayPause = MutableLiveData(false)
    val questionAnswerSetting = MutableLiveData(0)
    var commandHistory = MutableLiveData(FixedSizeQueue<IndexedValue<PlaybackCommand>>(20))
}
