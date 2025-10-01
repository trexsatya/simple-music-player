@file:UnstableApi

package com.satya.musicplayer.playback.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import com.satya.musicplayer.PlaybackCommand
import com.satya.musicplayer.Utils
import com.satya.musicplayer.activities.MainActivity
import com.satya.musicplayer.extensions.broadcastUpdateWidgetState
import com.satya.musicplayer.extensions.config
import com.satya.musicplayer.extensions.currentMediaItems
import com.satya.musicplayer.extensions.setRepeatMode
import com.satya.musicplayer.helpers.SEEK_INTERVAL_MS
import com.satya.musicplayer.playback.*
import com.satya.musicplayer.playback.GlobalData.manualPlayPause
import com.satya.musicplayer.playback.PlaybackService.Companion.getEffectivePlaybackCommands
import com.satya.musicplayer.playback.PlaybackService.Companion.updatePlaybackInfo
import com.satya.musicplayer.playback.getCustomLayout
import com.satya.musicplayer.playback.getMediaSessionCallback
import java.time.Instant

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
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    if (manualPlayPause.value == true) {
                        // Only restart random cycle if it was a manual resume
                        manualPlayPause.postValue(false)
                        Log.d("PlaybackService", "Manual resume -> restarting cycle")
                        cancelScheduledPauseResume()
                        scheduler.post { seekRandomOrPlayAnswer() }
                    } else {
                        // Auto-resume from scheduler -> do nothing extra
                        Log.d("PlaybackService", "Auto-resume -> continuing cycle")
                    }
                } else if (manualPlayPause.value == true) {
                    // Manual pause (user) cancels cycle
                    Log.d("PlaybackService", "Manual pause. Cancel cycle")
                    cancelScheduledPauseResume()
                }
            }
        })
        setRepeatMode(config.playbackSetting)
        setPlaybackSpeed(config.playbackSpeed)
        shuffleModeEnabled = config.isShuffleEnabled
        mediaSession.setCustomLayout(getCustomLayout())
        SimpleEqualizer.setupEqualizer(this@initializeSessionAndPlayer, player)
    }
}

private var handler = Handler(Looper.getMainLooper())

internal fun PlaybackService.rewind() {
    withPlayer {
        player.seekTo(player.currentPosition - PAUSE_AFTER_MS)
    }
}

private fun updatePlaybackContent(txt: String) {
    GlobalData.playbackFileContent.postValue(txt)
}

internal fun PlaybackService.mediaNextButtonClicked(player: SimpleMusicPlayer) {
    val enabled = GlobalData.playbackFileEnabled.value ?: false
    if (enabled && previousRandomPlaybackCommand != null) {
        replayLastRandom()
    } else {
        rewind()
    }
}

internal fun PlaybackService.replayLastRandom() {
    val pauseAfterMs = (GlobalData.playDurationSeconds.value ?: defaultStopIntervalMs).toLong() * 1000
    val resumePlayingAfterMs = (GlobalData.pauseDurationSeconds.value ?: defaultStopIntervalMs).toLong() * 1000
    val commands = getEffectivePlaybackCommands()
    if(commands.isNotEmpty()) {
        previousRandomPlaybackCommand?.let {
            executeCommand(it, commands, pauseAfterMs, resumePlayingAfterMs)
        }
    } else {
        lastRandomPosition?.let {
            val msg = "Replaying last random ${Utils.formatMillis(it)}"
            withPlayer {
                player.seekTo(it)
                player.play()
                updatePlaybackContent(msg)
            }
            schedulePauseThenResume(pauseAfterMs, resumePlayingAfterMs, msg, continueAfterResume = true)
        }
    }
}

internal fun PlaybackService.seekRandomOrPlayAnswer() {
    waitForDurationAndRun {
        if(!questionAnswerEnabled()) {
            seekRandomInternal()
        } else {
            if(PlaybackService.turnForQuestion) {
                seekRandomInternal()
                PlaybackService.turnForQuestion = false
            } else {
                playAnswer(previousRandomPlaybackCommand)
                PlaybackService.turnForQuestion = true
            }
        }
    }
}

class ShuffleBag<T>(private val items: List<T>) {
    private var bag = items.shuffled().toMutableList()
    fun next(): T {
        if (bag.isEmpty()) bag = items.shuffled().toMutableList()
        return bag.removeAt(0)
    }
}

