package com.satya.musicplayer.activities

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.widget.NumberPicker
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.os.bundleOf
import androidx.core.os.postDelayed
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.MediaItem
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import com.satya.musicplayer.R
import com.satya.musicplayer.Utils.Companion.readTextFromUri
import com.satya.musicplayer.databinding.ActivityTrackBinding
import com.satya.musicplayer.extensions.*
import com.satya.musicplayer.fragments.PlaybackSpeedFragment
import com.satya.musicplayer.helpers.PlaybackSetting
import com.satya.musicplayer.helpers.SEEK_INTERVAL_S
import com.satya.musicplayer.interfaces.PlaybackSpeedListener
import com.satya.musicplayer.models.Track
import com.satya.musicplayer.playback.CustomCommands
import com.satya.musicplayer.playback.GlobalData
import com.satya.musicplayer.playback.PlaybackService
import com.satya.musicplayer.playback.PlaybackService.Companion.setPlaybackCommands
import com.simplemobiletools.commons.extensions.*
import com.simplemobiletools.commons.helpers.MEDIUM_ALPHA
import com.simplemobiletools.commons.helpers.ensureBackgroundThread
import java.text.DecimalFormat
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds


private const val QUES_ANS_SETTING_KEY = "questionAnswerSetting"

private const val PLAY_DURATION_SECONDS_KEY = "playDurationSeconds"

private const val PAUSE_DURATION_SECONDS_KEY = "pauseDurationSeconds"

class TrackActivity : SimpleControllerActivity(), PlaybackSpeedListener {
    private val SWIPE_DOWN_THRESHOLD = 100

    private var isThirdPartyIntent = false
    private lateinit var nextTrackPlaceholder: Drawable

    private val handler = Handler(Looper.getMainLooper())
    private val updateIntervalMillis = 500L
    private val PICK_FILE_REQUEST_CODE: Int = 1
    private val binding by viewBinding(ActivityTrackBinding::inflate)
    private var evenTurn = true
    private var sharedPreferences: SharedPreferences? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        showTransparentTop = true
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        nextTrackPlaceholder = resources.getColoredDrawableWithColor(R.drawable.ic_headset, getProperTextColor())
        sharedPreferences = getSharedPreferences("com.satya.musicplayer", Context.MODE_PRIVATE)
        setupButtons()
        setupFlingListener()
        binding.apply {
            (activityTrackAppbar.layoutParams as ConstraintLayout.LayoutParams).topMargin = statusBarHeight
            activityTrackHolder.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            activityTrackToolbar.setNavigationOnClickListener {
                finish()
            }

            isThirdPartyIntent = intent.action == Intent.ACTION_VIEW
            arrayOf(activityTrackToggleShuffle, activityTrackPrevious, activityTrackNext, activityTrackPlaybackSetting).forEach {
                it.beInvisibleIf(isThirdPartyIntent)
            }

            if (isThirdPartyIntent) {
                initThirdPartyIntent()
                return
            }

            setupTrackInfo(PlaybackService.currentMediaItem)
            setupNextTrackInfo(PlaybackService.nextMediaItem)
            activityTrackPlayPause.updatePlayPauseIcon(PlaybackService.isPlaying, getProperTextColor())
            updatePlayerState()

            nextTrackHolder.background = ColorDrawable(getProperBackgroundColor())
            nextTrackHolder.setOnClickListener {
                startActivity(Intent(applicationContext, QueueActivity::class.java))
            }
        }

        GlobalData.playbackFileContent.observe(this) { text ->
            runOnUiThread {
                evenTurn = !evenTurn
                findViewById<TextView>(R.id.activity_playback_control_file_content).also {
                    it.text = text
                    if (evenTurn) {
                        it.setTextColor(Color.YELLOW)
                    } else {
                        it.setTextColor(Color.GREEN)
                    }
                }
            }
        }
        GlobalData.playbackFileName.observe(this) {
            runOnUiThread {
                findViewById<TextView>(R.id.activity_track_playback_control_file_btn).text = it
            }
        }
        GlobalData.playbackCountdown.observe(this) { text ->
            binding.activityCountdownLabel.text = text
        }

        GlobalData.commandHistory.observe(this) { updateCommandHistoryList() }

        setupSettingsOverlay()
        findViewById<ToggleButton>(R.id.activity_playback_file_enable_btn)
        findViewById<ToggleButton>(R.id.activity_playback_file_enable_btn)?.setOnCheckedChangeListener { _, checked ->
            GlobalData.playbackFileEnabled.postValue(checked)
        }

