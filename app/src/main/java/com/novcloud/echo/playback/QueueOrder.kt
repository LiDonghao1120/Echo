package com.novcloud.echo.playback

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * Shuffle order that keeps the queue the user sees. Media3's DefaultShuffleOrder drops inserted items at random
 * places in the shuffled sequence and ignores moves, so in shuffle mode "play next" landed anywhere and dragging
 * an item did not change what plays.
 *
 * Inserted items follow, in play order, the item just before them in the playlist: [PlayerConnection.playNext]
 * inserts after the current item, so the new songs play right after it, even when the current item is the last
 * one in the playlist. "Add to queue" appends, which [QueuePlayer] tells apart and moves to the end of the play
 * order. Only an insert into an empty playlist (a new queue) is shuffled, and [fresh] marks it so the service can
 * put the current item first.
 */
class QueueOrder private constructor(
    private val order: IntArray,
    private val random: Random,
    val fresh: Boolean,
) : ShuffleOrder {
    private val positions = IntArray(order.size).also { p -> order.forEachIndexed { i, v -> p[v] = i } }

    constructor(length: Int = 0, seed: Long = System.nanoTime()) : this(shuffled(length, Random(seed)), Random(seed), fresh = true)

    /** The play order as playlist indices. */
    fun toList(): List<Int> = order.toList()

    override fun getLength(): Int = order.size
    override fun getNextIndex(index: Int): Int = positions[index].let { if (it + 1 < order.size) order[it + 1] else C.INDEX_UNSET }
    override fun getPreviousIndex(index: Int): Int = positions[index].let { if (it > 0) order[it - 1] else C.INDEX_UNSET }
    override fun getLastIndex(): Int = if (order.isEmpty()) C.INDEX_UNSET else order[order.size - 1]
    override fun getFirstIndex(): Int = if (order.isEmpty()) C.INDEX_UNSET else order[0]

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        if (insertionCount <= 0) return this
        if (order.isEmpty()) return QueueOrder(shuffled(insertionCount, random), random, fresh = true)
        val shifted = IntArray(order.size) { i -> order[i].let { if (it >= insertionIndex) it + insertionCount else it } }
        val inserted = IntArray(insertionCount) { insertionIndex + it }
        val at = if (insertionIndex <= 0) 0 else positions[minOf(insertionIndex, order.size) - 1] + 1
        val out = IntArray(shifted.size + insertionCount)
        System.arraycopy(shifted, 0, out, 0, at)
        System.arraycopy(inserted, 0, out, at, insertionCount)
        System.arraycopy(shifted, at, out, at + insertionCount, shifted.size - at)
        return QueueOrder(out, random, fresh = false)
    }

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val count = indexToExclusive - indexFrom
        if (count <= 0) return this
        val out = IntArray(order.size - count)
        var j = 0
        for (v in order) {
            if (v in indexFrom until indexToExclusive) continue
            out[j++] = if (v >= indexToExclusive) v - count else v
        }
        return QueueOrder(out, random, fresh = false)
    }

    override fun cloneAndClear(): ShuffleOrder = QueueOrder(IntArray(0), random, fresh = false)

    /** A new shuffle of the same playlist with [first] at the front (shuffle turned on, or a new shuffled queue). */
    fun reshuffledFrom(first: Int): QueueOrder = startingWith(order.size, first, random)

    /** Same order with playlist item [index] moved to play position [to] (0 = first in play order). */
    fun moved(index: Int, to: Int): QueueOrder {
        val list = order.toMutableList()
        val from = positions[index]
        list.removeAt(from)
        list.add(to.coerceIn(0, list.size), index)
        return QueueOrder(list.toIntArray(), random, fresh = false)
    }

    /** Same order with playlist items [indices] moved, in playlist order, to the end of the play order. */
    fun movedToEnd(indices: IntRange): QueueOrder {
        val rest = order.filter { it !in indices }
        return QueueOrder((rest + indices.filter { it in positions.indices }).toIntArray(), random, fresh = false)
    }

    /** Play position of playlist item [index]. */
    fun positionOf(index: Int): Int = positions[index]

    companion object {
        fun of(order: List<Int>, seed: Long = System.nanoTime()): QueueOrder? {
            if (order.sorted() != order.indices.toList()) return null
            return QueueOrder(order.toIntArray(), Random(seed), fresh = false)
        }

        fun startingWith(length: Int, first: Int, random: Random = Random.Default): QueueOrder {
            val rest = (0 until length).filter { it != first }.shuffled(random)
            val order = if (first in 0 until length) intArrayOf(first) + rest.toIntArray() else rest.toIntArray()
            return QueueOrder(order, random, fresh = false)
        }

        private fun shuffled(n: Int, random: Random) = IntArray(n) { it }.also { a ->
            for (i in a.size - 1 downTo 1) {
                val j = random.nextInt(i + 1)
                val t = a[i]; a[i] = a[j]; a[j] = t
            }
        }

        /**
         * Saved play order (indices into the saved key list) mapped onto the restored playlist, where songs that no
         * longer resolve were dropped. [kept] holds, per saved index, its new index or -1.
         */
        fun remap(saved: List<Int>, kept: IntArray): List<Int>? {
            if (saved.sorted() != kept.indices.toList()) return null
            val out = saved.map { kept[it] }.filter { it >= 0 }
            return out.takeIf { it.sorted() == it.indices.toList() }
        }
    }
}

/** Session command for a queue move in shuffle mode, where only the service can change the play order. */
object QueueMove {
    const val ACTION = "com.novcloud.echo.QUEUE_MOVE"
    const val ARG_FROM = "from"
    const val ARG_TO = "to"
}

/**
 * The player the media session drives. A controller's "add to queue" arrives as an append without an index, which
 * the shuffle order alone cannot tell from "play next" after the last item; [onAppended] lets the service put
 * appended songs at the end of the play order.
 */
class QueuePlayer(player: Player, private val onAppended: (start: Int, count: Int) -> Unit) : ForwardingPlayer(player) {
    override fun addMediaItem(mediaItem: MediaItem) {
        val start = mediaItemCount
        super.addMediaItem(mediaItem)
        if (start > 0) onAppended(start, 1)
    }

    override fun addMediaItems(mediaItems: List<MediaItem>) {
        val start = mediaItemCount
        super.addMediaItems(mediaItems)
        if (start > 0 && mediaItems.isNotEmpty()) onAppended(start, mediaItems.size)
    }
}
