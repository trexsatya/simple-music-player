package com.satya.musicplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.SparseIntArray
import kotlin.math.pow

class PianoSoundManager(context: Context) {
    private val soundPool = SoundPool.Builder().setAudioAttributes(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    ).setMaxStreams(16).build()
    private val soundMap = mutableMapOf<String, Int>()
    private val activeNotes = mutableMapOf<Int, Float>() // streamId → sustainLevel

    /** 0.0 = none, 1.0 = full pedal */
    var sustainLevel = 0f
        private set

    init {
        val notes = listOf(
            // C1 - B1
            "c1", "c_sharp1", "d1", "d_sharp1", "e1", "f1", "f_sharp1",
            "g1", "g_sharp1", "a1", "a_sharp1", "b1",

            // C2 - B2
            "c2", "c_sharp2", "d2", "d_sharp2", "e2", "f2", "f_sharp2",
            "g2", "g_sharp2", "a2", "a_sharp2", "b2",

            // C3 - B3
            "c3", "c_sharp3", "d3", "d_sharp3", "e3", "f3", "f_sharp3",
            "g3", "g_sharp3", "a3", "a_sharp3", "b3",

            // C4 - B4
            "c4", "c_sharp4", "d4", "d_sharp4", "e4", "f4", "f_sharp4",
            "g4", "g_sharp4", "a4", "a_sharp4", "b4",

            // C5 - B5
            "c5", "c_sharp5", "d5", "d_sharp5", "e5", "f5", "f_sharp5",
            "g5", "g_sharp5", "a5", "a_sharp5", "b5",

            // C6 - B6
            "c6", "c_sharp6", "d6", "d_sharp6", "e6", "f6", "f_sharp6",
            "g6", "g_sharp6", "a6", "a_sharp6", "b6"
        )

        for (note in notes) {
            val resId = context.resources.getIdentifier(note, "raw", context.packageName)
            if (resId != 0) soundMap[note] = soundPool.load(context, resId, 1)
        }

        soundMap["gong_1"] = soundPool.load(context, R.raw.gong_1, 1)
        soundMap["gong_2"] = soundPool.load(context, R.raw.gong_2, 1)
    }

    fun play(note: String) {
        val nm  = note.replace("#", "_sharp")
        val soundId = soundMap[nm] ?: return
        val streamId = soundPool.play(soundId, 3.0f, 3.0f, 1, 0, 1f)
        activeNotes[streamId] = sustainLevel
    }

    /** Stop only notes that are below the current sustain threshold */
    fun releaseNote() {
        if (sustainLevel < 0.2f) stopAll()
    }

    fun setSustainLevel(level: Float) {
        sustainLevel = level.coerceIn(0f, 1f)

        // When pedal lifts, fade sustained notes
        if (sustainLevel == 0f) {
            fadeOutAll()
        }
    }

    private fun fadeOutAll() {
        // quick linear fade using volume ramp
        for (id in activeNotes.keys) {
            for (i in 10 downTo 1) {
                val vol = (i / 10f).pow(2)
                soundPool.setVolume(id, vol, vol)
                Thread.sleep(15)
            }
            soundPool.stop(id)
        }
        activeNotes.clear()
    }

    fun stopAll() {
        for (id in activeNotes.keys) soundPool.stop(id)
        activeNotes.clear()
    }

    fun release() = soundPool.release()
}