fun PlaybackService.playAnswer(command: IndexedValue<PlaybackCommand>?) {
    Log.d("PlaybackService", "Prev cmd: $command")
    val (pauseAfterMs, resumePlayingAfterMs) = defaultDurations()
    val commands = getEffectivePlaybackCommands()
    if(previousRandomPlaybackCommand == null) {
        Log.d("PlaybackService", "Prev cmd: $command. Falling back to random.")
        PlaybackService.turnForQuestion = true
        seekRandomInternal()
    }
    previousRandomPlaybackCommand?.let {
        commands.getOrNull(it.index + 1)?.let { cmd ->
            executeCommand(IndexedValue(it.index + 1, cmd), commands, pauseAfterMs, resumePlayingAfterMs)
        }
    }
}

private fun PlaybackService.seekRandomInternal() {
    val commands = getEffectivePlaybackCommands()
    val (pauseAfterMs, resumePlayingAfterMs) = defaultDurations()

    var msg: String
    if (commands.isEmpty()) {
        withPlayer {
            val tm = (0..player.duration / 1000).random() * 1000
            player.seekTo(tm)
            msg = "randomSeek to ${Utils.formatMillis(tm)}."
            updatePlaybackContent(msg)
            lastRandomPosition = tm
            schedulePauseThenResume(pauseAfterMs, resumePlayingAfterMs, msg, continueAfterResume = true)
        }
    } else {
        val random = ShuffleBag(commands.withIndex().toList().filter { it.value.isQuestion() }).next()
        executeCommand(random, commands, pauseAfterMs, resumePlayingAfterMs)
        previousRandomPlaybackCommand = random
    }
}

private fun PlaybackService.defaultDurations(): Pair<Long, Long> {
    val pauseAfterMs = (GlobalData.playDurationSeconds.value ?: defaultStopIntervalMs).toLong() * 1000
    val resumePlayingAfterMs = (GlobalData.pauseDurationSeconds.value ?: defaultStopIntervalMs).toLong() * 1000
    return Pair(pauseAfterMs, resumePlayingAfterMs)
}

private fun PlaybackService.executeCommand(
    random: IndexedValue<PlaybackCommand>,
    commands: List<PlaybackCommand>,
    pauseAfterMs: Long,
    resumePlayingAfterMs: Long
) {
    if(commands.size < 2) {
        return
    }
    var pauseAfterMs1 = pauseAfterMs
    var resumePlayingAfterMs1 = resumePlayingAfterMs
    var nextCommand: PlaybackCommand? = null
    var commandToExecuteNow: PlaybackCommand?
    withPlayer {
        commandToExecuteNow = random.value
        val nextIndex = random.index + 1
        nextCommand = commands.getOrNull(nextIndex)

        if(nextCommand != null && commandToExecuteNow != null) {
            if (nextCommand!!.timestampMs >= commandToExecuteNow!!.timestampMs) {
                pauseAfterMs1 = nextCommand!!.timestampMs - commandToExecuteNow!!.timestampMs
            }
        }

        when (val cmd = commandToExecuteNow) {
            is PlaybackCommand.Stop -> {
                resumePlayingAfterMs1 = cmd.durationMs
            }
            else -> {}
        }

        var msg = commandToExecuteNow?.text ?: ""
        if(commandToExecuteNow != null) {
            player.seekTo(commandToExecuteNow!!.timestampMs)
        } else {
            msg = "Unexpected!! commandToExecuteNow is null"
        }

        updatePlaybackContent(msg)
        schedulePauseThenResume(pauseAfterMs1, resumePlayingAfterMs1, msg, continueAfterResume = true)
    }
}

private fun questionAnswerEnabled() = GlobalData.questionAnswerSetting.value == 0

// place near top of file (reuse your existing handler)
private var scheduledCycleId = 0L
private var nextScheduledPauseAction: Runnable? = null
private var nextScheduledResumeAction: Runnable? = null
private val scheduler = handler // already Handler(Looper.getMainLooper())
private var isInStop = false

