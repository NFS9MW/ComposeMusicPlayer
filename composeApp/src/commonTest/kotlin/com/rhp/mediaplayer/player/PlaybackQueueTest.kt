package com.rhp.mediaplayer.player

import com.rhp.mediaplayer.model.LoopMode
import com.rhp.mediaplayer.model.Song
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ordering rules are the part of this app most likely to be subtly wrong,
 * and the part a human cannot check by clicking around. They are pure logic, so
 * they get pinned down here instead.
 *
 * Every test seeds [Random] explicitly: an unseeded shuffle would make these
 * flaky, and a flaky ordering test is worse than no test.
 */
class PlaybackQueueTest {

    private fun songs(vararg titles: String): List<Song> = titles.map {
        Song(id = "/music/$it.mp3", path = "/music/$it.mp3", title = it)
    }

    private fun PlaybackQueue.currentTitle(): String? = current?.title

    // ------------------------------------------------------------- shuffling

    @Test
    fun `shuffle is a permutation, never a re-pick`() {
        val titles = listOf("a", "b", "c", "d", "e", "f", "g", "h")
        val expected = titles.sorted()

        // Several seeds, because a broken shuffle can still look right once.
        repeat(20) { seed ->
            val queue = PlaybackQueue(Random(seed.toLong()))
            queue.setSongs(songs(*titles.toTypedArray()))
            queue.setShuffle(true)

            assertEquals(titles.size, queue.activeOrder.size)
            assertEquals(expected, queue.activeOrder.map { it.title }.sorted())
        }
    }

    @Test
    fun `enabling shuffle pins the current song to the head of the new pass`() {
        val queue = PlaybackQueue(Random(42))
        queue.setSongs(songs("a", "b", "c", "d"))
        queue.select("/music/c.mp3")

        queue.setShuffle(true)

        assertEquals("c", queue.currentTitle())
        assertEquals(0, queue.activeOrder.indexOfFirst { it.title == "c" })
        assertTrue(queue.playedInPass.isEmpty(), "a fresh pass starts with nothing played")
    }

    @Test
    fun `disabling shuffle restores natural order and keeps playing`() {
        val queue = PlaybackQueue(Random(42))
        queue.setSongs(songs("a", "b", "c", "d"))
        queue.setShuffle(true)
        queue.select("/music/d.mp3")

        queue.setShuffle(false)

        assertEquals("d", queue.currentTitle())
        assertEquals(queue.naturalOrder.map { it.title }, queue.activeOrder.map { it.title })
        assertEquals("d", queue.currentTitle())
    }

    // -------------------------------------------------------------- the pass

    @Test
    fun `a shuffled pass plays every song exactly once then stops`() {
        val queue = PlaybackQueue(Random(7))
        queue.setSongs(songs("a", "b", "c", "d", "e"))
        queue.setShuffle(true)

        val played = mutableListOf<String>()
        queue.currentTitle()?.let { played += it }

        var outcome = queue.onTrackFinished()
        while (outcome is QueueOutcome.Play) {
            played += outcome.song.title
            outcome = queue.onTrackFinished()
        }

        assertIs<QueueOutcome.Stop>(outcome)
        assertEquals(5, played.size, "each song exactly once")
        assertEquals(listOf("a", "b", "c", "d", "e"), played.sorted())
        assertTrue(queue.finished, "the queue should report that the pass ran out")
        assertEquals(played.last(), queue.currentTitle(), "parked on the last track of the pass")
    }

    @Test
    fun `loop all starts a whole new pass covering every song again`() {
        val queue = PlaybackQueue(Random(11))
        queue.setSongs(songs("a", "b", "c"))
        queue.setShuffle(true)
        queue.setLoop(LoopMode.All)

        val played = mutableListOf<String>()
        queue.currentTitle()?.let { played += it }

        repeat(5) {
            val outcome = queue.onTrackFinished()
            assertIs<QueueOutcome.Play>(outcome)
            played += outcome.song.title
        }

        assertFalse(queue.finished)
        // More than one full pass has gone by, and each full pass is a permutation.
        val firstPass = played.take(3)
        assertEquals(listOf("a", "b", "c"), firstPass.sorted())
        val secondPass = played.subList(3, 6)
        assertEquals(listOf("a", "b", "c"), secondPass.sorted())
    }

