package com.satya.musicplayer.piano

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.*
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ToggleButton
import androidx.fragment.app.DialogFragment
import com.satya.musicplayer.PianoSoundManager

class PianoDialogFragment : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Force landscape
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private lateinit var soundManager: PianoSoundManager

    override fun onAttach(context: Context) {
        super.onAttach(context)
        // Try to obtain the provider from activity/context
        val provider = when {
            context is SoundManagerProvider -> context
            activity is SoundManagerProvider -> activity as SoundManagerProvider
            else -> null
        }
        provider?.let {
            soundManager = it.pianoSoundManager
        } ?: throw IllegalStateException("Host activity must implement SoundManagerProvider")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        if (!::soundManager.isInitialized) {
            throw IllegalStateException("soundManager must be set before showing PianoDialogFragment")
        }

        // Root container
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Top bar: Close + Pedal
        val topBar = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val closeButton = Button(requireContext()).apply {
            text = "Close"
            setOnClickListener { dismiss() }
        }

        val pedalButton = Button(requireContext()).apply {
            text = "Pedal"
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> soundManager.setSustainLevel(1f)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> soundManager.setSustainLevel(0f)
                }
                true
            }
        }

        topBar.addView(closeButton)
        topBar.addView(pedalButton)

        // Scrollable piano container
        val scrollView = HorizontalScrollView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }

        val pianoContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Add piano keys (multi-octave)
        val pianoView = PianoView(requireContext(), soundManager, startNote = 45, endNote = 84)
        pianoContainer.addView(pianoView)
        scrollView.addView(pianoContainer)

        val toggleScroll = ToggleButton(requireContext()).apply {
            textOn = "SCRL OFF"
            textOff = "SCRL ON"
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                pianoView.setScrollable(isChecked)
            }
        }

        topBar.addView(toggleScroll)
        // Assemble root layout
        root.addView(topBar)
        root.addView(scrollView)

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Restore portrait when dialog closes
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}
