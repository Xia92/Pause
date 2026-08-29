package io.github.xia92.pause

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.pauseSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "pause_settings"
)

data class PauseSettings(
    val restrictedStartMinutes: Int = PauseSettingsRepository.DEFAULT_START_MINUTES,
    val restrictedEndMinutes: Int = PauseSettingsRepository.DEFAULT_END_MINUTES,
    val restrictionSchedules: List<RestrictionSchedule> = migrateRestrictionSchedulesIfMissing(
        existingSchedules = null,
        oldStartMinutes = restrictedStartMinutes,
        oldEndMinutes = restrictedEndMinutes
    ),
    val restrictedAppPackageNames: Set<String> = emptySet(),
    val activeUsageSessions: Map<String, Long> = emptyMap(),
    val languagePreference: PauseLanguagePreference = PauseLanguagePreference.FOLLOW_SYSTEM,
    val themePreference: PauseThemePreference = PauseThemePreference.FOLLOW_SYSTEM,
    val finalWarningSeconds: Int = DEFAULT_FINAL_WARNING_SECONDS,
    val warningVibrationEnabled: Boolean = true
)

class PauseSettingsRepository(private val context: Context) {
    val settings: Flow<PauseSettings> = context.pauseSettingsDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val restrictedStartMinutes = preferences[RESTRICTED_START_MINUTES]
                ?: DEFAULT_START_MINUTES
            val restrictedEndMinutes = preferences[RESTRICTED_END_MINUTES]
                ?: DEFAULT_END_MINUTES
            val legacyRestrictedPackages = preferences[RESTRICTED_APP_PACKAGE_NAMES]
                ?.toSet()
                ?: emptySet()
            val savedSchedules = preferences[RESTRICTION_SCHEDULES]
                ?.let { entries ->
                    parseRestrictionScheduleEntries(
                        entries = entries,
                        fallbackSelectedPackages = legacyRestrictedPackages
                    )
                }
            val restrictionSchedules = migrateRestrictionSchedulesIfMissing(
                existingSchedules = savedSchedules,
                oldStartMinutes = restrictedStartMinutes,
                oldEndMinutes = restrictedEndMinutes,
                selectedPackages = legacyRestrictedPackages
            )

