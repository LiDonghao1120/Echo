package com.novcloud.echo.diag

import android.content.Context

/**
 * Startup work that must never take the app down. A step's state is written synchronously before and after it runs,
 * so a launch that died inside a step (a crash the try/catch could not see, or a native kill) is noticed next time
 * and the step is skipped instead of crashing again.
 */
class StartupSteps(private val store: Store) {
    interface Store {
        fun get(name: String): String?
        fun put(name: String, value: String?)
    }

    /** One-time migration: runs until it finishes or fails once, then never again. */
    suspend fun once(name: String, block: suspend () -> Unit) {
        when (store.get(name)) {
            DONE, FAILED -> return
            RUNNING -> {
                store.put(name, FAILED)
                DiagLog.w(TAG, "$name did not finish last launch; skipped from now on")
                return
            }
        }
        store.put(name, RUNNING)
        val ok = runCatching { block() }.onFailure { DiagLog.e(TAG, "$name failed; skipped from now on", it) }.isSuccess
        store.put(name, if (ok) DONE else FAILED)
    }

    /**
     * Work done on every launch. Returns [block]'s result, or null when it threw. When the previous launch died inside
     * it, [recover] runs first (for example discarding the cache the step reads) and the step then runs as usual.
     */
    suspend fun <T> every(name: String, recover: () -> Unit, block: suspend () -> T): T? {
        if (store.get(name) == RUNNING) {
            DiagLog.w(TAG, "$name did not finish last launch; recovering")
            runCatching(recover).onFailure { DiagLog.e(TAG, "$name recovery failed", it) }
        }
        store.put(name, RUNNING)
        val r = runCatching { block() }.onFailure { DiagLog.e(TAG, "$name failed", it) }
        store.put(name, null)
        return r.getOrNull()
    }

    companion object {
        private const val TAG = "Startup"
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"

        fun of(context: Context): StartupSteps {
            val sp = context.getSharedPreferences("startup", Context.MODE_PRIVATE)
            return StartupSteps(object : Store {
                override fun get(name: String) = sp.getString(name, null)
                override fun put(name: String, value: String?) {
                    sp.edit().apply { if (value == null) remove(name) else putString(name, value) }.commit()
                }
            })
        }
    }
}
