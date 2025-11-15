@file:UnstableApi

package com.satya.musicplayer.playback.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import com.satya.musicplayer.FixedSizeQueue
import com.satya.musicplayer.PlaybackCommand
import com.satya.musicplayer.Utils.Companion.formatMillis
import com.satya.musicplayer.activities.MainActivity
import com.satya.musicplayer.extensions.*
import com.satya.musicplayer.helpers.SEEK_INTERVAL_MS
import com.satya.musicplayer.playback.*
import com.satya.musicplayer.playback.GlobalData.currentlyPlayingQA
import com.satya.musicplayer.playback.GlobalData.manualResumeEnforced
import com.satya.musicplayer.playback.PlaybackService.Companion.DEFAULT_STOP_INTERVAL_MS
import com.satya.musicplayer.playback.PlaybackService.Companion.commandRepeatIteration
import com.satya.musicplayer.playback.PlaybackService.Companion.getRandomCommandToPlayNext
import com.satya.musicplayer.playback.PlaybackService.Companion.lastDuration
import com.satya.musicplayer.playback.PlaybackService.Companion.lastRandomPosition
import com.satya.musicplayer.playback.PlaybackService.Companion.previousPlaybackCommand
import com.satya.musicplayer.playback.PlaybackService.Companion.repetitionReachedMax
import com.satya.musicplayer.playback.PlaybackService.Companion.savedSpeed
import com.satya.musicplayer.playback.PlaybackService.Companion.timestampForNextAction
import com.satya.musicplayer.playback.PlaybackService.Companion.updatePlaybackInfo
import com.satya.musicplayer.playback.getCustomLayout
import com.satya.musicplayer.playback.getMediaSessionCallback
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask

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
        timer = Timer()
        val period = 100L
        savedSpeed = player.playbackParameters.speed

        val task = object : TimerTask() {
            override fun run() {
                playerHandler.post {
                    if (PlaybackService.pausedManually) {
                        // optionally update UI countdown only
                        GlobalData.playbackCountdown.postValue(formatMillis(PlaybackService.currentPosition) + timestampForNextAction + "/" + player.playbackParameters.speed)
                        return@post
                    }
                    PlaybackService.currentPosition += period
                    withPlayer {
                        maybePause()
                        maybeResume()
                        GlobalData.playbackCountdown.postValue(formatMillis(PlaybackService.currentPosition) + timestampForNextAction + "/" + player.playbackParameters.speed)
                    }
                }
            }

            private fun maybePause() {
                PlaybackService.pauseAt?.let { pauseAt ->
                    if (player.isPlaying && player.currentPosition >= pauseAt) {
                        PlaybackService.pauseAt = null
                        PlaybackService.programmaticChange = manualResumeEnforced.value == false
                        player.pause()
                        PlaybackService.currentPosition = player.currentPosition
                        PlaybackService.resumeAt?.let {
                            timestampForNextAction = "/r-" + formatMillis(it)
                        }
                    }
                }
            }

            private fun maybeResume() {
                PlaybackService.resumeAt?.let { resumeAt ->
                    if (!player.isPlaying && PlaybackService.currentPosition >= resumeAt) {
                        PlaybackService.resumeAt = null
                        PlaybackService.currentPosition = player.currentPosition
                        PlaybackService.programmaticChange = manualResumeEnforced.value == false
                        seekRandomOrPlaySomeCommand()
                        PlaybackService.pauseAt?.let {
                            timestampForNextAction = "/p-" + formatMillis(it)
                        }
                    }
                }
            }
        }
        timer.schedule(task, period, period)
        addListener(playerListener)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (PlaybackService.programmaticChange) {
                    PlaybackService.programmaticChange = false
                    return
                }

                when (reason) {
                    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> {
                        // User pressed play or pause manually
                        PlaybackService.pausedManually = !playWhenReady
                        Log.d("Playback", if (playWhenReady) "Manual resume" else "Manual pause")
                        hack(playWhenReady)
                    }

                    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> {
                        // Optional: remote (e.g. headset button)
                        PlaybackService.pausedManually = !playWhenReady
                        Log.d("Playback", if (playWhenReady) "Remote resume" else "Remote pause")
                        hack(playWhenReady)
                    }

                    else -> {
                        // Automatic change (timer, playlist, focus, etc.)
                        // Ignore – not user-initiated
                    }
                }
            }

            private fun hack(playWhenReady: Boolean) {
                timestampForNextAction = if (!playWhenReady) {
                    "/r-${PlaybackService.resumeAt?.let { formatMillis(it) }}"
                } else {
                    "/p-${PlaybackService.pauseAt?.let { formatMillis(it) }}"
                }
                // Resuming, should be able to pause at some point
                if (playWhenReady && (PlaybackService.pauseAt == null)) {
                    //PlaybackService.resumeAt = player.currentPosition + defaultDurations().first
                    seekRandomOrPlaySomeCommand()
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
    skipCurrentCommand()
}

internal fun PlaybackService.playSpecificCommand(id: Int) {
    val (_, resumePlayingAfterMs) = defaultDurations()

    val commands = PlaybackService.qaCommandListShuffled.remainingListInOriginalOrder()
    val command = commands.find { it.id == id }
    command?.part?.let {
        executeCommand(command.id, it, resumePlayingAfterMs)
        if (!PlaybackService.turnForPart) {
            currentlyPlayingQA.postValue(command)
            commandRepeatIteration = 0
            GlobalData.repeatRemaining.postValue(getMaxRepeat() - commandRepeatIteration)
        }
    }
}

internal fun PlaybackService.skipCurrentCommand() {
    previousPlaybackCommand = currentlyPlayingQA.value
    val (pauseAfterMs, resumePlayingAfterMs) = defaultDurations()

    if(PlaybackService.qaCommandListShuffled.items().isEmpty()) {
        playNewPosition(pauseAfterMs, resumePlayingAfterMs)
        return
    }
    playNewCommand(resumePlayingAfterMs)
}

internal fun PlaybackService.seekRandomOrPlaySomeCommand() {
    waitForDurationAndRun {
        if (!questionAnswerEnabled()) {
            seekRandomInternalOrPlayPart()
        } else {
            if (PlaybackService.turnForPart) {
                withPlayer {
                    player.setPlaybackSpeed(savedSpeed)
                }
                seekRandomInternalOrPlayPart()
            } else {
                playCounterpart(currentlyPlayingQA.value)
            }
        }
    }
}

class ShuffleBag<T>(private val items: List<T>) {
    private var bag = items.shuffled().toMutableList()
    fun next(onEmpty: Runnable): T {
        if (bag.isEmpty()) {
            onEmpty.run()
            bag = items.shuffled().toMutableList()
        }
        return bag.removeAt(0)
    }

    fun remainingListInOriginalOrder(): ImmutableList<T> {
        return items.filter { it in bag }.toImmutableList()
    }

    fun isEmpty() = items.isEmpty()
    fun items() = items
}

class CircularList<T>(private val items: List<T>) {
    private var currentIndex = 0

    fun get(index: Int): T {
        if (items.isEmpty()) throw NoSuchElementException("List is empty")
        val circularIndex = ((index % items.size) + items.size) % items.size
        return items[circularIndex]
    }

    fun next(): T? {
        if (items.isEmpty()) return null
        val element = items[currentIndex]
        currentIndex = (currentIndex + 1) % items.size
        return element
    }

    val size: Int
        get() = items.size

    fun isEmpty() = items.isEmpty()

    fun reset() {
        currentIndex = 0
    }
}

fun PlaybackService.playCounterpart(command: PlaybackService.QA?) {
    val (_, resumePlayingAfterMs) = defaultDurations()
    if (command == null) {
        Log.d("PlaybackService", "Prev cmd is null. Moving on.")
        PlaybackService.turnForPart = true
        seekRandomInternalOrPlayPart()
        return
    }
    command.counterpart?.let {
        withPlayer {
            player.setPlaybackSpeed(PlaybackService.playbackSpeeds.next() ?: savedSpeed)
        }
        executeCommand(command.id, it, resumePlayingAfterMs)
    }
}

private fun PlaybackService.seekRandomInternalOrPlayPart() {
    val commands = PlaybackService.qaCommandListShuffled
    val (pauseAfterMs, resumePlayingAfterMs) = defaultDurations()
    val maxRepeat = getMaxRepeat()
    if (commands.isEmpty()) {
        seekPlayerOrRepeatPosition()
    } else {
        val currentCommand = currentlyPlayingQA.value
        val toRepeat = currentCommand?.part
        if (shouldRepeat() && toRepeat != null && commandRepeatIteration < maxRepeat) {
            increaseRepetitionIteration()
            executeCommand(currentCommand.id, toRepeat, resumePlayingAfterMs)
            repetitionReachedMax = commandRepeatIteration >= maxRepeat
        } else {
            GlobalData.message.postValue(Event(NEW_COMMAND_WILL_PLAY))
            CoroutineScope(Dispatchers.Main).launch {
                playNewCommand(resumePlayingAfterMs)
                commandRepeatIteration = 0
                repetitionReachedMax = false
                GlobalData.repeatRemaining.postValue(maxRepeat)
            }
        }
        GlobalData.repeatRemaining.postValue(maxRepeat - commandRepeatIteration )
    }
}

private fun PlaybackService.seekPlayerOrRepeatPosition() {
    val (pauseAfterMs, resumePlayingAfterMs) = defaultDurations()
    val maxRepeat = getMaxRepeat()
    withPlayer {
        if (shouldRepeat() && lastRandomPosition != null && commandRepeatIteration < maxRepeat) {
            lastRandomPosition?.let { tm ->
                increaseRepetitionIteration()
                player.setPlaybackSpeed(PlaybackService.playbackSpeeds.next() ?: savedSpeed)
                seekAndPlay(tm, "repeating ${formatMillis(tm)}", pauseAfterMs, resumePlayingAfterMs)
                repetitionReachedMax = commandRepeatIteration >= maxRepeat
            }
        } else {
            playNewPosition(pauseAfterMs, resumePlayingAfterMs)
        }
        GlobalData.repeatRemaining.postValue(maxRepeat - commandRepeatIteration)
    }
}

private fun increaseRepetitionIteration() {
    commandRepeatIteration += 1
}

private fun shouldRepeat() = GlobalData.repeatCount.value?.let { it > 0 } ?: false

private fun PlaybackService.seekAndPlay(
    tm: Long,
    msg: String,
    pauseAfterMs: Long,
    resumePlayingAfterMs: Long
) {
    var tm1 = tm
    if(tm >= player.duration) tm1 = 0
    withPlayer {
        player.seekTo(tm1)
        player.play()
        val pauseAt = tm1 + pauseAfterMs
        PlaybackService.pauseAt = pauseAt
        val resumeAt = pauseAt + resumePlayingAfterMs
        PlaybackService.resumeAt = resumeAt
        updatePlaybackContent(msg)
        timestampForNextAction = "/p-${formatMillis(pauseAt)}"
    }
    lastRandomPosition = tm1
    lastDuration = pauseAfterMs
}

private fun PlaybackService.playNewPosition(
    pauseAfterMs: Long,
    resumePlayingAfterMs: Long
) {
    player.setPlaybackSpeed(savedSpeed)
    PlaybackService.playbackSpeeds.reset()

    var tm = if (lastRandomPosition == null) 0 else (lastRandomPosition ?: 0) + (lastDuration ?: pauseAfterMs)
    if (GlobalData.randomSeekEnabled.value == true) {
        tm = (0..player.duration / 1000).random() * 1000
    }
    seekAndPlay(tm, "random seek to ${formatMillis(tm)}", pauseAfterMs, resumePlayingAfterMs)
    commandRepeatIteration = 0
}

private fun PlaybackService.playNewCommand(resumePlayingAfterMs: Long) {
    var excludeIds: List<Int> = listOf()
    withPlayer {
        player.setPlaybackSpeed(savedSpeed)
        PlaybackService.playbackSpeeds.reset()

        val currentTrackId = currentMediaItem?.toTrack()?.trackId

        if (currentTrackId == GlobalData.playedTrackId.value) {
            excludeIds = GlobalData.playedQaCommandIds.value?.toList() ?: listOf()
        }
        val command: PlaybackService.QA? = if(GlobalData.randomSeekEnabled.value == true) getRandomCommandToPlayNext(excludeIds) {
            GlobalData.message.postValue(Event(ALL_COMMANDS_PLAYED))
            GlobalData.playedQaCommandIds.postValue(setOf())
            try {
                Thread.sleep(5000)
            } catch (_: Exception) {}
        } else PlaybackService.getCommandToPlayNext()

        if (command?.part != null) {
            currentlyPlayingQA.value?.id?.let { updateAlreadyPlayedIndices(it) }
            executeCommand(command.id, command.part, resumePlayingAfterMs)
            // New command
            currentlyPlayingQA.postValue(command)
            Log.d("PlaybackService", "Currently playing: ${currentlyPlayingQA.value?.id}")
            previousPlaybackCommand = command
        } else {
            Log.w("PlaybackService", "No random command to play and nothing to repeat!!")
            Toast.makeText(applicationContext, "No command!!", Toast.LENGTH_LONG).show()
        }
    }
}

private fun getMaxRepeat() = GlobalData.repeatCount.value ?: DEFAULT_REPEAT_COUNT

private fun defaultDurations(): Pair<Long, Long> {
    val pauseAfterMs = (GlobalData.playDurationSeconds.value ?: DEFAULT_STOP_INTERVAL_MS).toLong() * 1000
    val resumePlayingAfterMs = (GlobalData.pauseDurationSeconds.value ?: DEFAULT_STOP_INTERVAL_MS).toLong() * 1000
    return Pair(pauseAfterMs, resumePlayingAfterMs)
}

private fun PlaybackService.executeCommand(
    id: Int,
    random: PlaybackCommand,
    resumePlayingAfterMs: Long
) {
    var commandToExecuteNow: PlaybackCommand
    PlaybackService.updateTurn(random)
    withPlayer {
        commandToExecuteNow = random

        val endTimeMs = commandToExecuteNow.endTimeMs ?: player.duration

        var msg = commandToExecuteNow.text
        random.endTimeMs?.let {
            msg += " ${formatMillis(it)}"
        }
        player.seekTo(commandToExecuteNow.startTimeMs)
        PlaybackService.currentPosition = commandToExecuteNow.startTimeMs
        player.play()

        updatePlaybackContent("$id || $msg")
        Log.d("PlaybackService", "Playing ${commandToExecuteNow.id} $commandToExecuteNow")

        PlaybackService.pauseAt = endTimeMs
        PlaybackService.resumeAt = ((commandToExecuteNow.endTimeMs ?: 0 ) + resumePlayingAfterMs) % player.duration

        timestampForNextAction = "/p-${formatMillis(endTimeMs)}"

        Log.d("Player", "Execute ${commandToExecuteNow.id} ${formatMillis(PlaybackService.pauseAt ?: 0)} ${formatMillis(PlaybackService.resumeAt ?: 0)} $commandToExecuteNow")
    }
}

internal fun PlaybackService.updateAlreadyPlayedIndices(id: Int) {
    withPlayer {
        currentMediaItem?.toTrack()?.trackId?.let {
            if (it == GlobalData.playedTrackId.value) {
                var alreadyPlayed = GlobalData.playedQaCommandIds.value ?: setOf()
                alreadyPlayed = alreadyPlayed.plus(id)
                GlobalData.playedQaCommandIds.postValue(alreadyPlayed)
                Log.d("PlaybackService", "Already played Ids: $alreadyPlayed")
            } else {
                GlobalData.playedTrackId.postValue(it)
                GlobalData.playedQaCommandIds.postValue(setOf())
            }
        }
    }
}

private fun <T> MutableLiveData<FixedSizeQueue<T>>.addItem(item: T) {
    val oldQueue = value
    val maxSize = 20
    val newQueue = if (oldQueue != null) {
        FixedSizeQueue<T>(maxSize).apply {
            oldQueue.toList().forEach { add(it) }
            add(item)
        }
    } else {
        FixedSizeQueue<T>(maxSize).apply { add(item) }
    }
    postValue(newQueue)
}

private fun <T> MutableLiveData<FixedSizeQueue<T>>.removeItem(item: T) {
    val oldQueue = value
    val maxSize = 20
    val newQueue = if (oldQueue != null) {
        FixedSizeQueue<T>(maxSize).apply {
            oldQueue.toList().forEach {
                if (it?.equals(item) == false) {
                    add(it)
                }
            }
        }
    } else {
        FixedSizeQueue<T>(maxSize)
    }
    postValue(newQueue)
}

private fun questionAnswerEnabled() = GlobalData.questionAnswerSetting.value != null

const val NEW_COMMAND_WILL_PLAY = "newCommandWillPlay"
const val ALL_COMMANDS_PLAYED = "allCommandsPlayed"

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