private fun cancelScheduledPauseResume() {
    // increment token to invalidate any already enqueued Runnables
    scheduledCycleId++
    nextScheduledPauseAction?.let { scheduler.removeCallbacks(it) }
    nextScheduledResumeAction?.let { scheduler.removeCallbacks(it) }
    nextScheduledPauseAction = null
    nextScheduledResumeAction = null
    isInStop = false
    Log.d("PlaybackService", "cancelScheduledPauseResume(): token now=$scheduledCycleId")
}

/**
 * Schedule a pause after [pauseAfterMs], then schedule resume after [resumeAfterMs].
 * If continueAfterResume==true, the resume action will start the next random cycle by calling seekRandom().
 */
internal fun PlaybackService.schedulePauseThenResume(
    pauseAfterMs: Long,
    resumeAfterMs: Long,
    pauseMessage: String,
    continueAfterResume: Boolean = true
) {
    val safePauseMs = pauseAfterMs.coerceAtLeast(1000L)
    val safeResumeMs = resumeAfterMs.coerceAtLeast(0L)

    // cancel previous and create new token
    cancelScheduledPauseResume()
    val token = scheduledCycleId

    val pauseAction = Runnable {
        if (token != scheduledCycleId) {
            Log.d("PlaybackService", "pauseAction stale (token=$token,current=${scheduledCycleId}) -> ignoring. $pauseMessage")
            return@Runnable
        }
        Log.d("PlaybackService", "Pausing (token=$token) at ${Instant.now()}. $pauseMessage")

        isInStop = true
        withPlayer {
            player.pause()
        }
        updatePlaybackContent(pauseMessage)

        // schedule resume only if resume > 0
        if (safeResumeMs > 0) {
            val resumeAction = Runnable {
                if (token != scheduledCycleId) {
                    Log.d("PlaybackService", "resumeAction stale (token=$token,current=${scheduledCycleId}) -> ignoring. $pauseMessage")
                    return@Runnable
                }
                Log.d("PlaybackService", "Resuming (token=$token) at ${Instant.now()}. $pauseMessage")
                if (isInStop) {
                    withPlayer {
                        player.play()
                    }
                    isInStop = false
                    updatePlaybackContent("") // clear message
                }

                // IMPORTANT: continue the cycle by scheduling the next random seek.
                if (continueAfterResume) {
                    // Post to handler to avoid running seekRandom on the same stack
                    scheduler.post {
                        Log.d("PlaybackService", "continuing cycle after resume (token=$token). $pauseMessage")
                        // Use public seekRandom() so it waits for duration -> avoids C.TIME_UNSET issues
                        seekRandomOrPlayAnswer()
                    }
                }
            }
            nextScheduledResumeAction = resumeAction
            scheduler.postDelayed(resumeAction, safeResumeMs)
            startCountdown(safeResumeMs)
            Log.d("PlaybackService", "⏳ Scheduled resume (token=$token) after ${safeResumeMs/1000}s at ${Instant.now().plusMillis(safeResumeMs)}. $pauseMessage")
        }
    }

    nextScheduledPauseAction = pauseAction
    scheduler.postDelayed(pauseAction, safePauseMs)
    startCountdown(safePauseMs)
    Log.d("PlaybackService", "▶ Scheduled pause (token=$token) after ${safePauseMs/1000}s at ${Instant.now().plusMillis(safePauseMs)} (resume ${safeResumeMs/1000}s). $pauseMessage")
}

private var countdownRunnable: Runnable? = null

internal fun startCountdown(totalMs: Long) {
    countdownRunnable?.let { handler.removeCallbacks(it) }

    var remaining = totalMs / 1000
    countdownRunnable = object : Runnable {
        override fun run() {
            if (remaining >= 0) {
                GlobalData.playbackCountdown.postValue("${remaining}s")
                remaining--
                handler.postDelayed(this, 1000)
            }
        }
    }
    handler.post(countdownRunnable!!)
}

internal fun PlaybackService.waitForDurationAndRun(action: () -> Unit) {
    handler.postDelayed(object : Runnable {
        override fun run() {
            val me = this
            withPlayer {
                if (player.duration != C.TIME_UNSET) {
                    action()
                } else {
                    handler.postDelayed(me, 100) // retry after 100ms
                }
            }
        }
    }, 100)
}

internal fun PlaybackService.mediaPreviousButtonClicked(player: SimpleMusicPlayer) {
    mediaNextButtonClicked(player)
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
