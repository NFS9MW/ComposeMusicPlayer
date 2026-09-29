package com.rhp.mediaplayer.player

import com.rhp.mediaplayer.model.LoopMode
import com.rhp.mediaplayer.model.Song
import kotlin.random.Random

/**
 * What the queue wants the player to do next.
 *
 * Advance operations return one of these instead of mutating playback directly,
 * which keeps this class free of any engine or UI dependency and makes every
 * branch assertable in a test.
 */
sealed interface QueueOutcome {
    /**
     * Play [song].
     *
     * [restart] is true when the song is already the current one and must begin
     * again from the top -- repeat-one, "previous" with no history, and clicking
     * the row that is already playing all land here.
     */
    data class Play(val song: Song, val restart: Boolean = false) : QueueOutcome

    /** The pass is over and looping is off: playback must stop. */
    data object Stop : QueueOutcome

    /** The queue holds nothing to play. */
    data object Empty : QueueOutcome
}

/**
 * Decides which track plays when, and in what order.
 *
 * The design rests on two separate orderings, which is what makes the
 * Apple-Music-style shuffle behave sensibly:
 *
 *  - **Natural order** is the playlist sorted by name. It is what the main list
 *    displays and it never depends on playback.
 *  - **Active order** is the plan for this pass. With shuffle off it *is* the
 *    natural order; with shuffle on it is a permutation of it.
 *
 * Shuffle is implemented as a full permutation (Fisher-Yates) rather than as
 * random picking, so "every song plays at most once per pass" falls out of the
 * data structure instead of needing de-duplication logic. Letting the pass run
 * to its end and looping is what re-permutes it.
 *
 * **What has played is a list, not a position.** The tempting shortcut is to
 * call everything ahead of the current track "played", which is right only
 * while playback walks the order from one end to the other. The moment the user
 * picks a song out of the middle, everything the jump passed over would be
 * reported as heard. [playedThisPass] is therefore recorded as playback
 * actually happens, and the three lists are read from it:
 *
 *  * played   = [playedThisPass]
 *  * current  = [current]
 *  * upcoming = the active order minus both
 *
 * Everything here is deterministic given [random], so the ordering rules are
 * unit-testable without a sound card or an audio file.
 */
class PlaybackQueue(private val random: Random = Random.Default) {

    private var natural: List<Song> = emptyList()
    private var active: List<Song> = emptyList()

    /** The track under the cursor. Null only while the queue is empty. */
    private var playing: Song? = null

    /** Songs played before the current one, in playback order, oldest first. */
    private val historyStack = ArrayDeque<Song>()

    /**
     * What has actually been heard in this pass, in playback order.
     *
     * Separate from [historyStack] because the two answer different questions:
     * this one is "what does the queue still owe me", the other is "where does
     * previous go". They coincide today, but a pass boundary clears the first
     * and must not clear the second.
     */
    private val playedThisPass = ArrayDeque<Song>()

    private var shuffled = false
    private var loopMode = LoopMode.Off
    private var passFinished = false

    // ------------------------------------------------------------- inspection

    val naturalOrder: List<Song> get() = natural
    val activeOrder: List<Song> get() = active
    val shuffle: Boolean get() = shuffled
    val loop: LoopMode get() = loopMode
    val isEmpty: Boolean get() = active.isEmpty()
    val current: Song? get() = playing

    /** Already played in this pass. Empty at the start of a pass by definition. */
    val playedInPass: List<Song> get() = playedThisPass.toList()

    /** Still to come in this pass, excluding the current track. */
    val upNext: List<Song>
        get() {
            val now = playing ?: return emptyList()
            val playedIds = playedThisPass.mapTo(HashSet()) { it.id }
            return active.filter { it.id != now.id && it.id !in playedIds }
        }

    val history: List<Song> get() = historyStack.toList()
    val hasPrevious: Boolean get() = historyStack.isNotEmpty()

    /** True once a pass ended with looping off: playback is parked at the last track. */
    val finished: Boolean get() = passFinished

    // ------------------------------------------------------------- mutations

    /**
     * Replaces the playable songs, keeping the current track current if it
     * survived the change.
     *
     * Called for imports, removals and re-sorts. The played list survives too:
     * adding a file halfway through an album is not a reason to forget which
     * tracks have been heard, nor to offer them again.
     */
    fun setSongs(songs: List<Song>) {
        val ids = songs.mapTo(HashSet()) { it.id }
        val anchorId = playing?.id
        val anchorSurvived = anchorId != null && anchorId in ids

        natural = songs
        historyStack.retainAll { it.id in ids }
        // A track that has left the playlist is no longer owed a play.
        playedThisPass.retainAll { it.id in ids }

        active = if (shuffled) permutedAround(anchorId) else songs
        playing = if (anchorSurvived) playing else firstUnplayed()
        passFinished = false
    }