    @Test
    fun `a new pass does not open with the song that just finished`() {
        repeat(30) { seed ->
            val queue = PlaybackQueue(Random(seed.toLong()))
            queue.setSongs(songs("a", "b", "c", "d"))
            queue.setShuffle(true)

            val justFinished = queue.currentTitle()
            val outcome = queue.beginNewPass()

            assertIs<QueueOutcome.Play>(outcome)
            assertNotEquals(justFinished, outcome.song.title)
        }
    }

    @Test
    fun `played and upcoming sections partition the pass`() {
        val queue = PlaybackQueue(Random(5))
        queue.setSongs(songs("a", "b", "c", "d", "e"))

        assertEquals(0, queue.playedInPass.size)
        assertEquals(4, queue.upNext.size)

        queue.onTrackFinished()
        queue.onTrackFinished()

        assertEquals(2, queue.playedInPass.size)
        assertEquals(2, queue.upNext.size)
        assertEquals(
            queue.activeOrder.map { it.title },
            queue.playedInPass.map { it.title } +
                listOfNotNull(queue.currentTitle()) +
                queue.upNext.map { it.title },
        )
    }

    // --------------------------------------------------------- loop mode one

    @Test
    fun `repeat one replays the track and never moves the cursor`() {
        val queue = PlaybackQueue(Random(3))
        queue.setSongs(songs("a", "b", "c"))
        queue.setLoop(LoopMode.One)

        val before = queue.currentTitle()
        val outcome = queue.onTrackFinished()

        assertIs<QueueOutcome.Play>(outcome)
        assertTrue(outcome.restart, "repeat one must restart rather than resume")
        assertEquals(before, outcome.song.title)
        assertEquals(before, queue.currentTitle(), "the cursor must not advance")
    }

    @Test
    fun `repeat one still honours a manual skip`() {
        val queue = PlaybackQueue(Random(3))
        queue.setSongs(songs("a", "b", "c"))
        queue.setLoop(LoopMode.One)

        val before = queue.currentTitle()
        val outcome = queue.skipNext()

        assertIs<QueueOutcome.Play>(outcome)
        assertFalse(outcome.restart)
        assertNotEquals(before, outcome.song.title)
    }

    // -------------------------------------------------------------- history

    @Test
    fun `previous follows what actually played, not list position`() {
        val queue = PlaybackQueue(Random(9))
        queue.setSongs(songs("a", "b", "c", "d"))
        queue.setShuffle(true)

        // Walk two tracks forward, recording the real order.
        val first = queue.currentTitle()!!
        queue.onTrackFinished()
        val second = queue.currentTitle()!!
        queue.onTrackFinished()

        assertEquals(listOf(first, second), queue.history.map { it.title })

        val outcome = queue.skipPrevious()
        assertIs<QueueOutcome.Play>(outcome)
        assertEquals(second, outcome.song.title)
        assertEquals(second, queue.currentTitle())
    }

    @Test
    fun `previous with no history restarts the current track`() {
        val queue = PlaybackQueue(Random(9))
        queue.setSongs(songs("a", "b"))

        val outcome = queue.skipPrevious()

        assertIs<QueueOutcome.Play>(outcome)
        assertTrue(outcome.restart)
        assertFalse(queue.hasPrevious)
    }

    @Test
    fun `selecting a song records the one it interrupted`() {
        val queue = PlaybackQueue(Random(1))
        queue.setSongs(songs("a", "b", "c"))
        assertEquals("a", queue.currentTitle())

        queue.select("/music/c.mp3")

        assertEquals("c", queue.currentTitle())
        assertEquals(listOf("a"), queue.history.map { it.title })
    }

    @Test
    fun `selecting the song already playing restarts it without touching history`() {
        val queue = PlaybackQueue(Random(1))
        queue.setSongs(songs("a", "b", "c"))

        val outcome = queue.select("/music/a.mp3")

        assertIs<QueueOutcome.Play>(outcome)
        assertTrue(outcome.restart)
        assertTrue(queue.history.isEmpty())
    }

    // ----------------------------------------------------------- rescanning

    @Test
    fun `resorting keeps the current song current`() {
        val queue = PlaybackQueue(Random(2))
        queue.setSongs(songs("c", "a", "b"))
        queue.select("/music/b.mp3")

        // Same tracks, different natural order -- as if the sort direction flipped.
        queue.setSongs(songs("a", "b", "c"))

        assertEquals("b", queue.currentTitle())
    }

