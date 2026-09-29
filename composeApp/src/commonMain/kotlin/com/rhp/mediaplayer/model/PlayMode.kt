package com.rhp.mediaplayer.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** What happens when the queue runs past its last track. */
@Serializable
enum class LoopMode {
    /** Play the queue once, then stop. */
    Off,

    /** Start a fresh pass over the whole playlist. Re-shuffles when shuffle is on. */
    All,

    /** Repeat the current track forever; the cursor never moves. */
    One;

    /** The order a single toggle button cycles through. */
    fun next(): LoopMode = when (this) {
        Off -> All
        All -> One
        One -> Off
    }
}

/**
 * The two independent playback switches. They are kept together because their
 * combination is what the queue branches on, and because both are persisted.
 */
@Immutable
@Serializable
data class PlayMode(
    val shuffle: Boolean = false,
    val loop: LoopMode = LoopMode.Off,
) {
    val repeatsCurrent: Boolean get() = loop == LoopMode.One

    fun withNextLoopMode(): PlayMode = copy(loop = loop.next())
}
