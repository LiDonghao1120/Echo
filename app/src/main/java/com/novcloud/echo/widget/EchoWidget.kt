package com.novcloud.echo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.novcloud.echo.MainActivity
import com.novcloud.echo.R
import com.novcloud.echo.diag.DiagLog
import com.novcloud.echo.playback.PlaybackService
import java.io.File

/** What the widget shows; persisted so it survives the process and a reboot (then always paused). */
data class WidgetState(val title: String = "", val artist: String = "", val playing: Boolean = false, val coverVersion: Long = 0) {
    val hasTrack get() = title.isNotEmpty()
}

class EchoWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = Widgets.push(context, ids)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        Widgets.push(context, intArrayOf(id))
}

/** Buttons on the widget. A live service with a queue is driven directly; otherwise the service is started like a media button press, which resumes the saved queue. */
class WidgetActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (PlaybackService.onWidget(action)) return
        PlaybackService.pendingSkip = when (action) {
            ACTION_NEXT -> 1
            ACTION_PREV -> -1
            else -> 0
        }
        val press = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setClass(context, PlaybackService::class.java)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
        try {
            ContextCompat.startForegroundService(context, press)
        } catch (e: Exception) {
            DiagLog.w("EchoWidget", "could not start playback from the widget", e)
            PlaybackService.pendingSkip = 0
            runCatching { context.startActivity(Widgets.openIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.novcloud.echo.widget.TOGGLE"
        const val ACTION_NEXT = "com.novcloud.echo.widget.NEXT"
        const val ACTION_PREV = "com.novcloud.echo.widget.PREV"
    }
}

object Widgets {
    private const val PREFS = "widget"
    private const val COVER_PX = 256

    private fun coverFile(context: Context) = File(context.filesDir, "widget/cover.jpg")

    fun load(context: Context): WidgetState {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return WidgetState(p.getString("title", "").orEmpty(), p.getString("artist", "").orEmpty(), p.getBoolean("playing", false), p.getLong("cover", 0))
    }

    /** Saves the state (and the cover when it changed: null keeps the old one, [clearCover] removes it) and redraws every widget. */
    fun publish(context: Context, state: WidgetState, cover: Bitmap? = null, clearCover: Boolean = false) {
        var s = state
        val f = coverFile(context)
        if (cover != null) {
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, "cover.tmp")
                tmp.outputStream().use { scaled(cover).compress(Bitmap.CompressFormat.JPEG, 92, it) }
                tmp.renameTo(f)
                s = s.copy(coverVersion = System.currentTimeMillis())
            }
        } else if (clearCover) {
            f.delete()
            s = s.copy(coverVersion = 0)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("title", s.title).putString("artist", s.artist).putBoolean("playing", s.playing).putLong("cover", s.coverVersion)
            .apply()
        push(context, null, s)
    }

    fun push(context: Context, ids: IntArray?, state: WidgetState = load(context)) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val all = ids ?: runCatching { manager.getAppWidgetIds(ComponentName(context, EchoWidget::class.java)) }.getOrNull() ?: return
        if (all.isEmpty()) return
        val cover = if (state.coverVersion > 0) runCatching { BitmapFactory.decodeFile(coverFile(context).path) }.getOrNull() else null
        for (id in all) {
            val views = if (Build.VERSION.SDK_INT >= 31) {
                RemoteViews(mapOf(SizeF(180f, 40f) to views(context, R.layout.widget_small, state, cover), SizeF(180f, 104f) to views(context, R.layout.widget_large, state, cover)))
            } else {
                val minHeight = manager.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) ?: 0
                views(context, if (minHeight >= 104) R.layout.widget_large else R.layout.widget_small, state, cover)
            }
            runCatching { manager.updateAppWidget(id, views) }.onFailure { DiagLog.w("EchoWidget", "update failed", it) }
        }
    }

    fun views(context: Context, layout: Int, state: WidgetState, cover: Bitmap?): RemoteViews {
        val v = RemoteViews(context.packageName, layout)
        if (state.hasTrack) {
            v.setTextViewText(R.id.widget_title, state.title)
            v.setTextViewText(R.id.widget_artist, state.artist)
        } else {
            v.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
            v.setTextViewText(R.id.widget_artist, context.getString(R.string.widget_idle))
        }
        if (cover != null) v.setImageViewBitmap(R.id.widget_cover, cover) else v.setImageViewResource(R.id.widget_cover, R.drawable.ic_logo)
        v.setImageViewResource(R.id.widget_toggle, if (state.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        v.setContentDescription(R.id.widget_toggle, context.getString(if (state.playing) R.string.widget_pause else R.string.widget_play))
        if (layout == R.layout.widget_large) {
            v.setTextViewText(R.id.widget_state, context.getString(if (state.playing) R.string.widget_playing else R.string.widget_paused))
            v.setViewVisibility(R.id.widget_state_dot, if (state.playing) android.view.View.VISIBLE else android.view.View.INVISIBLE)
        }
        v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, openIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        v.setOnClickPendingIntent(R.id.widget_toggle, action(context, WidgetActions.ACTION_TOGGLE, 1))
        v.setOnClickPendingIntent(R.id.widget_prev, action(context, WidgetActions.ACTION_PREV, 2))
        v.setOnClickPendingIntent(R.id.widget_next, action(context, WidgetActions.ACTION_NEXT, 3))
        return v
    }

    fun openIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun action(context: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        context, code, Intent(context, WidgetActions::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun scaled(b: Bitmap): Bitmap {
        val side = minOf(b.width, b.height)
        val square = if (b.width == b.height) b else Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        return if (side <= COVER_PX) square else Bitmap.createScaledBitmap(square, COVER_PX, COVER_PX, true)
    }
}