            PauseSettings(
                restrictedStartMinutes = restrictedStartMinutes,
                restrictedEndMinutes = restrictedEndMinutes,
                restrictionSchedules = restrictionSchedules,
                restrictedAppPackageNames = legacyRestrictedPackages,
                activeUsageSessions = parseUsageSessions(
                    preferences[ACTIVE_USAGE_SESSIONS] ?: emptySet()
                ),
                languagePreference = PauseLanguagePreference.fromStorageValue(
                    preferences[LANGUAGE_PREFERENCE]
                ),
                themePreference = PauseThemePreference.fromStorageValue(
                    preferences[THEME_PREFERENCE]
                ),
                finalWarningSeconds = normalizeFinalWarningSeconds(
                    preferences[FINAL_WARNING_SECONDS]
                ),
                warningVibrationEnabled = defaultWarningVibrationEnabled(
                    preferences[WARNING_VIBRATION_ENABLED]
                )
            )
        }

    suspend fun migrateSingleTimeSettingsToSchedulesIfNeeded() {
        context.pauseSettingsDataStore.edit { preferences ->
            val legacyRestrictedPackages = preferences[RESTRICTED_APP_PACKAGE_NAMES]
                ?.toSet()
                ?: emptySet()
            val existingScheduleEntries = preferences[RESTRICTION_SCHEDULES]
            if (existingScheduleEntries != null) {
                val upgradedSchedules = parseRestrictionScheduleEntries(
                    entries = existingScheduleEntries,
                    fallbackSelectedPackages = legacyRestrictedPackages
                )
                val upgradedEntries = upgradedSchedules.toRestrictionScheduleEntries()
                if (upgradedEntries != existingScheduleEntries) {
                    preferences[RESTRICTION_SCHEDULES] = upgradedEntries
                    Log.i(
                        SCHEDULE_TAG,
                        "Schedule migration completed: upgradedExistingSchedules=true " +
                            "totalSchedules=${upgradedSchedules.size} " +
                            "legacySelectedPackages=${legacyRestrictedPackages.size}"
                    )
                }
                return@edit
            }

            val oldStartMinutes = preferences[RESTRICTED_START_MINUTES] ?: DEFAULT_START_MINUTES
            val oldEndMinutes = preferences[RESTRICTED_END_MINUTES] ?: DEFAULT_END_MINUTES
            val migratedSchedule = createMigratedRestrictionSchedule(
                oldStartMinutes = oldStartMinutes,
                oldEndMinutes = oldEndMinutes,
                selectedPackages = legacyRestrictedPackages
            )
            preferences[RESTRICTION_SCHEDULES] = listOf(migratedSchedule)
                .toRestrictionScheduleEntries()
            Log.i(
                SCHEDULE_TAG,
                "Schedule migration completed: createdScheduleId=${migratedSchedule.id} " +
                    "start=${formatMinutesSinceMidnight(migratedSchedule.startMinutesOfDay)} " +
                    "end=${formatMinutesSinceMidnight(migratedSchedule.endMinutesOfDay)} " +
                    "weekdays=${migratedSchedule.weekdays.toScheduleWeekdayText()} " +
                    "mode=${migratedSchedule.appRestrictionMode.storageValue} " +
                    "selectedPackages=${migratedSchedule.selectedPackages.size}"
            )
        }
    }

    suspend fun setRestrictedStartMinutes(minutesSinceMidnight: Int) {
        context.pauseSettingsDataStore.edit { preferences ->
            preferences[RESTRICTED_START_MINUTES] = TimeOfDay
                .fromMinutes(minutesSinceMidnight)
                .minutesSinceMidnight
        }
    }

    suspend fun setRestrictedEndMinutes(minutesSinceMidnight: Int) {
        context.pauseSettingsDataStore.edit { preferences ->
            preferences[RESTRICTED_END_MINUTES] = TimeOfDay
                .fromMinutes(minutesSinceMidnight)
                .minutesSinceMidnight
        }
    }

    suspend fun upsertRestrictionSchedule(schedule: RestrictionSchedule) {
        context.pauseSettingsDataStore.edit { preferences ->
            val currentSchedules = preferences.currentRestrictionSchedules()
            val updatedSchedules = if (currentSchedules.any { it.id == schedule.id }) {
                currentSchedules.map { existingSchedule ->
                    if (existingSchedule.id == schedule.id) schedule else existingSchedule
                }
            } else {
                currentSchedules + schedule
            }

            preferences[RESTRICTION_SCHEDULES] = updatedSchedules.toRestrictionScheduleEntries()
            Log.i(
                SCHEDULE_TAG,
                "Schedule saved: id=${schedule.id} enabled=${schedule.enabled} " +
                    "start=${formatMinutesSinceMidnight(schedule.startMinutesOfDay)} " +
                    "end=${formatMinutesSinceMidnight(schedule.endMinutesOfDay)} " +
                    "weekdays=${schedule.weekdays.toScheduleWeekdayText()} " +
                    "mode=${schedule.appRestrictionMode.storageValue} " +
                    "selectedPackages=${schedule.selectedPackages.size} " +
                    "totalSchedules=${updatedSchedules.size}"
            )
        }
    }

    suspend fun setRestrictionScheduleEnabled(scheduleId: String, enabled: Boolean) {
        context.pauseSettingsDataStore.edit { preferences ->
            val currentSchedules = preferences.currentRestrictionSchedules()
            val updatedSchedules = currentSchedules.map { schedule ->
                if (schedule.id == scheduleId) {
                    schedule.copy(enabled = enabled)
                } else {
                    schedule
                }
            }

            preferences[RESTRICTION_SCHEDULES] = updatedSchedules.toRestrictionScheduleEntries()
            Log.i(
                SCHEDULE_TAG,
                "Schedule enabled state changed: id=$scheduleId enabled=$enabled"
            )
        }
    }

    suspend fun deleteRestrictionSchedule(scheduleId: String) {
        context.pauseSettingsDataStore.edit { preferences ->
            val currentSchedules = preferences.currentRestrictionSchedules()
            val updatedSchedules = currentSchedules.filterNot { schedule ->
                schedule.id == scheduleId
            }

            preferences[RESTRICTION_SCHEDULES] = updatedSchedules.toRestrictionScheduleEntries()
            Log.i(
                SCHEDULE_TAG,
                "Schedule deleted: id=$scheduleId totalSchedules=${updatedSchedules.size}"
            )
        }
    }

    suspend fun setLanguagePreference(languagePreference: PauseLanguagePreference) {
        context.pauseSettingsDataStore.edit { preferences ->
            if (preferences[LANGUAGE_PREFERENCE] == languagePreference.storageValue) {
                return@edit
            }

            preferences[LANGUAGE_PREFERENCE] = languagePreference.storageValue
        }
    }

    suspend fun setThemePreference(themePreference: PauseThemePreference) {
        context.pauseSettingsDataStore.edit { preferences ->
            if (preferences[THEME_PREFERENCE] == themePreference.storageValue) {
                return@edit
            }

            preferences[THEME_PREFERENCE] = themePreference.storageValue
        }
    }

    suspend fun setFinalWarningSeconds(seconds: Int) {
        context.pauseSettingsDataStore.edit { preferences ->
            preferences[FINAL_WARNING_SECONDS] = normalizeFinalWarningSeconds(seconds)
        }
    }

    suspend fun setWarningVibrationEnabled(enabled: Boolean) {
        context.pauseSettingsDataStore.edit { preferences ->
            preferences[WARNING_VIBRATION_ENABLED] = enabled
        }
    }

    suspend fun setAppRestricted(packageName: String, isRestricted: Boolean) {
        context.pauseSettingsDataStore.edit { preferences ->
            val currentPackageNames = preferences[RESTRICTED_APP_PACKAGE_NAMES] ?: emptySet()

            preferences[RESTRICTED_APP_PACKAGE_NAMES] = if (isRestricted) {
                currentPackageNames + packageName
            } else {
                currentPackageNames - packageName
            }
        }
    }

    suspend fun createUsageSession(packageName: String, allowedUntilMillis: Long) {
        context.pauseSettingsDataStore.edit { preferences ->
            val currentSessions = parseUsageSessions(
                preferences[ACTIVE_USAGE_SESSIONS] ?: emptySet()
            )

            preferences[ACTIVE_USAGE_SESSIONS] = withUsageSessionForPackage(
                activeUsageSessions = currentSessions,
                packageName = packageName,
                allowedUntilMillis = allowedUntilMillis
            ).toUsageSessionEntries()
        }
    }

    suspend fun removeUsageSession(packageName: String) {
        context.pauseSettingsDataStore.edit { preferences ->
            val currentSessions = parseUsageSessions(
                preferences[ACTIVE_USAGE_SESSIONS] ?: emptySet()
            )

            preferences[ACTIVE_USAGE_SESSIONS] = withoutUsageSessionForPackage(
                activeUsageSessions = currentSessions,
                packageName = packageName
            ).toUsageSessionEntries()
        }
    }

    suspend fun resetUserPreferences() {
        context.pauseSettingsDataStore.edit { preferences ->
            preferences[LANGUAGE_PREFERENCE] = PauseLanguagePreference.FOLLOW_SYSTEM.storageValue
            preferences[THEME_PREFERENCE] = PauseThemePreference.FOLLOW_SYSTEM.storageValue
            preferences[FINAL_WARNING_SECONDS] = DEFAULT_FINAL_WARNING_SECONDS
            preferences[WARNING_VIBRATION_ENABLED] = true
        }
    }

    companion object {
        val DEFAULT_START_MINUTES: Int = toMinutesSinceMidnight(22, 30)
        val DEFAULT_END_MINUTES: Int = toMinutesSinceMidnight(7, 0)

        internal val RESTRICTED_START_MINUTES = intPreferencesKey("restricted_start_minutes")
        internal val RESTRICTED_END_MINUTES = intPreferencesKey("restricted_end_minutes")
        internal val RESTRICTION_SCHEDULES =
            stringSetPreferencesKey("restriction_schedules")
        internal val RESTRICTED_APP_PACKAGE_NAMES =
            stringSetPreferencesKey("restricted_app_package_names")
        private val ACTIVE_USAGE_SESSIONS = stringSetPreferencesKey("active_usage_sessions")
        private val LANGUAGE_PREFERENCE = stringPreferencesKey("language_preference")
        private val THEME_PREFERENCE = stringPreferencesKey("theme_preference")
        private val FINAL_WARNING_SECONDS = intPreferencesKey("final_warning_seconds")
        private val WARNING_VIBRATION_ENABLED =
            booleanPreferencesKey("warning_vibration_enabled")
        private const val SCHEDULE_TAG = "PauseSchedule"
    }
}