    @Test
    fun `removing the current song falls back to the head of the list`() {
        val queue = PlaybackQueue(Random(2))
        queue.setSongs(songs("a", "b", "c"))
        queue.select("/music/b.mp3")

        queue.setSongs(songs("a", "c"))

        assertEquals("a", queue.currentTitle())
    }

    @Test
    fun `history drops songs that left the playlist`() {
        val queue = PlaybackQueue(Random(2))
        queue.setSongs(songs("a", "b", "c"))
        queue.select("/music/b.mp3")
        queue.select("/music/c.mp3")
        assertEquals(listOf("a", "b"), queue.history.map { it.title })

        queue.setSongs(songs("c"))

        assertTrue(queue.history.isEmpty())
        assertEquals("c", queue.currentTitle())
    }

    // ---------------------------------------------------------------- empty

    @Test
    fun `an empty queue answers every request with Empty`() {
        val queue = PlaybackQueue(Random(1))
        queue.setSongs(emptyList())

        assertTrue(queue.isEmpty)
        assertNull(queue.current)
        assertTrue(queue.playedInPass.isEmpty())
        assertTrue(queue.upNext.isEmpty())
        assertIs<QueueOutcome.Empty>(queue.onTrackFinished())
        assertIs<QueueOutcome.Empty>(queue.skipNext())
        assertIs<QueueOutcome.Empty>(queue.skipPrevious())
        assertIs<QueueOutcome.Empty>(queue.beginNewPass())
        assertIs<QueueOutcome.Empty>(queue.select("/music/nope.mp3"))
    }

    @Test
    fun `a single song queue stops instead of looping when loop is off`() {
        val queue = PlaybackQueue(Random(1))
        queue.setSongs(songs("only"))
        queue.setShuffle(true)

        assertIs<QueueOutcome.Stop>(queue.onTrackFinished())
        assertTrue(queue.finished)
        assertEquals("only", queue.currentTitle())
    }

    // ---------------------------------------------------------------- jumping

    /**
     * The bug these pin down: "played" used to be read as "everything ahead of
     * the current track in the order". That is indistinguishable from the truth
     * while playback walks the order from one end, and plainly wrong the moment
     * a song is picked out of the middle -- every track the jump passed over
     * was reported as heard.
     */
    @Test
    fun `jumping ahead does not mark the songs it passed as played`() {
        val queue = PlaybackQueue(Random(1))
        queue.setSongs(songs("a", "b", "c", "d", "e"))

        queue.select("/music/d.mp3")

        assertEquals("d", queue.currentTitle())
        assertTrue(
            queue.playedInPass.isEmpty(),
            "nothing had been heard yet, so nothing is played",
        )
        assertEquals(
            listOf("a", "b", "c", "e"),
            queue.upNext.map { it.title },
            "the songs passed over keep their place and are still owed a play",
        )
    }

    @Test
    fun `the track a jump interrupted is not written off as heard`() {
        val queue = PlaybackQueue(Random(1))
        queue.setSongs(songs("a", "b", "c", "d", "e"))

        queue.select("/music/d.mp3")

        // It keeps its place in line rather than being dropped or marked played.
        assertEquals(listOf("a", "b", "c", "e"), queue.upNext.map { it.title })
        assertEquals(listOf("a"), queue.history.map { it.title })

        val back = queue.skipPrevious()
        assertIs<QueueOutcome.Play>(back)
        assertEquals("a", back.song.title)
    }

    @Test
    fun `a jump plays on through the playlist from wherever it landed`() {
        val queue = PlaybackQueue(Random(1))
        queue.setSongs(songs("a", "b", "c", "d", "e"))
        queue.select("/music/d.mp3")

        val heard = mutableListOf(queue.currentTitle()!!)
        var outcome = queue.onTrackFinished()
        while (outcome is QueueOutcome.Play) {
            heard += outcome.song.title
            outcome = queue.onTrackFinished()
        }

        assertIs<QueueOutcome.Stop>(outcome)
        assertEquals(
            listOf("d", "a", "b", "c", "e"),
            heard,
            "the pass carries on in playlist order, with nothing lost or doubled",
        )
    }

