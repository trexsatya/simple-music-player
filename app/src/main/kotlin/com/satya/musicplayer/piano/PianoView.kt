package com.satya.musicplayer.piano

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.satya.musicplayer.PianoSoundManager
import kotlin.math.roundToInt

class PianoView(
    context: Context,
    private val soundManager: PianoSoundManager,
    private val startNote: Int = 48, // C3
    private val endNote: Int = 72    // C5
) : FrameLayout(context) {

    private val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val allNotes: List<String>
    private val whiteNotes: List<String>

    private val WHITE_KEY_WIDTH = 180f
    private val WHITE_KEY_HEIGHT = 300f
    private val BLACK_KEY_WIDTH = 120f
    private val BLACK_KEY_HEIGHT = 180f

    private var isScrollLocked = false
    private val scrollView: LockableHorizontalScrollView

    init {
        allNotes = generateNotes()
        whiteNotes = allNotes.filter { !it.contains("#") }

        scrollView = LockableHorizontalScrollView(context)
        setupScrollablePiano()
    }

    private fun generateNotes(): List<String> {
        val notes = mutableListOf<String>()
        for (midi in startNote..endNote) {
            val octave = midi / 12 - 1
            val name = noteNames[midi % 12] + octave
            notes.add(name)
        }
        return notes
    }

    fun setScrollable(scrollable: Boolean) {
        isScrollLocked = !scrollable
        scrollView.isScrollLocked = !scrollable
    }

    private fun setupScrollablePiano() {
        scrollView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        scrollView.isFillViewport = true

        val container = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        }

        // White keys
        val whiteLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        }

        for (note in whiteNotes) {
            whiteLayout.addView(createWhiteKey(note))
        }
        container.addView(whiteLayout)

        // Black keys
        for ((index, note) in allNotes.withIndex()) {
            if (note.contains("#")) {
                val blackKey = createBlackKey(note)
                val prevWhiteCount = allNotes.subList(0, index).count { !it.contains("#") }
                blackKey.translationX = prevWhiteCount * WHITE_KEY_WIDTH - BLACK_KEY_WIDTH / 2
                container.addView(blackKey)
            }
        }

        scrollView.addView(container)
        addView(scrollView)
    }

    private fun createWhiteKey(note: String): FrameLayout {
        val key = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                WHITE_KEY_WIDTH.roundToInt(),
                WHITE_KEY_HEIGHT.roundToInt()
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(2, Color.LTGRAY)
            }
            tag = note.lowercase()
        }

        val label = TextView(context).apply {
            text = note
            textSize = 14f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                bottomMargin = 6
            }
        }
        key.addView(label)

        key.setOnTouchListener { btn, event ->
            scrollView.requestDisallowInterceptTouchEvent(isScrollLocked)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    soundManager.play(btn.tag.toString())
                    btn.alpha = 0.6f
                    btn.scaleY = 0.95f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    btn.alpha = 1f
                    btn.scaleY = 1f
                }
            }
            true
        }

        return key
    }

    private fun createBlackKey(note: String): FrameLayout {
        val key = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                BLACK_KEY_WIDTH.roundToInt(),
                BLACK_KEY_HEIGHT.roundToInt()
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.BLACK)
                setStroke(2, Color.DKGRAY)
            }
            tag = note.lowercase()
        }

        key.setOnTouchListener { btn, event ->
            scrollView.requestDisallowInterceptTouchEvent(isScrollLocked)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    soundManager.play(btn.tag.toString())
                    btn.alpha = 0.6f
                    btn.scaleY = 0.95f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    btn.alpha = 1f
                    btn.scaleY = 1f
                }
            }
            true
        }

        return key
    }

    class LockableHorizontalScrollView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null
    ) : HorizontalScrollView(context, attrs) {

        var isScrollLocked = false

        override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
            return if (isScrollLocked) false else super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: MotionEvent?): Boolean {
            return if (isScrollLocked) false else super.onTouchEvent(ev)
        }
    }
}