private fun Preferences.currentRestrictionSchedules(): List<RestrictionSchedule> {
    val restrictedStartMinutes = this[PauseSettingsRepository.RESTRICTED_START_MINUTES]
        ?: PauseSettingsRepository.DEFAULT_START_MINUTES
    val restrictedEndMinutes = this[PauseSettingsRepository.RESTRICTED_END_MINUTES]
        ?: PauseSettingsRepository.DEFAULT_END_MINUTES
    val legacyRestrictedPackages = this[PauseSettingsRepository.RESTRICTED_APP_PACKAGE_NAMES]
        ?.toSet()
        ?: emptySet()
    val savedSchedules = this[PauseSettingsRepository.RESTRICTION_SCHEDULES]
        ?.let { entries ->
            parseRestrictionScheduleEntries(
                entries = entries,
                fallbackSelectedPackages = legacyRestrictedPackages
            )
        }

    return migrateRestrictionSchedulesIfMissing(
        existingSchedules = savedSchedules,
        oldStartMinutes = restrictedStartMinutes,
        oldEndMinutes = restrictedEndMinutes,
        selectedPackages = legacyRestrictedPackages
    )
}

internal fun parseRestrictionScheduleEntries(
    entries: Set<String>,
    fallbackSelectedPackages: Set<String> = emptySet()
): List<RestrictionSchedule> {
    return entries.mapNotNull { entry ->
        parseRestrictionScheduleEntry(
            entry = entry,
            fallbackSelectedPackages = fallbackSelectedPackages
        )
    }
        .sortedBy { schedule -> schedule.id }
}