    @Test
    fun `heard tracks are left out of the upcoming list after a jump`() {
        val queue = PlaybackQueue(Random(1))
        queue.setSongs(songs("a", "b", "c", "d", "e"))

        queue.onTrackFinished() // b
        queue.onTrackFinished() // c
        assertEquals(listOf("a", "b"), queue.playedInPass.map { it.title })

        queue.select("/music/e.mp3")

        assertEquals("e", queue.currentTitle())
        assertEquals(
            listOf("a", "b"),
            queue.playedInPass.map { it.title },
            "a jump must not invent plays, nor forget the real ones",
        )
        assertEquals(listOf("c", "d"), queue.upNext.map { it.title })
    }

    @Test
    fun `jumping back to a track that has played replays it`() {
        val queue = PlaybackQueue(Random(1))
        queue.setSongs(songs("a", "b", "c", "d", "e"))

        queue.onTrackFinished() // b
        queue.onTrackFinished() // c
        queue.onTrackFinished() // d
        assertEquals(listOf("a", "b", "c"), queue.playedInPass.map { it.title })
        assertEquals(listOf("e"), queue.upNext.map { it.title })

        queue.select("/music/b.mp3")

        assertEquals("b", queue.currentTitle())
        assertEquals(
            listOf("a", "c"),
            queue.playedInPass.map { it.title },
            "the track now playing is not also listed as one already behind us",
        )
        assertEquals(listOf("d", "e"), queue.upNext.map { it.title })
    }

    @Test
    fun `a jump into the shuffled order leaves the rest of the plan alone`() {
        val queue = PlaybackQueue(Random(4))
        queue.setSongs(songs("a", "b", "c", "d", "e", "f"))
        queue.setShuffle(true)

        val plan = queue.activeOrder.map { it.title }
        val target = plan[4]
        queue.select("/music/$target.mp3")

        assertEquals(target, queue.currentTitle())
        assertTrue(queue.playedInPass.isEmpty())
        assertEquals(
            plan.filterNot { it == target },
            queue.upNext.map { it.title },
            "the permutation itself is untouched -- only the current track moved",
        )
    }

    @Test
    fun `resuming while shuffling puts the track at the head of a fresh pass`() {
        val queue = PlaybackQueue(Random(9))
        queue.setSongs(songs("a", "b", "c", "d", "e"))
        queue.setShuffle(true)
        val target = queue.activeOrder[3].title

        queue.resumeAt("/music/$target.mp3")

        assertEquals(target, queue.currentTitle())
        assertEquals(
            target,
            queue.activeOrder.first().title,
            "next has to walk the rest of the pass, not continue from the middle of it",
        )
        assertTrue(queue.playedInPass.isEmpty(), "a new session starts with nothing heard")
        assertTrue(queue.history.isEmpty(), "and with nothing to go back to")
        assertEquals(4, queue.upNext.size, "every other track is still to come")
    }

    @Test
    fun `resuming on its own records nothing as played`() {
        val queue = PlaybackQueue(Random(1))
        queue.setSongs(songs("a", "b", "c", "d"))
        queue.onTrackFinished() // b
        queue.onTrackFinished() // c

        queue.resumeAt("/music/c.mp3")

        assertEquals("c", queue.currentTitle())
        assertTrue(queue.playedInPass.isEmpty(), "the session before does not count as this one")
        assertTrue(queue.history.isEmpty())
        assertEquals(listOf("a", "b", "d"), queue.upNext.map { it.title })
    }

    @Test
    fun `assuming a restored position means the earlier tracks were heard`() {
        val queue = PlaybackQueue(Random(1))
        queue.setSongs(songs("a", "b", "c", "d"))

        queue.select("/music/c.mp3")
        queue.assumePlayedUpTo("/music/c.mp3")

        assertEquals("c", queue.currentTitle())
        assertEquals(listOf("a", "b"), queue.playedInPass.map { it.title })
        assertEquals(listOf("a", "b"), queue.history.map { it.title })
        assertEquals(
            listOf("d"),
            queue.upNext.map { it.title },
            "the tracks already heard must not come round again in this pass",
        )

        val back = queue.skipPrevious()
        assertIs<QueueOutcome.Play>(back)
        assertEquals("b", back.song.title)
    }

    @Test
    fun `assuming a position at the top of the list plays nothing off as heard`() {
        val queue = PlaybackQueue(Random(1))
        queue.setSongs(songs("a", "b", "c"))

        queue.assumePlayedUpTo("/music/a.mp3")

        assertTrue(queue.playedInPass.isEmpty())
        assertTrue(queue.history.isEmpty())
        assertEquals(listOf("b", "c"), queue.upNext.map { it.title })
    }
}
