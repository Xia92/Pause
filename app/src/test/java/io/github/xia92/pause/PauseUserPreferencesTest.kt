package io.github.xia92.pause

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseUserPreferencesTest {
    @Test
    fun defaultWarningDurationIsTwentySeconds() {
        assertEquals(20, DEFAULT_FINAL_WARNING_SECONDS)
        assertEquals(20, normalizeFinalWarningSeconds(null))
        assertEquals(20_000L, DEFAULT_FINAL_WARNING_DURATION_MILLIS)
        assertEquals(20_000L, finalWarningDurationMillis(DEFAULT_FINAL_WARNING_SECONDS))
    }

    @Test
    fun warningDurationOnlyAllowsSupportedChoices() {
        assertEquals(10, normalizeFinalWarningSeconds(10))
        assertEquals(20, normalizeFinalWarningSeconds(20))
        assertEquals(30, normalizeFinalWarningSeconds(30))
        assertEquals(20, normalizeFinalWarningSeconds(15))
        assertEquals(20, normalizeFinalWarningSeconds(0))
    }

    @Test
    fun vibrationPreferenceDefaultsOn() {
        assertTrue(defaultWarningVibrationEnabled(null))
        assertTrue(defaultWarningVibrationEnabled(true))
        assertFalse(defaultWarningVibrationEnabled(false))
    }

    @Test
    fun languagePreferenceMapsStoredValues() {
        assertEquals(
            PauseLanguagePreference.FOLLOW_SYSTEM,
            PauseLanguagePreference.fromStorageValue(null)
        )
        assertEquals(
            PauseLanguagePreference.ENGLISH,
            PauseLanguagePreference.fromStorageValue("en")
        )
        assertEquals(
            PauseLanguagePreference.SIMPLIFIED_CHINESE,
            PauseLanguagePreference.fromStorageValue("zh-rCN")
        )
        assertEquals(
            PauseLanguagePreference.FOLLOW_SYSTEM,
            PauseLanguagePreference.fromStorageValue("unsupported")
        )
    }

    @Test
    fun themePreferenceMapsStoredValues() {
        assertEquals(
            PauseThemePreference.FOLLOW_SYSTEM,
            PauseThemePreference.fromStorageValue(null)
        )
        assertEquals(
            PauseThemePreference.LIGHT,
            PauseThemePreference.fromStorageValue("light")
        )
        assertEquals(
            PauseThemePreference.DARK,
            PauseThemePreference.fromStorageValue("dark")
        )
        assertEquals(
            PauseThemePreference.FOLLOW_SYSTEM,
            PauseThemePreference.fromStorageValue("unsupported")
        )
    }
}
