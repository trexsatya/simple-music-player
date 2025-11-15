package com.satya.musicplayer.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.*
import androidx.annotation.OptIn
import androidx.core.os.postDelayed
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.media3.ui.PlayerNotificationManager
import com.satya.musicplayer.PlaybackCommand
import com.satya.musicplayer.PlaybackCommand.Companion.buildListWithEndTimes
import com.satya.musicplayer.R
import com.satya.musicplayer.activities.MainActivity
import com.simplemobiletools.commons.extensions.hasPermission
import com.simplemobiletools.commons.extensions.showErrorToast
import com.satya.musicplayer.extensions.*
import com.satya.musicplayer.helpers.NotificationHelper
import com.satya.musicplayer.helpers.NotificationHelper.Companion.NOTIFICATION_ID
import com.satya.musicplayer.helpers.getPermissionToRequest
import com.satya.musicplayer.playback.library.MediaItemProvider
import com.satya.musicplayer.playback.player.CircularList
import com.satya.musicplayer.playback.player.ShuffleBag
import com.satya.musicplayer.playback.player.SimpleMusicPlayer
import com.satya.musicplayer.playback.player.initializeSessionAndPlayer
import java.util.Timer

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService(), MediaSessionService.Listener {
    internal lateinit var player: SimpleMusicPlayer
    internal lateinit var playerThread: HandlerThread
    internal lateinit var playerListener: Player.Listener
    internal lateinit var timer: Timer
    internal lateinit var playerHandler: Handler
    internal lateinit var mediaSession: MediaLibrarySession
    internal lateinit var mediaItemProvider: MediaItemProvider
    private var wakeLock: PowerManager.WakeLock? = null
    internal var currentRoot = ""

    override fun onCreate() {
        super.onCreate()
        setListener(this)
        val notification = NotificationHelper.createInstance(this).createMediaScannerNotification("Text", 100, 100)

        initializeSessionAndPlayer(handleAudioFocus = true, handleAudioBecomingNoisy = true, skipSilence = config.gaplessPlayback)
        initializeLibrary()

        // Required for Android 14+
        startForeground(
            NOTIFICATION_ID,
            notification
        )

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PlaybackService::WakeLock")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(30*60_000)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onDestroy() {
        super.onDestroy()
        releaseMediaSession()
        clearListener()
        stopSleepTimer()
        timer.cancel()
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
        } else {
            showNoPermissionNotification()
        }
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
        var resumeAt: Long? = 0
        var pauseAt: Long? = 0L
        var pausedManually = false
        var programmaticChange = false
        var currentPosition = 0L
        var timestampForNextAction = ""
        var playbackSpeeds = CircularList(listOf(1.0f))
        var currentPlaybackSpeed = 1.0f
        // Initializing a media controller might take a noticeable amount of time thus we expose current playback info here to keep things as quick as possible.
        var isPlaying: Boolean = false
            private set
        var currentMediaItem: MediaItem? = null
            private set
        var nextMediaItem: MediaItem? = null
            private set
        private var playbackCommands: List<PlaybackCommand> = listOf()
        var qaCommandListShuffled: ShuffleBag<QA> = ShuffleBag(listOf())
        private var qaCommandListSequential: CircularList<QA> = CircularList(listOf())
        internal var previousPlaybackCommand: QA? = null
        internal var lastRandomPosition: Long? = null
        internal var lastDuration: Long? = null
        const val DEFAULT_STOP_INTERVAL_MS = 10_000L
        internal var savedSpeed = 1.0f
        var commandRepeatIteration = 0
        var repetitionReachedMax = false
        var changeInPianoDisplay = false

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
        fun getRandomCommandToPlayNext(excludeIds: List<Int>, onEmpty: Runnable): QA? {
            if(qaCommandListShuffled.isEmpty()) return null
            if(qaCommandListShuffled.items().size == 1) {
                return qaCommandListShuffled.items()[0]
            }
            var next: QA
            while (true) {
                next = qaCommandListShuffled.next(onEmpty)
                if(next.id !in excludeIds) {
                    return next
                }
            }
        }

        fun getCommandToPlayNext(): QA? {
            return qaCommandListSequential.next()
        }

        fun clearPlaybackCommands(andThen: Runnable) {
            playbackCommands = listOf()
            qaCommandListShuffled = ShuffleBag(listOf())
            qaCommandListSequential = CircularList(listOf())
            andThen.run()
        }

        fun setPlaybackCommands(playbackFileContent: String, andThen: Runnable) {
            playbackCommands = buildListWithEndTimes(playbackFileContent.trimIndent().lines())
            qaCommandListShuffled = ShuffleBag(buildQAList(playbackCommands, GlobalData.questionAnswerSetting.value == 1))
            qaCommandListSequential = CircularList(buildQAList(playbackCommands, GlobalData.questionAnswerSetting.value == 1))
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

        fun reset() {
            GlobalData.currentlyPlayingQA.postValue(null)
            GlobalData.playedQaCommandIds.postValue(setOf())
            lastRandomPosition = 0
            lastDuration = null
            turnForPart = true
            currentPosition = 0
            qaCommandListShuffled = ShuffleBag(listOf())
            val defaultPlayDuration = (GlobalData.playDurationSeconds.value ?: DEFAULT_STOP_INTERVAL_MS).toLong() * 1000
            pauseAt = defaultPlayDuration
            resumeAt = defaultPlayDuration + (GlobalData.pauseDurationSeconds.value ?: DEFAULT_STOP_INTERVAL_MS).toLong() * 1000
            commandRepeatIteration = 0
            repetitionReachedMax = false
            playbackSpeeds.reset()
        }
    }
}

class MyDescriptionAdapter(playbackService: PlaybackService, applicationContext: Context) : PlayerNotificationManager.MediaDescriptionAdapter {
    private val context = applicationContext
    override fun getCurrentContentTitle(player: Player): CharSequence {
        return player.currentMediaItem?.toTrack()?.title ?: "Title"
    }

    override fun createCurrentContentIntent(player: Player): PendingIntent? {
        val contentIntent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(context, 0, contentIntent, FLAG_IMMUTABLE)
    }

    override fun getCurrentContentText(player: Player): CharSequence? {
        return player.currentMediaItem?.toTrack()?.title
    }

    override fun getCurrentLargeIcon(player: Player, callback: PlayerNotificationManager.BitmapCallback): Bitmap? {
        return null
    }

}

