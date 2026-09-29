package com.rhp.mediaplayer.coverart

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.rhp.mediaplayer.metadata.MetadataReader
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * Cover art backed by ffmpeg, decoded and downscaled off the UI thread.
 *
 * Two policies here exist because of how much a single lookup costs (a decoder
 * process, roughly 100-200 ms):
 *
 *  * **The queue is bounded, and dropped requests are forgettable.** Scrolling
 *    quickly through a large library composes far more rows than it settles on,
 *    which would otherwise queue up thousands of extractions. When the queue is
 *    full the oldest entry is discarded *and forgotten*, so the row can ask
 *    again next time it is scrolled into view instead of being stuck on a
 *    placeholder forever.
 *  * **Art is downscaled once, on load.** A 1500x1500 sleeve is decoded and
 *    reduced to a thumbnail before it ever reaches the GPU; keeping full-size
 *    art for a list of rows would cost hundreds of megabytes.
 */
class DesktopCoverArtRepository(
    private val reader: MetadataReader,
    private val thumbnailPx: Int = 96,
    // Large enough to fill the sleeve on the now-playing screen at desktop
    // window sizes, small enough that one bitmap is under a megabyte.
    private val largePx: Int = 640,
    maxEntries: Int = 320,
    maxPending: Int = 48,
    workers: Int = 3,
) : CoverArtRepository {

    private val thumbnails = mutableStateMapOf<String, ImageBitmap>()

    // Insertion-ordered and used purely to bound the cache.
    private val order = ConcurrentLinkedDeque<String>()
    private val capacity = maxEntries

    private val pending = ConcurrentLinkedDeque<String>()
    private val pendingPaths = ConcurrentHashMap<String, String>()
    private val known = ConcurrentHashMap.newKeySet<String>()
    private val noArt = ConcurrentHashMap.newKeySet<String>()
    private val pendingLimit = maxPending

    private val workers = Executors.newFixedThreadPool(workers) { runnable ->
        Thread(runnable, "cover-art-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
    }

    override var largeArt: ImageBitmap? by mutableStateOf(null)
        private set

    private var largeRequestedId: String? = null

    override fun get(songId: String): ImageBitmap? = thumbnails[songId]

    override fun request(songId: String, path: String) {
        if (thumbnails.containsKey(songId)) return
        if (noArt.contains(songId)) return
        if (!known.add(songId)) return

        while (pending.size >= pendingLimit) {
            val evicted = pending.pollFirst() ?: break
            pendingPaths.remove(evicted)
            // Forget it so the row can request again when it scrolls back.
            known.remove(evicted)
        }

        pending.addLast(songId)
        pendingPaths[songId] = path
        workers.execute { loadThumbnail() }
    }

    override fun requestLarge(songId: String, path: String) {
        if (largeRequestedId == songId && largeArt != null) return
        largeRequestedId = songId
        largeArt = null
        workers.execute {
            val bitmap = load(path, largePx)
            if (largeRequestedId == songId) {
                largeArt = bitmap
            }
        }
    }

    override fun clear() {
        thumbnails.clear()
        order.clear()
        pending.clear()
        pendingPaths.clear()
        known.clear()
        noArt.clear()
        largeRequestedId = null
        largeArt = null
    }

    private fun loadThumbnail() {
        val songId = pending.pollFirst() ?: return
        val path = pendingPaths.remove(songId) ?: run {
            known.remove(songId)
            return
        }

        val bitmap = if (noArt.contains(songId)) null else load(path, thumbnailPx)

        if (bitmap == null) {
            // Remember the miss so the same file is not decoded again, and let
            // the row keep its placeholder.
            noArt.add(songId)
            known.remove(songId)
            return
        }

        thumbnails[songId] = bitmap
        order.addLast(songId)
        trim()
    }

    private fun trim() {
        while (order.size > capacity) {
            val evicted = order.pollFirst() ?: return
            thumbnails.remove(evicted)
            // The thumbnail is gone, so allow a future request to rebuild it.
            known.remove(evicted)
        }
    }

    private fun load(path: String, targetPx: Int): ImageBitmap? = try {
        val bytes = reader.readCoverArt(path)
        if (bytes == null) {
            null
        } else {
            val source = ImageIO.read(bytes.inputStream())
            if (source == null) null else scale(source, targetPx).toComposeImageBitmap()
        }
    } catch (_: Exception) {
        null
    }

    /** Keeps the aspect ratio, so non-square sleeves are not distorted. */
    private fun scale(source: BufferedImage, targetPx: Int): BufferedImage {
        val longest = maxOf(source.width, source.height)
        if (longest <= targetPx) return source

        val ratio = targetPx.toDouble() / longest
        val width = (source.width * ratio).toInt().coerceAtLeast(1)
        val height = (source.height * ratio).toInt().coerceAtLeast(1)

        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    /** Stops the worker threads. Called when the app is closing. */
    fun shutdown(timeoutMs: Long = 2000) {
        workers.shutdownNow()
        workers.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private companion object {
        val threadCounter = AtomicInteger()
    }
}
