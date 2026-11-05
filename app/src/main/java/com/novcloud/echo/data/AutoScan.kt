package com.novcloud.echo.data

/**
 * What a rescan changed, by song key. [retagged] songs kept their file but show different tags; [rewritten] songs
 * have a new size or modification time with the same tags (embedded lyrics or artwork edited, audio replaced).
 * A modification time appearing where the old record had none (a library saved before it was recorded) is not a change.
 */
class LibraryDiff(
    val added: List<Song>,
    val removed: List<Song>,
    val retagged: List<Song>,
    val rewritten: List<Song>,
) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && retagged.isEmpty() && rewritten.isEmpty()

    /** Songs whose cached per-file data (lyrics, artwork) is stale, by id; removed songs included. */
    val staleIds: Set<Long> get() = (retagged + rewritten + removed).mapTo(HashSet()) { it.id }

    fun describe(): String = "+${added.size} -${removed.size} ~${retagged.size} retagged ~${rewritten.size} rewritten"

    companion object {
        fun of(old: List<Song>, new: List<Song>): LibraryDiff {
            val before = old.associateBy { it.key }
            val after = new.associateBy { it.key }
            val added = new.filter { it.key !in before }
            val removed = old.filter { it.key !in after }
            val retagged = ArrayList<Song>()
            val rewritten = ArrayList<Song>()
            for (s in new) {
                val o = before[s.key] ?: continue
                if (o == s) continue
                when {
                    tagsDiffer(o, s) -> retagged += s
                    o.size != s.size || (o.modified != 0L && o.modified != s.modified) -> rewritten += s
                }
            }
            return LibraryDiff(added, removed, retagged, rewritten)
        }

        private fun tagsDiffer(a: Song, b: Song) = a.title != b.title || a.artist != b.artist || a.album != b.album ||
            a.albumArtist != b.albumArtist || a.genre != b.genre || a.track != b.track || a.disc != b.disc ||
            a.year != b.year || a.durationMs != b.durationMs || a.mimeType != b.mimeType
    }
}

/**
 * When the library looks for new or changed files on its own.
 *
 * MediaStore changes arrive through a ContentObserver; a copy of an album fires dozens of notifications, so a scan
 * waits for [quietMs] without new ones but never longer than [maxWaitMs] after the first. While the app is in the
 * background a change only marks the library dirty and the scan runs on the next return to the foreground, so the
 * observer never keeps the device busy.
 *
 * SAF folders have no observer. On start and on return to the foreground (at most every [checkEveryMs]) their cheap
 * shallow signature is compared; a deep walk (listing every folder, re-reading tags only of files whose size or
 * modification time changed) runs when the signature changed or [deepEveryMs] passed since the last one, which
 * also catches files edited deep inside a tree.
 */
class AutoScanPolicy(
    val quietMs: Long = 2_500,
    val maxWaitMs: Long = 15_000,
    val checkEveryMs: Long = 15 * 60_000L,
    val deepEveryMs: Long = 6 * 3_600_000L,
) {
    private var firstChange = -1L

    /** A MediaStore change at [now]: how long to wait before scanning (each new change restarts the wait). */
    fun onChange(now: Long): Long {
        if (firstChange < 0) firstChange = now
        return minOf(quietMs, (firstChange + maxWaitMs - now).coerceAtLeast(0))
    }

    /** The scan for the pending changes started. */
    fun onScan() {
        firstChange = -1
    }

    fun checkDue(now: Long, lastCheck: Long): Boolean = lastCheck <= 0 || now - lastCheck >= checkEveryMs || now < lastCheck

    /**
     * Whether a scan walks the SAF trees. [mode]: [Mode.Never] reuses the last walk (MediaStore change),
     * [Mode.IfChanged] walks on a changed signature or an overdue deep walk, [Mode.Always] walks.
     */
    fun walkTrees(mode: Mode, signatureChanged: Boolean, now: Long, lastDeepWalk: Long): Boolean = when (mode) {
        Mode.Always -> true
        Mode.Never -> false
        Mode.IfChanged -> signatureChanged || lastDeepWalk <= 0 || now - lastDeepWalk >= deepEveryMs || now < lastDeepWalk
    }

    enum class Mode { Never, IfChanged, Always }
}
