package com.rhp.mediaplayer.player

import com.rhp.mediaplayer.model.Song

/**
 * Notified by the engine. Callbacks arrive on the audio thread, so the
 * implementation behind this must hop to wherever the UI state lives.
 */
interface AudioEngineListener {
    /** The track reached its natural end and finished playing out. */
    fun onTrackCompleted()

    /** Playback could not continue. [message] is meant for the user. */
    fun onPlaybackError(message: String)
}

/**
 * Plays one song at a time.
 *
 * Kept deliberately narrow: it knows about songs and positions, and nothing
 * about queues, shuffle or playlists. Everything that decides *what* to play
 * lives in [PlaybackQueue], which is why the ordering rules can be tested
 * without an audio device.
 *
 * Position is polled rather than pushed. The UI needs it about ten times a
 * second, and polling keeps the read path trivial to reason about for the
 * interop rules described in the design notes.
 */
interface AudioEngine {
    var listener: AudioEngineListener?

    /** True while audio is actively being fed to the output. */
    val isPlaying: Boolean

    /** Playback position in milliseconds, including the offset playback began at. */
    val positionMs: Long

    /** Begins [song] at [startMs]. Any current playback is replaced. */
    fun play(song: Song, startMs: Long = 0L)

    /** Halts audio but keeps the position; [resume] continues from there. */
    fun pause()

    /** Continues after [pause]. Does nothing when not paused. */
    fun resume()

    /** Stops playback and releases the current track. */
    fun stop()

    /** Moves to [positionMs] within the current song, preserving play/pause. */
    fun seekTo(positionMs: Long)

    /** [volume] is clamped to 0f..1f, where 1f is unattenuated. */
    fun setVolume(volume: Float)

    /**
     * Touches the decoder in the background so the first real playback is not
     * the one paying for cold file-cache and library loading.
     */
    fun prewarm()

    /** Stops playback and frees every resource. The engine is unusable after this. */
    fun release()
}
