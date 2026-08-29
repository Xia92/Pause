package io.github.xia92.pause

const val DEFAULT_FINAL_WARNING_SECONDS = 20

val FINAL_WARNING_SECONDS_CHOICES: List<Int> = listOf(10, 20, 30)

enum class PauseLanguagePreference(val storageValue: String) {
    FOLLOW_SYSTEM("system"),
    ENGLISH("en"),
    SIMPLIFIED_CHINESE("zh-rCN");

    companion object {
        fun fromStorageValue(storageValue: String?): PauseLanguagePreference {
            return entries.firstOrNull { preference ->
                preference.storageValue == storageValue
            } ?: FOLLOW_SYSTEM
        }
    }
}

enum class PauseThemePreference(val storageValue: String) {
    FOLLOW_SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorageValue(storageValue: String?): PauseThemePreference {
            return entries.firstOrNull { preference ->
                preference.storageValue == storageValue
            } ?: FOLLOW_SYSTEM
        }
    }
}

fun normalizeFinalWarningSeconds(seconds: Int?): Int {
    return if (seconds != null && seconds in FINAL_WARNING_SECONDS_CHOICES) {
        seconds
    } else {
        DEFAULT_FINAL_WARNING_SECONDS
    }
}

fun finalWarningDurationMillis(warningSeconds: Int): Long {
    return normalizeFinalWarningSeconds(warningSeconds) * 1_000L
}

fun defaultWarningVibrationEnabled(savedValue: Boolean?): Boolean {
    return savedValue ?: true
}
