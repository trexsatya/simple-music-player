@file:UnstableApi

package com.satya.musicplayer.playback.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import com.satya.musicplayer.Utils.Companion.extractFlexibleTimestamp
import com.satya.musicplayer.Utils.Companion.getMatching
import com.satya.musicplayer.Utils.Companion.parseTimestamp
import com.satya.musicplayer.Utils.Companion.toMilliSeconds
import com.satya.musicplayer.activities.MainActivity
import com.satya.musicplayer.extensions.broadcastUpdateWidgetState
import com.satya.musicplayer.extensions.config
import com.satya.musicplayer.extensions.currentMediaItems
import com.satya.musicplayer.extensions.setRepeatMode
import com.satya.musicplayer.helpers.SEEK_INTERVAL_MS
import com.satya.musicplayer.playback.*
import com.satya.musicplayer.playback.PlaybackService.Companion.updatePlaybackInfo
import com.satya.musicplayer.playback.getCustomLayout
import com.satya.musicplayer.playback.getMediaSessionCallback
import java.time.Duration
import java.time.Instant
import java.util.Timer
import java.util.TimerTask
import kotlin.math.absoluteValue

private const val PLAYER_THREAD = "PlayerThread"
const val PAUSE_AFTER_MS = 30000
const val RESUME_AFTER_MS = 30000

/**
 * Initializes player and media session.
 *
 * All player operations are handled on a separate handler thread to avoid slowing down the main thread.
 * See https://developer.android.com/guide/topics/media/exoplayer/hello-world#a-note-on-threading for more info.
 */
internal fun PlaybackService.initializeSessionAndPlayer(handleAudioFocus: Boolean, handleAudioBecomingNoisy: Boolean, skipSilence: Boolean) {
    playerThread = HandlerThread(PLAYER_THREAD).also { it.start() }
    playerHandler = Handler(playerThread.looper)
    player = initializePlayer(handleAudioFocus, handleAudioBecomingNoisy, skipSilence)
    playerListener = getPlayerListener()

    mediaSession = MediaLibraryService.MediaLibrarySession.Builder(this, player, getMediaSessionCallback())
        .setSessionActivity(getSessionActivityIntent())
        .build()

    withPlayer {
        addListener(playerListener)
        setRepeatMode(config.playbackSetting)
        setPlaybackSpeed(config.playbackSpeed)
        shuffleModeEnabled = config.isShuffleEnabled
        mediaSession.setCustomLayout(getCustomLayout())
        SimpleEqualizer.setupEqualizer(this@initializeSessionAndPlayer, player)
    }
    timer = Timer()
    timer.schedule(object : TimerTask() {
        override fun run() {
            withPlayer {
                var commandedToPause = false
                val enabled = GlobalData.playbackFileEnabled.value ?: false
                if(enabled && PlaybackService.currentItemPlaybackTimestamps.isNotEmpty()) {
                    val currentPosition = player.currentPosition
                    val x = getMatching(PlaybackService.currentItemPlaybackTimestamps, { !PlaybackService.processedTimestamps.contains(it) && it.first <= currentPosition }){ it == null || it.first > currentPosition}
                    x?.second?.trim()?.startsWith("stop")?.let {
                        commandedToPause = true
                        sleepTime = extractFlexibleTimestamp(x.second)?.let { parseTimestamp(it) }?.let { toMilliSeconds(it) } ?: Int.MAX_VALUE
                    }
                    x?.let {  PlaybackService.processedTimestamps.add(it) }
                    x?.second?.let { txt ->
                        updatePlaybackContent(txt)
                    }
                }
                if(!player.isPlaying) {
                    if(lastPausedAt != null && Duration.between(lastPausedAt, Instant.now()).abs().toMillis() >= sleepTime) {
                        player.play()
                        sleepTime = RESUME_AFTER_MS
                    }
                } else if (player.duration > 0 && player.currentPosition >= player.duration) {
                    // Music Item finished
                    playersLastPosition = 0
                    PlaybackService.processedTimestamps.clear()
                } else if (commandedToPause || (player.currentPosition - playersLastPosition).absoluteValue > sleepTime) {
                    // Time to pause
                    player.pause()
                    playersLastPosition = player.currentPosition
                    lastPausedAt = Instant.now()
                } else if (player.currentPosition < playersLastPosition) {
                    // Re-winded
                    PlaybackService.processedTimestamps.clear()
                    playersLastPosition = player.currentPosition
                }
            }
        }
    }, 0, pollingInterval)
}


internal fun PlaybackService.rewind() {
    withPlayer {
            player.seekTo(player.currentPosition - PAUSE_AFTER_MS)
    }
}

private fun updatePlaybackContent(txt: String) {
    GlobalData.playbackFileContent.postValue(txt)
}

internal fun PlaybackService.mediaNextButtonClicked() {
    val enabled = GlobalData.playbackFileEnabled.value ?: false
    if(enabled) {
        seekRandom()
    } else {
        rewind()
    }
}

internal fun PlaybackService.replayLastRandom() {
    lastRandomTimestamp?.let { playRandomTimestamp(it) }
}

internal fun PlaybackService.seekRandom() {
    lastPausedAt = Instant.now()
    if(PlaybackService.currentItemPlaybackTimestamps.isEmpty()) {
        player.seekTo((0..player.duration / 1000).random()*1000)
        return
    }
    val random = PlaybackService.currentItemPlaybackTimestamps.filter { it.second.trim().startsWith("stop") }.randomOrNull()
    if (random == null) {
        player.seekTo(0)
    } else {
        playRandomTimestamp(random)
        lastRandomTimestamp = random
    }
    player.play()
}

private fun PlaybackService.playRandomTimestamp(random: Triple<Int, String, Boolean>) {
    PlaybackService.processedTimestamps.add(random)
    player.seekTo(random.first.toLong())
    updatePlaybackContent(random.second)
}

internal fun PlaybackService.mediaPreviousButtonClicked() {
    mediaNextButtonClicked()
}

private fun PlaybackService.initializePlayer(handleAudioFocus: Boolean, handleAudioBecomingNoisy: Boolean, skipSilence: Boolean): SimpleMusicPlayer {
    val renderersFactory = AudioOnlyRenderersFactory(context = this)
    return SimpleMusicPlayer(
        ExoPlayer.Builder(this, renderersFactory)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setHandleAudioBecomingNoisy(handleAudioBecomingNoisy)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                handleAudioFocus
            )
            .setSkipSilenceEnabled(
                // TODO: Enable when https://github.com/androidx/media/issues/712 is resolved.
                //  See https://github.com/SimpleMobileTools/Simple-Music-Player/issues/604
                false //skipSilence
            )
            .setSeekBackIncrementMs(SEEK_INTERVAL_MS)
            .setSeekForwardIncrementMs(SEEK_INTERVAL_MS)
            .setLooper(playerThread.looper)
            .build()
    )
}

private fun Context.getSessionActivityIntent(): PendingIntent {
    return PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

internal fun PlaybackService.updatePlaybackState() {
    withPlayer {
        updatePlaybackInfo(player)
        broadcastUpdateWidgetState()
        val currentMediaItem = currentMediaItem
        if (currentMediaItem != null) {
            mediaItemProvider.saveRecentItemsWithStartPosition(
                mediaItems = currentMediaItems,
                current = currentMediaItem,
                startPosition = currentPosition
            )
        }
    }
}
