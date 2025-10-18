package com.satya.musicplayer.piano

import com.satya.musicplayer.PianoSoundManager

class DualPianoController(
    private val upperView: PianoView,
    private val lowerView: PianoView,
    private val soundManager: PianoSoundManager
) {
    private var transposeOffset = 0
    private var linked = false

    fun setLinked(link: Boolean) {
        linked = link
        upperView.invalidate()
        lowerView.invalidate()
    }

    fun isLinked() = linked

    fun transposeUp(semitones: Int = 12) {
        transposeOffset += semitones
        applyTranspose()
    }

    fun transposeDown(semitones: Int = 12) {
        transposeOffset -= semitones
        applyTranspose()
    }

    private fun applyTranspose() {

    }
}
