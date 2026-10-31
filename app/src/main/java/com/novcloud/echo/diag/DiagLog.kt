package com.novcloud.echo.diag

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Two-file rolling log: lines go to [current] until it passes [maxBytes], then it replaces [previous]. The pair never
 * holds much more than twice [maxBytes].
 */
class RollingLog(private val dir: File, private val maxBytes: Long, private val name: String = "echo") {
    private val current get() = File(dir, "$name.log")
    private val previous get() = File(dir, "$name.1.log")

    @Synchronized
    fun append(line: String) {
        runCatching {
            dir.mkdirs()
            val f = current
            if (f.length() > maxBytes) {
                previous.delete()
                f.renameTo(previous)
            }
            current.appendText(line.trimEnd('\n') + "\n")
        }
    }

    /** Oldest first. */
    @Synchronized
    fun copyTo(out: OutputStream) {
        listOf(previous, current).filter { it.exists() }.forEach { f -> f.inputStream().use { it.copyTo(out) } }
    }

    @Synchronized
    fun size(): Long = previous.length() + current.length()

    @Synchronized
    fun clear() {
        previous.delete()
        current.delete()
    }
}

/** In-app diagnostics: playback watchdog events, scan timings, errors and crashes, kept on the device until exported. */
object DiagLog {
    const val MAX_BYTES = 256L * 1024

    private var log: RollingLog? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "echo-diag").apply { isDaemon = true } }
    private val time = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun init(context: Context) {
        val l = RollingLog(File(context.filesDir, "logs"), MAX_BYTES)
        log = l
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val marker = File(context.filesDir, "logs/crashed")
        if (marker.exists()) {
            marker.delete()
            l.append(line('W', "Crash", "previous launch ended in a crash (stack trace above)"))
        }
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { if (Thread.currentThread().name != "echo-diag") writer.submit {}.get(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
            l.append(line('F', "Crash", "uncaught on ${t.name}\n${stack(e)}"))
            runCatching { marker.parentFile?.mkdirs(); marker.createNewFile() }
            previous?.uncaughtException(t, e)
        }
    }

    fun i(tag: String, msg: String) = write('I', tag, msg, null)
    fun w(tag: String, msg: String, e: Throwable? = null) = write('W', tag, msg, e)
    fun e(tag: String, msg: String, e: Throwable? = null) = write('E', tag, msg, e)

    private fun write(level: Char, tag: String, msg: String, e: Throwable?) {
        runCatching {
            when (level) {
                'I' -> Log.i(tag, msg)
                'W' -> Log.w(tag, msg, e)
                else -> Log.e(tag, msg, e)
            }
        }
        val l = log ?: return
        val text = if (e == null) msg else "$msg\n${stack(e)}"
        val stamped = line(level, tag, text)
        writer.execute { l.append(stamped) }
    }

    private fun line(level: Char, tag: String, msg: String) = "${time.get()!!.format(Date())} $level/$tag: $msg"

    private fun stack(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString().trimEnd()

    fun export(out: OutputStream, header: String) {
        val l = log
        writer.submit {}.get()
        out.write(header.toByteArray())
        out.write("\n\n".toByteArray())
        l?.copyTo(out)
        out.flush()
    }

    fun device(): String = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}
