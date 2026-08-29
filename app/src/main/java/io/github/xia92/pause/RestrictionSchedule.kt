package io.github.xia92.pause

import java.util.Calendar
import java.util.UUID

private const val MINUTES_PER_DAY_FOR_SCHEDULES = 24 * 60
const val MIGRATED_RESTRICTION_SCHEDULE_ID = "migrated_single_time_schedule"

enum class AppRestrictionMode(val storageValue: String) {
    BLOCK_SELECTED("block_selected"),
    ALLOW_SELECTED_ONLY("allow_selected_only");

    companion object {
        fun fromStorageValue(storageValue: String?): AppRestrictionMode {
            return entries.firstOrNull { mode -> mode.storageValue == storageValue }
                ?: BLOCK_SELECTED
        }
    }
}

enum class PauseWeekday(
    val calendarDayOfWeek: Int,
    val shortLabel: String
) {
    MONDAY(Calendar.MONDAY, "Mon"),
    TUESDAY(Calendar.TUESDAY, "Tue"),
    WEDNESDAY(Calendar.WEDNESDAY, "Wed"),
    THURSDAY(Calendar.THURSDAY, "Thu"),
    FRIDAY(Calendar.FRIDAY, "Fri"),
    SATURDAY(Calendar.SATURDAY, "Sat"),
    SUNDAY(Calendar.SUNDAY, "Sun");

    fun previous(): PauseWeekday {
        val values = entries
        val previousIndex = (ordinal - 1 + values.size) % values.size
        return values[previousIndex]
    }

    companion object {
        val everyDay: Set<PauseWeekday> = entries.toSet()
        val weekdays: Set<PauseWeekday> = setOf(
            MONDAY,
            TUESDAY,
            WEDNESDAY,
            THURSDAY,
            FRIDAY
        )
        val weekends: Set<PauseWeekday> = setOf(SATURDAY, SUNDAY)

        fun fromCalendarDayOfWeek(calendarDayOfWeek: Int): PauseWeekday {
            return entries.first { weekday ->
                weekday.calendarDayOfWeek == calendarDayOfWeek
            }
        }
    }
}

data class RestrictionSchedule(
    val id: String,
    val startMinutesOfDay: Int,
    val endMinutesOfDay: Int,
    val weekdays: Set<PauseWeekday>,
    val enabled: Boolean,
    val appRestrictionMode: AppRestrictionMode = AppRestrictionMode.BLOCK_SELECTED,
    val selectedPackages: Set<String> = emptySet()
) {
    init {
        require(id.isNotBlank()) { "Schedule id must not be blank." }
        require(startMinutesOfDay in 0 until MINUTES_PER_DAY_FOR_SCHEDULES) {
            "Start time must be between 0 and 1439 minutes."
        }
        require(endMinutesOfDay in 0 until MINUTES_PER_DAY_FOR_SCHEDULES) {
            "End time must be between 0 and 1439 minutes."
        }
        require(selectedPackages.none { packageName -> packageName.isBlank() }) {
            "Selected packages must not contain blank package names."
        }
    }

    companion object {
        fun newDefault(): RestrictionSchedule {
            return RestrictionSchedule(
                id = UUID.randomUUID().toString(),
                startMinutesOfDay = PauseSettingsRepository.DEFAULT_START_MINUTES,
                endMinutesOfDay = PauseSettingsRepository.DEFAULT_END_MINUTES,
                weekdays = PauseWeekday.everyDay,
                enabled = true,
                appRestrictionMode = AppRestrictionMode.BLOCK_SELECTED,
                selectedPackages = emptySet()
            )
        }
    }
}

fun createMigratedRestrictionSchedule(
    oldStartMinutes: Int,
    oldEndMinutes: Int,
    selectedPackages: Set<String> = emptySet()
): RestrictionSchedule {
    return RestrictionSchedule(
        id = MIGRATED_RESTRICTION_SCHEDULE_ID,
        startMinutesOfDay = TimeOfDay.fromMinutes(oldStartMinutes).minutesSinceMidnight,
        endMinutesOfDay = TimeOfDay.fromMinutes(oldEndMinutes).minutesSinceMidnight,
        weekdays = PauseWeekday.everyDay,
        enabled = true,
        appRestrictionMode = AppRestrictionMode.BLOCK_SELECTED,
        selectedPackages = selectedPackages
    )
}

fun migrateRestrictionSchedulesIfMissing(
    existingSchedules: List<RestrictionSchedule>?,
    oldStartMinutes: Int,
    oldEndMinutes: Int,
    selectedPackages: Set<String> = emptySet()
): List<RestrictionSchedule> {
    return existingSchedules ?: listOf(
        createMigratedRestrictionSchedule(
            oldStartMinutes = oldStartMinutes,
            oldEndMinutes = oldEndMinutes,
            selectedPackages = selectedPackages
        )
    )
}

fun findMatchingRestrictionSchedule(
    schedules: List<RestrictionSchedule>,
    currentWeekday: PauseWeekday = currentLocalWeekday(),
    currentMinutesSinceMidnight: Int = currentLocalMinutesSinceMidnight()
): RestrictionSchedule? {
    val currentMinutes = normalizeScheduleMinutes(currentMinutesSinceMidnight)
    return schedules.firstOrNull { schedule ->
        schedule.matches(
            currentWeekday = currentWeekday,
            currentMinutesSinceMidnight = currentMinutes
        )
    }
}

