package com.novcloud.echo.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * A block above a pinned bar that folds away before the list under it scrolls, and opens again only once the list is
 * back at its first row (exit until collapsed). It is pure state: the screen lays the block out at [height] minus
 * [folded], so the pinned bar and the list always sit in normal layout below it.
 */
@Stable
class CollapsingHeader(initialFolded: Float = 0f) {
    /** The block's full height in px, as last measured. */
    var height by mutableIntStateOf(0)

    /** How much of it is folded away, in px (0 = open). */
    var folded by mutableFloatStateOf(initialFolded)
        private set

    /** 0 open … 1 folded; drives the top bar's title. */
    val progress: Float get() = if (height <= 0) 0f else (folded / height).coerceIn(0f, 1f)

    private fun clampTo(v: Float) = v.coerceIn(0f, height.coerceAtLeast(0).toFloat())

    /** Folds by up to [dy] (< 0 = content moving up) and returns what it used, same sign. */
    internal fun consume(dy: Float): Float {
        val before = folded
        folded = clampTo(folded - dy)
        return before - folded
    }

    fun fold() {
        folded = height.toFloat()
    }

    suspend fun unfold() {
        if (folded <= 0f) return
        animate(folded, 0f, animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> folded = clampTo(v) }
    }

    fun connection(): NestedScrollConnection = object : NestedScrollConnection {
        // Content moving up: the block folds first.
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0f) Offset(0f, consume(available.y)) else Offset.Zero

        // Content moving down: the list scrolls back first; what is left over opens the block.
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (available.y > 0f) Offset(0f, consume(available.y)) else Offset.Zero
    }

    /** A drag on the block or the pinned bar itself, shared with [grid] in the same order as a drag on the list. */
    fun drag(dy: Float, grid: LazyGridState): Float {
        return if (dy < 0f) {
            val used = consume(dy)
            used - grid.dispatchRawDelta(-(dy - used))
        } else {
            val back = -grid.dispatchRawDelta(-dy)
            back + consume(dy - back)
        }
    }

    companion object {
        val Saver: Saver<CollapsingHeader, Float> = Saver(save = { it.folded }, restore = { CollapsingHeader(it) })
    }
}