        findViewById<ToggleButton>(R.id.activity_random_seek_toggle)?.setOnCheckedChangeListener { _, checked ->
            GlobalData.randomSeekEnabled.postValue(checked)
        }
    }

    private var isOverlayVisible = false
    private fun setupSettingsOverlay() {
        val overlay = findViewById<View>(R.id.playback_file_overlay)
        val settingsButton = findViewById<View>(R.id.activity_playback_settings) // your chosen button

        settingsButton.setOnClickListener {
            if (isOverlayVisible) {
                overlay.animate()
                    .translationY(overlay.height.toFloat())
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction { overlay.visibility = View.GONE }
                    .start()
            } else {
                overlay.visibility = View.VISIBLE
                overlay.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(300)
                    .start()
            }
            isOverlayVisible = !isOverlayVisible
        }
    }

    fun getContrastingTextColor(backgroundColor: Int): Int {
        val r = Color.red(backgroundColor)
        val g = Color.green(backgroundColor)
        val b = Color.blue(backgroundColor)

        // Calculate perceived brightness
        val brightness = (0.299 * r + 0.587 * g + 0.114 * b)

        return when {
            brightness > 200 -> Color.parseColor("#212121") // Dark gray for very light backgrounds
            brightness > 150 -> Color.parseColor("#424242") // Medium gray for light backgrounds
            brightness > 100 -> Color.parseColor("#BDBDBD") // Light gray for medium backgrounds
            else -> Color.parseColor("#FFFFFF")             // White for dark backgrounds
        }
    }

    override fun onResume() {
        super.onResume()
        updateTextColors(binding.activityTrackHolder)
        binding.activityTrackTitle.setTextColor(getProperTextColor())
        binding.activityTrackArtist.setTextColor(getProperTextColor())
        updatePlayerState()
        updateTrackInfo()
    }

    override fun onPause() {
        super.onPause()
        cancelProgressUpdate()
    }

    override fun onStop() {
        super.onStop()
        cancelProgressUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelProgressUpdate()
        if (isThirdPartyIntent && !isChangingConfigurations) {
            withPlayer {
                if (!isReallyPlaying) {
                    sendCommand(CustomCommands.CLOSE_PLAYER)
                }
            }
        }
    }

    private fun setupTrackInfo(item: MediaItem?) {
        val track = item?.toTrack() ?: return

        setupTopArt(track)
        binding.apply {
            activityTrackTitle.text = track.title
            activityTrackArtist.text = track.artist
            activityTrackTitle.setOnLongClickListener {
                copyToClipboard(activityTrackTitle.value)
                true
            }

            activityTrackArtist.setOnLongClickListener {
                copyToClipboard(activityTrackArtist.value)
                true
            }

            activityTrackProgressbar.max = track.duration
            activityTrackProgressMax.text = track.duration.getFormattedDuration()
        }
    }

    private fun initThirdPartyIntent() {
        binding.nextTrackHolder.beGone()
        getTrackFromUri(intent.data) { track ->
            runOnUiThread {
                if (track != null) {
                    prepareAndPlay(listOf(track), startActivity = false)
                } else {
                    toast(com.simplemobiletools.commons.R.string.unknown_error_occurred)
                    finish()
                }
            }
        }
    }

    private fun setupButtons(): ActivityTrackBinding {
        val durationValues =
            listOf("10s", "20s", "30s", "40s", "50s", "60s", "70s", "80s", "90s", "100s", "110s", "120s", "130s", "140s", "150s", "160s", "170s", "180s")
        return binding.apply {
            activityTrackToggleShuffle.setOnClickListener { withPlayer { toggleShuffle() } }
            activityTrackPrevious.setOnClickListener { withPlayer { forceSeekToPrevious() } }
            activityTrackPlayPause.setOnClickListener { togglePlayback() }
            activityTrackNext.setOnClickListener { withPlayer { forceSeekToNext() } }

            playbackFileContainer.activityPlayDurationSecs.minValue = 0
            playbackFileContainer.activityPlayDurationSecs.maxValue = durationValues.size - 1
            playbackFileContainer.activityPauseDurationSecs.minValue = 0
            playbackFileContainer.activityPauseDurationSecs.maxValue = durationValues.size - 1

            playbackFileContainer.activityPlayDurationSecs.displayedValues = durationValues.toTypedArray()
            val defaultDuration = 10
            val savedPlayDurationIndex = durationValues.indexOfFirst {
                it == (sharedPreferences?.getInt(
                    PLAY_DURATION_SECONDS_KEY,
                    defaultDuration
                ) ?: defaultDuration).toString() + "s"
            }
            playbackFileContainer.activityPlayDurationSecs.value = savedPlayDurationIndex.coerceAtLeast(0)
            playbackFileContainer.activityPauseDurationSecs.displayedValues = durationValues.toTypedArray()
            val savedPauseDurationIndex = durationValues.indexOfFirst {
                it == (sharedPreferences?.getInt(
                    PAUSE_DURATION_SECONDS_KEY,
                    defaultDuration
                ) ?: defaultDuration).toString() + "s"
            }
            playbackFileContainer.activityPauseDurationSecs.value = savedPauseDurationIndex.coerceAtLeast(0)

            //Initial values
            updateGlobalValue(GlobalData.playDurationSeconds, playbackFileContainer.activityPlayDurationSecs.value, playbackFileContainer.activityPlayDurationSecs.displayedValues)
            updateGlobalValue(GlobalData.pauseDurationSeconds, playbackFileContainer.activityPauseDurationSecs.value, playbackFileContainer.activityPauseDurationSecs.displayedValues)

            playbackFileContainer.activityPlayDurationSecs.setOnValueChangedListener { _: NumberPicker, _: Int, newValue: Int ->
                updateGlobalValue(GlobalData.playDurationSeconds, newValue, playbackFileContainer.activityPlayDurationSecs.displayedValues)
                sharedPreferences?.edit()?.putInt(PLAY_DURATION_SECONDS_KEY, newValue)?.apply()
            }
            playbackFileContainer.activityPauseDurationSecs.setOnValueChangedListener { _: NumberPicker, _: Int, newValue: Int ->
                updateGlobalValue(GlobalData.pauseDurationSeconds, newValue, playbackFileContainer.activityPauseDurationSecs.displayedValues)
                sharedPreferences?.edit()?.putInt(PAUSE_DURATION_SECONDS_KEY, newValue)?.apply()
            }

            val quesAnsValues = arrayOf("Ques-Ans", "Ans-Ques")
            val defaultQuestionAnswerSetting = quesAnsValues[0]
            val savedQuesAnsSetting = quesAnsValues.indexOfFirst {
                it == (sharedPreferences?.getString(
                    QUES_ANS_SETTING_KEY,
                    defaultQuestionAnswerSetting
                ) ?: defaultQuestionAnswerSetting).toString()
            }
            playbackFileContainer.activityQuestionAnswerSetting.displayedValues = quesAnsValues
            playbackFileContainer.activityQuestionAnswerSetting.minValue = 0
            playbackFileContainer.activityQuestionAnswerSetting.maxValue = playbackFileContainer.activityQuestionAnswerSetting.displayedValues.size - 1
            playbackFileContainer.activityQuestionAnswerSetting.value = savedQuesAnsSetting
            GlobalData.questionAnswerSetting.value = savedQuesAnsSetting;
            playbackFileContainer.activityQuestionAnswerSetting.setOnValueChangedListener { _: NumberPicker, _: Int, newValue: Int ->
                GlobalData.questionAnswerSetting.postValue(newValue)
                sharedPreferences?.edit()?.putString(QUES_ANS_SETTING_KEY, quesAnsValues[newValue])?.apply()
            }

            activityTrackReplayRandom.setOnClickListener {
                withPlayer {
                    sendCommand(
                        command = CustomCommands.REPLAY_LAST_RANDOM,
                        extras = bundleOf("NOT_REQUIRED" to "_")
                    )
                }
            }

            updateCommandHistoryList()

            activityPlaySpecificCommand.setOnClickListener {
                val list = GlobalData.commandHistory.value?.toList()
                val selected = list?.get(activityPlaybackCommandItems.value)
                selected?.let {
                    withPlayer {
                        sendCommand(
                            command = CustomCommands.PLAY_COMMAND,
                            extras = bundleOf("index" to selected.index)
                        )
                    }
                }
            }

            activityTrackProgressCurrent.setOnClickListener { seekBack() }
            activityTrackProgressMax.setOnClickListener { seekForward() }
            activityTrackPlaybackSetting.setOnClickListener { togglePlaybackSetting() }
            activityTrackSpeedClickArea.setOnClickListener { showPlaybackSpeedPicker() }
            playbackFileContainer.activityTrackPlaybackControlFileBtn.setOnClickListener { openFilePicker() }
            setupShuffleButton()
            setupPlaybackSettingButton()
            setupSeekbar()

            arrayOf(activityTrackPrevious, activityTrackPlayPause, activityTrackNext).forEach {
                it.applyColorFilter(getProperTextColor())
            }
        }
    }

    private fun updateCommandHistoryList(): ActivityTrackBinding {
        val b = binding
        val snapshot = GlobalData.commandHistory.value?.toList() ?: emptyList()

        b.root.post {
            val picker = b.activityPlaybackCommandItems

            // Reset displayedValues first to avoid old array reference crash
            picker.displayedValues = null

            if (snapshot.isNotEmpty()) {
                val displayed = snapshot.map { it.value.text.take(20) + "..." }.toTypedArray()

                picker.minValue = 0
                picker.maxValue = displayed.size - 1
                picker.displayedValues = displayed

                picker.value = (displayed.size - 1).coerceIn(picker.minValue, picker.maxValue)

                b.activityPlaySpecificCommand.visibility = VISIBLE
            } else {
                // Fallback for empty list: single placeholder
                val placeholder = arrayOf("No commands")

                picker.minValue = 0
                picker.maxValue = 0
                picker.displayedValues = placeholder
                picker.value = 0

                b.activityPlaySpecificCommand.visibility = INVISIBLE
            }
        }

        return b
    }

    private fun updateGlobalValue(value: MutableLiveData<Int>, idx: Int, displayedValues: Array<String>): Int {
        val secs = displayedValues[idx].replace("s", "")
        val secsValue = secs.toInt()
        value.postValue(secsValue)
        return secsValue
    }

    private fun setupNextTrackInfo(item: MediaItem?) {
        val track = item?.toTrack()
        if (track == null) {
            binding.nextTrackHolder.beGone()
            return
        }

        binding.nextTrackHolder.beVisible()
        val artist = if (track.artist.trim().isNotEmpty() && track.artist != MediaStore.UNKNOWN_STRING) {
            " • ${track.artist}"
        } else {
            ""
        }

        @SuppressLint("SetTextI18n")
        binding.nextTrackLabel.text = "${getString(R.string.next_track)} ${track.title}$artist"

        getTrackCoverArt(track) { coverArt ->
            val cornerRadius = resources.getDimension(com.simplemobiletools.commons.R.dimen.rounded_corner_radius_small).toInt()
            val wantedSize = resources.getDimension(R.dimen.song_image_size).toInt()

            // change cover image manually only once loaded successfully to avoid blinking at fails and placeholders
            loadGlideResource(
                model = coverArt,
                options = RequestOptions().transform(CenterCrop(), RoundedCorners(cornerRadius)),
                size = Size(wantedSize, wantedSize),
                onLoadFailed = {
                    runOnUiThread {
                        binding.nextTrackImage.setImageDrawable(nextTrackPlaceholder)
                    }
                },
                onResourceReady = {
                    runOnUiThread {
                        binding.nextTrackImage.setImageDrawable(it)
                    }
                }
            )
        }
    }

    private fun setupTopArt(track: Track) {
        getTrackCoverArt(track) { coverArt ->
            var wantedHeight = resources.getCoverArtHeight()
            wantedHeight = min(wantedHeight, realScreenSize.y / 2)
            val wantedWidth = realScreenSize.x

            // change cover image manually only once loaded successfully to avoid blinking at fails and placeholders
            loadGlideResource(
                model = coverArt,
                options = RequestOptions().centerCrop(),
                size = Size(wantedWidth, wantedHeight),
                onLoadFailed = {
                    val drawable = resources.getDrawable(R.drawable.ic_headset)
                    val placeholder = getResizedDrawable(drawable, wantedHeight)
                    placeholder.applyColorFilter(getProperTextColor())

                    runOnUiThread {
                        binding.activityTrackImage.setImageDrawable(placeholder)
                    }
                },
                onResourceReady = {
                    val coverHeight = it.intrinsicHeight
                    if (coverHeight > 0 && binding.activityTrackImage.height != coverHeight) {
                        binding.activityTrackImage.layoutParams.height = coverHeight
                    }

                    runOnUiThread {
                        binding.activityTrackImage.setImageDrawable(it)
                    }
                }
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFlingListener() {
        val flingListener = object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null) {
                    if (velocityY > 0 && velocityY > velocityX && e2.y - e1.y > SWIPE_DOWN_THRESHOLD) {
                        finish()
                        binding.activityTrackTopShadow.animate().alpha(0f).start()
                        overridePendingTransition(0, com.simplemobiletools.commons.R.anim.slide_down)
                    }
                }
                return super.onFling(e1, e2, velocityX, velocityY)
            }
        }

        val gestureDetector = GestureDetectorCompat(this, flingListener)
        binding.activityTrackHolder.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun toggleShuffle() {
        val isShuffleEnabled = !config.isShuffleEnabled
        config.isShuffleEnabled = isShuffleEnabled
        toast(if (isShuffleEnabled) R.string.shuffle_enabled else R.string.shuffle_disabled)
        setupShuffleButton()
        withPlayer {
            shuffleModeEnabled = config.isShuffleEnabled
            setupNextTrackInfo(nextMediaItem)
        }
    }

    private fun setupShuffleButton(isShuffleEnabled: Boolean = config.isShuffleEnabled) {
        binding.activityTrackToggleShuffle.apply {
            applyColorFilter(if (isShuffleEnabled) getProperPrimaryColor() else getProperTextColor())
            alpha = if (isShuffleEnabled) 1f else MEDIUM_ALPHA
            contentDescription = getString(if (isShuffleEnabled) R.string.disable_shuffle else R.string.enable_shuffle)
        }
    }

    private fun seekBack() {
        binding.activityTrackProgressbar.progress += -SEEK_INTERVAL_S
        withPlayer { seekBack() }
    }

    private fun seekForward() {
        binding.activityTrackProgressbar.progress += SEEK_INTERVAL_S
        withPlayer { seekForward() }
    }

    fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.setType("text/*")
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(Intent.createChooser(intent, "Select File"), PICK_FILE_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == RESULT_OK) {
            resultData?.data?.let {
                val p = it
                ensureBackgroundThread {
                    updatePlaybackControlFile(p)
                }
            }
        }
    }

    private fun updatePlaybackControlFile(p: Uri) {
        try {
            val playbackFileContent = readTextFromUri(this, p)
            if (playbackFileContent.isNotEmpty()) {
                setPlaybackCommands(playbackFileContent)
            }

            val ah = this.audioHelper
            withPlayer {
                val track = this.currentMediaItem?.toTrack()
                val id = track?.id
                if (id != null) {
                    track.playbackFile = playbackFileContent
                    ah.updatePlaybackControlFile(playbackFileContent, id)
                }
            }
        } catch (e: Exception) {
            this.showErrorToast(e)
        }
    }

    private fun togglePlaybackSetting() {
        val newPlaybackSetting = config.playbackSetting.nextPlaybackOption
        config.playbackSetting = newPlaybackSetting
        toast(newPlaybackSetting.descriptionStringRes)
        setupPlaybackSettingButton()
        withPlayer {
            setRepeatMode(newPlaybackSetting)
        }
    }

    private fun maybeUpdatePlaybackSettingButton(playbackSetting: PlaybackSetting) {
        if (config.playbackSetting != PlaybackSetting.STOP_AFTER_CURRENT_TRACK) {
            setupPlaybackSettingButton(playbackSetting)
        }
    }

    private fun setupPlaybackSettingButton(playbackSetting: PlaybackSetting = config.playbackSetting) {
        binding.activityTrackPlaybackSetting.apply {
            contentDescription = getString(playbackSetting.contentDescriptionStringRes)
            setImageResource(playbackSetting.iconRes)

            val isRepeatOff = playbackSetting == PlaybackSetting.REPEAT_OFF

            alpha = if (isRepeatOff) MEDIUM_ALPHA else 1f
            applyColorFilter(if (isRepeatOff) getProperTextColor() else getProperPrimaryColor())
        }
    }

    private fun setupSeekbar() {
        binding.activityTrackSpeedIcon.applyColorFilter(getProperTextColor())
        updatePlaybackSpeed(config.playbackSpeed)

        binding.activityTrackProgressbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val formattedProgress = progress.getFormattedDuration()
                binding.activityTrackProgressCurrent.text = formattedProgress
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) = withPlayer {
                seekTo(seekBar.progress * 1000L)
            }
        })
    }

    private fun showPlaybackSpeedPicker() {
        val fragment = PlaybackSpeedFragment()
        fragment.show(supportFragmentManager, PlaybackSpeedFragment::class.java.simpleName)
        fragment.setListener(this)
    }

    override fun updatePlaybackSpeed(speed: Float) {
        val isSlow = speed < 1f
        if (isSlow != binding.activityTrackSpeed.tag as? Boolean) {
            binding.activityTrackSpeed.tag = isSlow

            val drawableId = if (isSlow) R.drawable.ic_playback_speed_slow_vector else R.drawable.ic_playback_speed_vector
            binding.activityTrackSpeedIcon.setImageDrawable(resources.getDrawable(drawableId))
        }

        @SuppressLint("SetTextI18n")
        binding.activityTrackSpeed.text = "${DecimalFormat("#.##").format(speed)}x"
        withPlayer {
            setPlaybackSpeed(speed)
        }
    }

    private fun getResizedDrawable(drawable: Drawable, wantedHeight: Int): Drawable {
        val bitmap = (drawable as BitmapDrawable).bitmap
        val bitmapResized = Bitmap.createScaledBitmap(bitmap, wantedHeight, wantedHeight, false)
        return BitmapDrawable(resources, bitmapResized)
    }

    override fun onPlaybackStateChanged(playbackState: Int) = updatePlayerState()

    override fun onIsPlayingChanged(isPlaying: Boolean) = updatePlayerState()

    override fun onRepeatModeChanged(repeatMode: Int) = maybeUpdatePlaybackSettingButton(getPlaybackSetting(repeatMode))

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = setupShuffleButton(shuffleModeEnabled)

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        super.onMediaItemTransition(mediaItem, reason)
        if (mediaItem == null) {
            finish()
        } else {
            binding.activityTrackProgressbar.progress = 0
            updateTrackInfo()
        }
    }


    private fun updateTrackInfo() {
        val ctx = this
        GlobalData.playbackFileContent.postValue("")
        withPlayer {
            setupTrackInfo(currentMediaItem)
            setupNextTrackInfo(nextMediaItem)
            PlaybackService.playbackCommands = listOf()
            val track = this.currentMediaItem?.toTrack()
            val id = track?.id
            if (id != null) {
                ctx.audioHelper.getPlaybackControlFile(id) { playbackFile ->
                    ensureBackgroundThread {
                        if (playbackFile.isNotEmpty()) {
                            GlobalData.playbackFileName.postValue("<from db>")
                            setPlaybackCommands(playbackFile.trimIndent())
                            //TODO: Handle other possibilities
                            if(GlobalData.questionAnswerSetting.value == 0) {
                                PlaybackService.turnForPart = true
                            } else {
                                PlaybackService.turnForPart = false
                            }
                        } else {
                            GlobalData.playbackFileName.postValue("[Playback File]")
                        }
                        maybeSeekRandom()
                    }
                }
            }
        }
    }

    private fun maybeSeekRandom() {
        if (GlobalData.randomSeekEnabled.value == true) {
            withPlayer {
                sendCommand(
                    command = CustomCommands.SEEK_RANDOM,
                    extras = bundleOf("NOT_REQUIRED" to "_")
                )
            }
        }
    }

    private fun updatePlayerState() {
        withPlayer {
            val isPlaying = isReallyPlaying
            if (isPlaying) {
                scheduleProgressUpdate()
            } else {
                cancelProgressUpdate()
            }

            updateProgress(currentPosition)
            updatePlayPause(isPlaying)
            setupShuffleButton(shuffleModeEnabled)
            maybeUpdatePlaybackSettingButton(getPlaybackSetting(repeatMode))
        }
    }

    private fun scheduleProgressUpdate() {
        cancelProgressUpdate()
        withPlayer {
            val delayInMillis = (updateIntervalMillis / config.playbackSpeed).toLong()
            handler.postDelayed(delayInMillis) {
                updateProgress(currentPosition)
                scheduleProgressUpdate()
            }
        }
    }

    private fun cancelProgressUpdate() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateProgress(currentPosition: Long) {
        binding.activityTrackProgressbar.progress = currentPosition.milliseconds.inWholeSeconds.toInt()
    }

    private fun updatePlayPause(isPlaying: Boolean) {
        binding.activityTrackPlayPause.updatePlayPauseIcon(isPlaying, getProperTextColor())
    }
}