fun isLocalDateTimeInsideAnyRestrictionSchedule(
    schedules: List<RestrictionSchedule>,
    currentWeekday: PauseWeekday = currentLocalWeekday(),
    currentMinutesSinceMidnight: Int = currentLocalMinutesSinceMidnight()
): Boolean {
    return findMatchingRestrictionSchedule(
        schedules = schedules,
        currentWeekday = currentWeekday,
        currentMinutesSinceMidnight = currentMinutesSinceMidnight
    ) != null
}

data class PackageRestrictionEvaluation(
    val isRestricted: Boolean,
    val activeSchedules: List<RestrictionSchedule>,
    val restrictingSchedule: RestrictionSchedule?
)

enum class RestrictionScheduleValidationError {
    EMPTY_ALLOWLIST
}

fun validateRestrictionSchedule(schedule: RestrictionSchedule): RestrictionScheduleValidationError? {
    return if (
        schedule.appRestrictionMode == AppRestrictionMode.ALLOW_SELECTED_ONLY &&
        schedule.selectedPackages.isEmpty()
    ) {
        RestrictionScheduleValidationError.EMPTY_ALLOWLIST
    } else {
        null
    }
}

fun activeRestrictionSchedules(
    schedules: List<RestrictionSchedule>,
    currentWeekday: PauseWeekday = currentLocalWeekday(),
    currentMinutesSinceMidnight: Int = currentLocalMinutesSinceMidnight()
): List<RestrictionSchedule> {
    val currentMinutes = normalizeScheduleMinutes(currentMinutesSinceMidnight)
    return schedules.filter { schedule ->
        schedule.matches(
            currentWeekday = currentWeekday,
            currentMinutesSinceMidnight = currentMinutes
        )
    }
}

fun evaluatePackageRestriction(
    schedules: List<RestrictionSchedule>,
    packageName: String,
    systemExcludedPackages: Set<String>,
    currentWeekday: PauseWeekday = currentLocalWeekday(),
    currentMinutesSinceMidnight: Int = currentLocalMinutesSinceMidnight()
): PackageRestrictionEvaluation {
    val activeSchedules = activeRestrictionSchedules(
        schedules = schedules,
        currentWeekday = currentWeekday,
        currentMinutesSinceMidnight = currentMinutesSinceMidnight
    )

    // Overlapping rules intentionally combine with OR: if any active schedule
    // restricts the package, the most restrictive result wins.
    val restrictingSchedule = activeSchedules.firstOrNull { schedule ->
        schedule.restrictsPackage(
            packageName = packageName,
            systemExcludedPackages = systemExcludedPackages
        )
    }

    return PackageRestrictionEvaluation(
        isRestricted = restrictingSchedule != null,
        activeSchedules = activeSchedules,
        restrictingSchedule = restrictingSchedule
    )
}

fun shouldRestrictPackageForSchedules(
    schedules: List<RestrictionSchedule>,
    packageName: String,
    systemExcludedPackages: Set<String>,
    currentWeekday: PauseWeekday = currentLocalWeekday(),
    currentMinutesSinceMidnight: Int = currentLocalMinutesSinceMidnight()
): Boolean {
    return evaluatePackageRestriction(
        schedules = schedules,
        packageName = packageName,
        systemExcludedPackages = systemExcludedPackages,
        currentWeekday = currentWeekday,
        currentMinutesSinceMidnight = currentMinutesSinceMidnight
    ).isRestricted
}

fun RestrictionSchedule.restrictsPackage(
    packageName: String,
    systemExcludedPackages: Set<String>
): Boolean {
    if (packageName in systemExcludedPackages) return false

    return when (appRestrictionMode) {
        AppRestrictionMode.BLOCK_SELECTED -> packageName in selectedPackages
        AppRestrictionMode.ALLOW_SELECTED_ONLY -> packageName !in selectedPackages
    }
}

fun RestrictionSchedule.matches(
    currentWeekday: PauseWeekday,
    currentMinutesSinceMidnight: Int
): Boolean {
    if (!enabled) return false
    if (weekdays.isEmpty()) return false
    if (startMinutesOfDay == endMinutesOfDay) return false

    val currentMinutes = normalizeScheduleMinutes(currentMinutesSinceMidnight)

    return if (startMinutesOfDay < endMinutesOfDay) {
        currentWeekday in weekdays &&
            currentMinutes >= startMinutesOfDay &&
            currentMinutes < endMinutesOfDay
    } else {
        val eveningMatches = currentWeekday in weekdays &&
            currentMinutes >= startMinutesOfDay
        val morningMatches = currentWeekday.previous() in weekdays &&
            currentMinutes < endMinutesOfDay

        eveningMatches || morningMatches
    }
}

fun currentLocalWeekday(): PauseWeekday {
    val now = Calendar.getInstance()
    return PauseWeekday.fromCalendarDayOfWeek(now.get(Calendar.DAY_OF_WEEK))
}

private fun normalizeScheduleMinutes(minutesSinceMidnight: Int): Int {
    return (
        (minutesSinceMidnight % MINUTES_PER_DAY_FOR_SCHEDULES) +
            MINUTES_PER_DAY_FOR_SCHEDULES
        ) % MINUTES_PER_DAY_FOR_SCHEDULES
}
