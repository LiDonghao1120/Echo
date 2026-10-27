package com.novcloud.echo

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

enum class AppLanguage(val tag: String) { System(""), Chinese("zh-CN"), English("en") }

/**
 * Per-app language. Android 13+ keeps the choice in the system (LocaleManager, also editable from system settings via
 * locales_config); older versions keep it here and apply it to the app's resources and to each activity.
 */
object Languages {
    private const val PREFS = "language"
    private const val KEY = "tag"

    fun current(context: Context): AppLanguage = from(storedTag(context))

    fun set(activity: Activity, language: AppLanguage) {
        if (current(activity) == language) return
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = activity.getSystemService(LocaleManager::class.java)
            lm.applicationLocales = if (language.tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).commit()
            applyLegacy(activity.applicationContext)
            (activity.applicationContext as? EchoApp)?.onLanguageChanged()
            activity.recreate()
        }
    }

    /** Below Android 13: the override an activity applies in attachBaseContext; null to follow the system. */
    fun override(context: Context): Configuration? {
        if (Build.VERSION.SDK_INT >= 33) return null
        val locale = locale(current(context)) ?: return null
        return Configuration().apply {
            fontScale = 0f
            setLocales(LocaleList(locale))
        }
    }

    /** Below Android 13: points the shared app resources (services, widget, receivers) and the default locale at the choice. */
    @Suppress("DEPRECATION")
    fun applyLegacy(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        val locale = locale(current(context)) ?: Resources.getSystem().configuration.locales[0]
        Locale.setDefault(locale)
        val res = context.resources
        if (res.configuration.locales[0] == locale) return
        val config = Configuration(res.configuration).apply { setLocales(LocaleList(locale)) }
        res.updateConfiguration(config, res.displayMetrics)
    }

    private fun storedTag(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags() }.getOrDefault("")
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        }

    private fun from(tag: String): AppLanguage {
        val first = tag.substringBefore(',').lowercase()
        return when {
            first.startsWith("zh") -> AppLanguage.Chinese
            first.startsWith("en") -> AppLanguage.English
            else -> AppLanguage.System
        }
    }

    private fun locale(language: AppLanguage): Locale? = language.tag.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag)
}