    /**
     * Turns shuffle on or off.
     *
     * Switching it on starts a fresh pass: the current track stays current and
     * the new permutation opens with it, so every *other* track still gets
     * exactly one play before the pass ends. Switching it off simply re-reads
     * the natural order and re-locates the current track, so playback continues
     * uninterrupted and whatever has already played stays played.
     */
    fun setShuffle(enabled: Boolean) {
        if (enabled == shuffled) return
        shuffled = enabled
        val anchorId = playing?.id

        if (enabled) {
            playedThisPass.clear()
            active = permutedAround(anchorId)
            playing = active.firstOrNull()
        } else {
            active = natural
            playing = if (anchorId != null) natural.firstOrNull { it.id == anchorId } else null
                ?: firstUnplayed()
        }
        passFinished = false
    }

    /**
     * Swaps in updated copies of songs without disturbing anything else.
     *
     * Metadata arrives progressively while a playlist is already loaded, and
     * re-running [setSongs] for each arriving tag would re-permute a shuffled
     * queue dozens of times during a single import. Since ids do not change,
     * order, cursor and history can all be preserved and only the objects
     * replaced.
     */
    fun refreshMetadata(songs: List<Song>) {
        if (songs.isEmpty()) return
        val byId = songs.associateBy { it.id }

        natural = natural.map { byId[it.id] ?: it }
        active = active.map { byId[it.id] ?: it }
        playing = playing?.let { byId[it.id] ?: it }

        refreshStack(historyStack, byId)
        refreshStack(playedThisPass, byId)
    }

    private fun refreshStack(stack: ArrayDeque<Song>, byId: Map<String, Song>) {
        if (stack.isEmpty()) return
        val refreshed = stack.map { byId[it.id] ?: it }
        stack.clear()
        stack.addAll(refreshed)
    }

    fun setLoop(mode: LoopMode) {
        loopMode = mode
        // Looping again after the pass ran out should be able to resume.
        if (mode != LoopMode.Off) passFinished = false
    }

    /**
     * Puts the session back on [songId], at the head of a fresh pass.
     *
     * This is the whole of what "resume" means here, and it is deliberately
     * narrow: the track is carried over, nothing else is. Nothing has been heard
     * yet in this session, so the played list and the history both start empty,
     * and with shuffle on the permutation is dealt again with this track at its
     * head -- so pressing play carries on with what the user was listening to,
     * and the rest of the pass follows in a new order.
     *
     * Anchoring it at the head rather than leaving it where the shuffle happened
     * to put it is what makes "next" walk through the whole rest of the playlist
     * instead of continuing from an arbitrary point and wrapping round at the
     * end. Callers restoring a *position* (sequential order) follow this with
     * [assumePlayedUpTo].
     */
    fun resumeAt(songId: String) {
        val song = active.firstOrNull { it.id == songId } ?: return

        playedThisPass.clear()
        historyStack.clear()
        if (shuffled) active = permutedAround(songId)

        playing = song
        passFinished = false
    }

    /**
     * Assumes everything ahead of [songId] in the order has already been heard.
     *
     * This exists for one caller: restoring a saved playhead in sequential
     * order. There, a position in the list genuinely does record the listening
     * -- reopening on the seventh track means six tracks were heard -- and
     * without saying so the queue would offer those six again further down the
     * same pass.
     *
     * It is deliberately qualified as an assumption rather than a fact, because
     * that is what it is: the played list itself is not persisted, so this is
     * inferred from a position. Which is also why it must never be used while
     * shuffling, where a position in a permutation says nothing at all about
     * what came before it.
     *
     * Both stacks are seeded, so "previous" walks back through what was heard,
     * just as it would have done had the session never ended.
     */
    fun assumePlayedUpTo(songId: String) {
        val index = active.indexOfFirst { it.id == songId }
        if (index < 0) return

        val heard = active.subList(0, index)
        playedThisPass.clear()
        playedThisPass.addAll(heard)
        historyStack.clear()
        historyStack.addAll(heard)
        passFinished = false
    }

    /**
     * Jumps to a specific song, as when the user clicks a row.
     *
     * A jump, not an advance, and the distinction is the whole point:
     *
     *  * the songs between the current track and the one picked have **not**
     *    been heard, so none of them is recorded as played. They stay exactly
     *    where they are in the order, which is what makes the upcoming list
     *    read like the playlist with the heard tracks taken out;
     *  * the track being left is not recorded either. It was cut short rather
     *    than finished, so it keeps its place and gets its play later instead
     *    of being written off as heard.
     *
     * The one thing that does move is the current track, and the interrupted
     * one is remembered in the history so that "previous" returns to it.
     */
    fun select(songId: String): QueueOutcome {
        val target = active.firstOrNull { it.id == songId } ?: return QueueOutcome.Empty
        val previous = playing

        if (previous != null && previous.id == target.id) {
            // The row that is already playing: a restart, not a move.
            return QueueOutcome.Play(target, restart = true)
        }
        if (previous != null) historyStack.addLast(previous)

        // Revisiting something already heard makes it current again, so it
        // cannot stay on the list of tracks behind us.
        playedThisPass.removeAll { it.id == target.id }

        playing = target
        passFinished = false
        return QueueOutcome.Play(target)
    }

