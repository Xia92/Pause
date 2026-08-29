package io.github.xia92.pause

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.util.Log
import java.util.Locale

const val PAUSE_LOCALE_TAG = "PauseLocale"

fun Context.localizedForPause(
    languagePreference: PauseLanguagePreference
): Context {
    val locale = languagePreference.toLocale() ?: return this
    val configuration = Configuration(resources.configuration)
    configuration.setLocales(LocaleList(locale))
    return createConfigurationContext(configuration)
}

fun Context.isDarkThemeForPause(themePreference: PauseThemePreference): Boolean {
    return when (themePreference) {
        PauseThemePreference.FOLLOW_SYSTEM ->
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        PauseThemePreference.LIGHT -> false
        PauseThemePreference.DARK -> true
    }
}

fun Context.applyPauseAppLocalePreference(
    languagePreference: PauseLanguagePreference,
    source: String,
    explicitUserAction: Boolean = false
): Boolean {
    val requestedLocale = languagePreference.toLanguageTags()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Log.i(
            PAUSE_LOCALE_TAG,
            "Locale application skipped; system app locales require Android 13+: " +
                "source=$source preference=${languagePreference.storageValue} " +
                "requestedLocale=$requestedLocale"
        )
        return false
    }

    val localeManager = getSystemService(LocaleManager::class.java)
    val currentLocale = localeManager.applicationLocales.toLanguageTags()
    Log.i(
        PAUSE_LOCALE_TAG,
        "Locale application requested: source=$source " +
            "explicitUserAction=$explicitUserAction " +
            "preference=${languagePreference.storageValue} " +
            "requestedLocale=$requestedLocale currentLocale=$currentLocale"
    )

    if (currentLocale == requestedLocale) {
        Log.i(
            PAUSE_LOCALE_TAG,
            "Locale application skipped because it is already correct: " +
                "requestedLocale=$requestedLocale currentLocale=$currentLocale"
        )
        return false
    }

    localeManager.applicationLocales = languagePreference.toLocaleList()
    Log.i(
        PAUSE_LOCALE_TAG,
        "Locale changed: source=$source explicitUserAction=$explicitUserAction " +
            "requestedLocale=$requestedLocale previousLocale=$currentLocale"
    )
    return true
}

private fun PauseLanguagePreference.toLocale(): Locale? {
    return when (this) {
        PauseLanguagePreference.FOLLOW_SYSTEM -> null
        PauseLanguagePreference.ENGLISH -> Locale.ENGLISH
        PauseLanguagePreference.SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
    }
}

private fun PauseLanguagePreference.toLanguageTags(): String {
    return toLocaleList().toLanguageTags()
}

private fun PauseLanguagePreference.toLocaleList(): LocaleList {
    val locale = toLocale()
    return if (locale == null) {
        LocaleList.getEmptyLocaleList()
    } else {
        LocaleList(locale)
    }
}
