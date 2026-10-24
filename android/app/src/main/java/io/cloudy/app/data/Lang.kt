package io.cloudy.app.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's language. Android 13+ keeps it as the per-app language (also shown in system Settings →
 * Apps → Cloudy → Language); older versions store it here and wrap each context ([wrap]).
 * The account has a language too (shared with the web app and emails); [followAccount] applies
 * changes made there, and choosing a language here saves it to the account.
 */
object Lang {
    /** Same list as res/xml/locales_config.xml and the server's LANGUAGES. */
    val CODES = listOf("en", "ar", "de", "fr", "es")
    /** Each language in its own name, for the picker. */
    val NAMES = mapOf("en" to "English", "ar" to "العربية", "de" to "Deutsch", "fr" to "Français", "es" to "Español")

    private fun prefs(context: Context) = context.getSharedPreferences("lang", Context.MODE_PRIVATE)

    /** The chosen language code, or null for the phone's language. */
    fun current(context: Context): String? =
        if (Build.VERSION.SDK_INT >= 33) {
            val list = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (list.isEmpty) null else list[0].language.takeIf { it in CODES }
        } else prefs(context).getString("code", null)

    /** Switches to [code] (null = the phone's language). Android 13+ recreates the screen itself. */
    fun apply(activity: Activity, code: String?) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (code == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(code)
        } else {
            prefs(activity).edit().apply { if (code == null) remove("code") else putString("code", code) }.apply()
            activity.recreate()
        }
    }

    /** Below Android 13: the context to use in attachBaseContext, with the chosen language's resources. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val code = current(base)
        val locale = if (code == null) Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(code)
        Locale.setDefault(locale) // date and month names (java.time) follow it too
        if (code == null) return base
        val config = Configuration(base.resources.configuration).apply { setLocale(locale); setLayoutDirection(locale) }
        return base.createConfigurationContext(config)
    }

    /**
     * Applies the account's language when it changed since this phone last saw it (set on the web, or
     * first sign-in). An account without a language leaves the phone's choice alone.
     */
    fun followAccount(activity: Activity, userId: String, account: String?) {
        val key = "account:$userId"
        val p = prefs(activity)
        if (p.contains(key) && p.getString(key, null) == account) return
        p.edit().putString(key, account).apply()
        if (account != null && account in CODES && account != current(activity)) apply(activity, account)
    }

    /** Remembers that the account now has [code], so [followAccount] doesn't switch back. */
    fun savedToAccount(context: Context, userId: String, code: String?) {
        prefs(context).edit().putString("account:$userId", code).apply()
    }
}
