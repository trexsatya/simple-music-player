package com.satya.musicplayer.playback

import androidx.lifecycle.MutableLiveData
import com.satya.musicplayer.PlaybackCommand

const val DEFAULT_REPEAT_COUNT = 1

class Event<T>(val content: T)

object GlobalData {
    val playbackCountdown = MutableLiveData("")
    val playbackFileContent = MutableLiveData("")
    val playbackFileName = MutableLiveData("[Playback File]")
    val playbackFileEnabled = MutableLiveData(true)
    val currentlyPlayingQA = MutableLiveData<PlaybackService.QA>(null)
    val randomSeekEnabled = MutableLiveData(true)
    val repeatCommandEnabled = MutableLiveData(true)
    val pauseDurationSeconds = MutableLiveData(50)
    val repeatCount = MutableLiveData(DEFAULT_REPEAT_COUNT)
    val repeatRemaining = MutableLiveData(0)
    val playedQaCommandIds: MutableLiveData<Set<Int>> = MutableLiveData(setOf())
    val playedTrackId = MutableLiveData(-1)
    val playDurationSeconds = MutableLiveData(30)
    val manualPlayPause = MutableLiveData(false)
    val questionAnswerSetting = MutableLiveData(0)
    val message = MutableLiveData<Event<String>>()
}