private fun parseRestrictionScheduleEntry(
    entry: String,
    fallbackSelectedPackages: Set<String>
): RestrictionSchedule? {
    val parts = entry.split('|', limit = 7)
    if (parts.size != 5 && parts.size != 7) return null

    val id = parts[0].takeIf { it.isNotBlank() } ?: return null
    val startMinutes = parts[1].toIntOrNull() ?: return null
    val endMinutes = parts[2].toIntOrNull() ?: return null
    val enabled = parts[3].toBooleanStrictOrNull() ?: return null
    val weekdays = parts[4]
        .split(',')
        .mapNotNull { weekdayNumberText ->
            val weekdayNumber = weekdayNumberText.toIntOrNull() ?: return@mapNotNull null
            PauseWeekday.entries.getOrNull(weekdayNumber - 1)
        }
        .toSet()
    val appRestrictionMode = if (parts.size >= 6) {
        AppRestrictionMode.fromStorageValue(parts[5])
    } else {
        AppRestrictionMode.BLOCK_SELECTED
    }
    val selectedPackages = if (parts.size >= 7) {
        parseSchedulePackageSet(parts[6])
    } else {
        fallbackSelectedPackages
    }

    return runCatching {
        RestrictionSchedule(
            id = id,
            startMinutesOfDay = TimeOfDay.fromMinutes(startMinutes).minutesSinceMidnight,
            endMinutesOfDay = TimeOfDay.fromMinutes(endMinutes).minutesSinceMidnight,
            weekdays = weekdays,
            enabled = enabled,
            appRestrictionMode = appRestrictionMode,
            selectedPackages = selectedPackages
        )
    }.getOrNull()
}

private fun List<RestrictionSchedule>.toRestrictionScheduleEntries(): Set<String> {
    return map { schedule ->
        schedule.toRestrictionScheduleEntry()
    }.toSet()
}

private fun RestrictionSchedule.toRestrictionScheduleEntry(): String {
    val weekdayNumbers = weekdays
        .sortedBy { weekday -> weekday.ordinal }
        .joinToString(separator = ",") { weekday ->
            (weekday.ordinal + 1).toString()
        }

    return listOf(
        id,
        startMinutesOfDay.toString(),
        endMinutesOfDay.toString(),
        enabled.toString(),
        weekdayNumbers,
        appRestrictionMode.storageValue,
        selectedPackages
            .sorted()
            .joinToString(separator = ",")
    ).joinToString(separator = "|")
}

private fun parseSchedulePackageSet(packageListText: String): Set<String> {
    if (packageListText.isBlank()) return emptySet()

    return packageListText
        .split(',')
        .map { packageName -> packageName.trim() }
        .filter { packageName -> packageName.isNotBlank() }
        .toSet()
}

private fun Set<PauseWeekday>.toScheduleWeekdayText(): String {
    if (isEmpty()) return "none"

    return sortedBy { weekday -> weekday.ordinal }
        .joinToString(separator = " ") { weekday -> weekday.shortLabel }
}

private fun parseUsageSessions(entries: Set<String>): Map<String, Long> {
    return entries.mapNotNull { entry ->
        val separatorIndex = entry.lastIndexOf('|')
        if (separatorIndex <= 0 || separatorIndex == entry.lastIndex) {
            return@mapNotNull null
        }

        val packageName = entry.substring(0, separatorIndex)
        val allowedUntilMillis = entry.substring(separatorIndex + 1).toLongOrNull()
            ?: return@mapNotNull null

        packageName to allowedUntilMillis
    }.toMap()
}

private fun Map<String, Long>.toUsageSessionEntries(): Set<String> {
    return map { (packageName, allowedUntilMillis) ->
        "$packageName|$allowedUntilMillis"
    }.toSet()
}
