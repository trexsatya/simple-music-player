package com.satya.musicplayer.piano

import com.satya.musicplayer.PianoSoundManager

// in some shared package, e.g. com.satya.musicplayer.piano
interface SoundManagerProvider {
    val pianoSoundManager: PianoSoundManager
}