    /** The user pressed "next": move on, whatever the loop mode says. */
    fun skipNext(): QueueOutcome = stepForward()

    /**
     * The current track reached its end on its own.
     *
     * This is where repeat-one lives, because that mode means "the track never
     * advances" rather than "the track advances differently". A manual skip is
     * unaffected by it, which matches how every mainstream player behaves.
     */
    fun onTrackFinished(): QueueOutcome {
        val playing = current ?: return QueueOutcome.Empty
        if (loopMode == LoopMode.One) {
            return QueueOutcome.Play(playing, restart = true)
        }
        return stepForward()
    }

    /**
     * The user pressed "previous".
     *
     * Deliberately driven by the history stack rather than by the position in
     * the active order: with shuffle on, the previously played track is almost
     * never the previous entry, so positional "previous" would feel broken.
     * Going back also un-plays the track being left, which returns it to the
     * upcoming list where it came from.
     */
    fun skipPrevious(): QueueOutcome {
        val playing = current ?: return QueueOutcome.Empty

        val previous = historyStack.removeLastOrNull()
            ?: return QueueOutcome.Play(playing, restart = true)

        if (active.none { it.id == previous.id }) {
            // The track was removed from the playlist after it played.
            return QueueOutcome.Play(playing, restart = true)
        }

        if (playedThisPass.lastOrNull()?.id == previous.id) {
            playedThisPass.removeLast()
        }
        this.playing = previous
        passFinished = false
        return QueueOutcome.Play(previous)
    }

    /** Replays the current track from the beginning. */
    fun replayCurrent(): QueueOutcome {
        val playing = current ?: return QueueOutcome.Empty
        return QueueOutcome.Play(playing, restart = true)
    }

    /**
     * Starts a brand new pass from the top, re-permuting when shuffle is on.
     *
     * This is what pressing play does after a pass ran out with looping off.
     */
    fun beginNewPass(): QueueOutcome {
        if (active.isEmpty()) return QueueOutcome.Empty
        playedThisPass.clear()
        active = permutedForNewPass(playing?.id)
        val first = active.firstOrNull() ?: return QueueOutcome.Empty
        playing = first
        passFinished = false
        return QueueOutcome.Play(first)
    }

    // --------------------------------------------------------------- internals

    private fun stepForward(): QueueOutcome {
        val playing = current ?: return QueueOutcome.Empty
        val next = upNext.firstOrNull()

        if (next != null) {
            historyStack.addLast(playing)
            playedThisPass.addLast(playing)
            this.playing = next
            passFinished = false
            return QueueOutcome.Play(next)
        }

        return when (loopMode) {
            LoopMode.Off -> {
                // Park on the last track, but stay paused there.
                passFinished = true
                QueueOutcome.Stop
            }

            LoopMode.All, LoopMode.One -> {
                historyStack.addLast(playing)
                playedThisPass.clear()
                active = permutedForNewPass(playing.id)
                val first = active.firstOrNull() ?: return QueueOutcome.Stop
                this.playing = first
                passFinished = false
                QueueOutcome.Play(first)
            }
        }
    }

    /** Full permutation with [anchorId] pinned to the head, when it is present. */
    private fun permutedAround(anchorId: String?): List<Song> {
        if (natural.isEmpty()) return emptyList()

        val anchor = anchorId?.let { id -> natural.firstOrNull { it.id == id } }
            ?: return shuffledCopy(natural)

        val rest = natural.filterNot { it.id == anchor.id }.toMutableList()
        shuffleInPlace(rest)
        return buildList {
            add(anchor)
            addAll(rest)
        }
    }

    /**
     * A fresh permutation for the next pass.
     *
     * [justFinishedId] is nudged away from the head: a permutation may legally
     * open with the track that just ended, but hearing the same song twice in a
     * row reads as a bug even when it is correct randomness.
     */
    private fun permutedForNewPass(justFinishedId: String?): List<Song> {
        val ordered = if (shuffled) shuffledCopy(natural) else natural
        if (ordered.size <= 1 || ordered.first().id != justFinishedId) return ordered

        val swapWith = 1 + random.nextInt(ordered.size - 1)
        val mutable = ordered.toMutableList()
        val tmp = mutable[0]
        mutable[0] = mutable[swapWith]
        mutable[swapWith] = tmp
        return mutable
    }

    /** The first track the pass still owes a play, or the head as a fallback. */
    private fun firstUnplayed(): Song? {
        val playedIds = playedThisPass.mapTo(HashSet()) { it.id }
        return active.firstOrNull { it.id !in playedIds } ?: active.firstOrNull()
    }

    private fun shuffledCopy(source: List<Song>): List<Song> =
        source.toMutableList().also { shuffleInPlace(it) }

    /** Fisher-Yates: every permutation equally likely, each song exactly once. */
    private fun shuffleInPlace(items: MutableList<Song>) {
        for (i in items.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            if (i != j) {
                val tmp = items[i]
                items[i] = items[j]
                items[j] = tmp
            }
        }
    }
}
