package com.satya.musicplayer.playback

import androidx.lifecycle.MutableLiveData

const val DEFAULT_REPEAT_COUNT = 1
const val DEFAULT_RANDOM_ENABLED_SETTING = false

class Event<T>(val content: T)

const val DEFAULT_PLAY_DURATION_SECONDS = 30
const val DEFAULT_MANUAL_RESUME = false

object GlobalData {
    val playbackCountdown = MutableLiveData("")
    val playbackFileContent = MutableLiveData("")
    val playbackFileName = MutableLiveData("[Playback File]")
    val playbackFileEnabled = MutableLiveData(true)
    val currentlyPlayingQA = MutableLiveData<PlaybackService.QA>(null)
    val randomSeekEnabled = MutableLiveData(DEFAULT_RANDOM_ENABLED_SETTING)
    val slowRepeatEnabled = MutableLiveData(true)
    val pauseDurationSeconds = MutableLiveData((PlaybackService.DEFAULT_STOP_INTERVAL_MS / 1000).toInt())
    val repeatCount = MutableLiveData(DEFAULT_REPEAT_COUNT)
    val repeatRemaining = MutableLiveData(0)
    val playedQaCommandIds: MutableLiveData<Set<Int>> = MutableLiveData(setOf())
    val playedTrackId = MutableLiveData(-1)
    val playDurationSeconds = MutableLiveData(DEFAULT_PLAY_DURATION_SECONDS)
    val manualPlayPause = MutableLiveData(false)
    val questionAnswerSetting = MutableLiveData(0)
    val message = MutableLiveData<Event<String>>()
    val manualResumeEnforced = MutableLiveData(DEFAULT_MANUAL_RESUME)
}
