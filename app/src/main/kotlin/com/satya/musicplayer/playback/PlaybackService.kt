package com.satya.musicplayer.playback

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.os.postDelayed
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.satya.musicplayer.PlaybackCommand
import com.satya.musicplayer.PlaybackCommand.Companion.buildListWithEndTimes
import com.simplemobiletools.commons.extensions.hasPermission
import com.simplemobiletools.commons.extensions.showErrorToast
import com.satya.musicplayer.extensions.*
import com.satya.musicplayer.helpers.NotificationHelper
import com.satya.musicplayer.helpers.getPermissionToRequest
import com.satya.musicplayer.playback.library.MediaItemProvider
import com.satya.musicplayer.playback.player.ShuffleBag
import com.satya.musicplayer.playback.player.SimpleMusicPlayer
import com.satya.musicplayer.playback.player.initializeSessionAndPlayer
import kotlinx.collections.immutable.ImmutableList

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService(), MediaSessionService.Listener {
    internal lateinit var player: SimpleMusicPlayer
    internal lateinit var playerThread: HandlerThread
    internal lateinit var playerListener: Player.Listener
    internal lateinit var playerHandler: Handler
    internal lateinit var mediaSession: MediaLibrarySession
    internal lateinit var mediaItemProvider: MediaItemProvider

    internal var previousPlaybackCommand: QA? = null
    internal var lastRandomPosition: Long? = null
    val defaultStopIntervalMs = 10_000L
    internal var currentRoot = ""

    override fun onCreate() {
        super.onCreate()
        setListener(this)
        initializeSessionAndPlayer(handleAudioFocus = true, handleAudioBecomingNoisy = true, skipSilence = config.gaplessPlayback)
        initializeLibrary()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaSession()
        clearListener()
        stopSleepTimer()
        SimpleEqualizer.release()
    }

    fun stopService() {
        withPlayer {
            pause()
            stop()
        }

        stopSelf()
    }

    private fun initializeLibrary() {
        mediaItemProvider = MediaItemProvider(this)
        if (hasPermission(getPermissionToRequest())) {
            mediaItemProvider.reload()
        }
        showNoPermissionNotification()
    }

    private fun releaseMediaSession() {
        mediaSession.release()
        withPlayer {
            removeListener(playerListener)
            release()
        }
    }

    internal fun withPlayer(callback: SimpleMusicPlayer.() -> Unit) = playerHandler.post { callback(player) }

    private fun showNoPermissionNotification() {
        Handler(Looper.getMainLooper()).postDelayed(delayInMillis = 100L) {
            try {
                startForeground(
                    NotificationHelper.NOTIFICATION_ID,
                    NotificationHelper.createInstance(this).createNoPermissionNotification()
                )
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * This method is only required to be implemented on Android 12 or above when an attempt is made
     * by a media controller to resume playback when the {@link MediaSessionService} is in the
     * background.
     */
    override fun onForegroundServiceStartNotAllowedException() {
        showErrorToast(getString(com.simplemobiletools.commons.R.string.unknown_error_occurred))
        // todo: show a notification instead.
    }

    data class QA(val part: PlaybackCommand?, val counterpart: PlaybackCommand?, val id: Int)

    companion object {
        // Initializing a media controller might take a noticeable amount of time thus we expose current playback info here to keep things as quick as possible.
        var isPlaying: Boolean = false
            private set
        var currentMediaItem: MediaItem? = null
            private set
        var nextMediaItem: MediaItem? = null
            private set
        private var playbackCommands: List<PlaybackCommand> = listOf()
        var qaCommandList: ShuffleBag<QA> = ShuffleBag(listOf())

        /**
         * part or counterpart: if part is ques, counterpart is answer and vice-versa
         */
        var turnForPart = true

        fun updatePlaybackInfo(player: Player) {
            currentMediaItem = player.currentMediaItem
            nextMediaItem = player.nextMediaItem
            isPlaying = player.isReallyPlaying
        }

        //TODO: Extend with other options
        fun getRandomCommandToPlayNext() = qaCommandList.next()

        fun setPlaybackCommands(playbackFileContent: String, andThen: Runnable) {
            playbackCommands = buildListWithEndTimes(playbackFileContent.trimIndent().lines())
            qaCommandList = ShuffleBag(buildQAList(playbackCommands, GlobalData.questionAnswerSetting.value == 1))
            andThen.run()
        }

        private fun buildQAList(items: List<PlaybackCommand>, swap: Boolean = false): List<QA> {
            return items.chunked(2).withIndex().mapNotNull { pairWithIndex ->
                val pair = pairWithIndex.value
                val id = pairWithIndex.index
                when (pair.size) {
                    2 -> if (!swap) {
                        QA(part = pair[0], counterpart = pair[1], id)
                    } else
                        QA(part = pair[1], counterpart = pair[0], id)
                    1 -> { // only one item left, handle gracefully
                        if (!swap)
                            QA(part = pair[0], counterpart = null, id) // no counterpart
                        else
                            QA(part = null, counterpart = pair[0], id) // no part
                    }
                    else -> null
                }
            }
        }

        fun updateTurn(commandPlayedNow: PlaybackCommand) {
            val isPartPlayedNow: Boolean = if(GlobalData.questionAnswerSetting.value == 0) {
                commandPlayedNow.isQuestion()
            } else {
                commandPlayedNow.isAnswer()
            }
            turnForPart = !isPartPlayedNow
        }
    }
}

