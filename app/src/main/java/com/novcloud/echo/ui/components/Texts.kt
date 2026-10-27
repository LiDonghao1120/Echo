package com.novcloud.echo.ui.components

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.novcloud.echo.R
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor
import java.util.Locale

val Resources.locale: Locale get() = configuration.locales[0]

fun Resources.number(n: Long): String = NumberFormat.getIntegerInstance(locale).format(n)

/** A quantity string whose only argument is the count, formatted for the locale. */
fun Resources.count(@PluralsRes id: Int, n: Int): String = getQuantityString(id, n, number(n.toLong()))

fun Resources.longDuration(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) getString(R.string.duration_hours_minutes, number(m / 60), number(m % 60)) else getString(R.string.duration_minutes, number(m))
}

fun Resources.formatDate(date: TemporalAccessor, @StringRes pattern: Int): String =
    DateTimeFormatter.ofPattern(getString(pattern), locale).format(date)

@Composable
private fun resources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

@Composable
fun count(@PluralsRes id: Int, n: Int): String = resources().count(id, n)

@Composable
fun number(n: Int): String = resources().number(n.toLong())

@Composable
fun formatDate(date: TemporalAccessor, @StringRes pattern: Int): String {
    val res = resources()
    val p = stringResource(pattern)
    return remember(date, p, res.locale) { DateTimeFormatter.ofPattern(p, res.locale).format(date) }
}

@Composable
fun rememberNumberFormat(): (Long) -> String {
    val locale = resources().locale
    return remember(locale) { val f = NumberFormat.getIntegerInstance(locale); { n: Long -> f.format(n) } }
}
