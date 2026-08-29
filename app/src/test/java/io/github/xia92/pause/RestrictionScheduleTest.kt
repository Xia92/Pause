package io.github.xia92.pause

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionScheduleTest {
    @Test
    fun sameDayMondayScheduleUsesStartInclusiveEndExclusive() {
        val schedule = schedule(
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
            weekdays = setOf(PauseWeekday.MONDAY)
        )

        assertFalse(schedule.matches(PauseWeekday.MONDAY, toMinutesSinceMidnight(8, 59)))
        assertTrue(schedule.matches(PauseWeekday.MONDAY, toMinutesSinceMidnight(9, 0)))
        assertTrue(schedule.matches(PauseWeekday.MONDAY, toMinutesSinceMidnight(16, 59)))
        assertFalse(schedule.matches(PauseWeekday.MONDAY, toMinutesSinceMidnight(17, 0)))
    }

    @Test
    fun weekdayFilteringPreventsMondayRuleFromMatchingTuesday() {
        val schedule = schedule(
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
            weekdays = setOf(PauseWeekday.MONDAY)
        )

        assertFalse(schedule.matches(PauseWeekday.TUESDAY, toMinutesSinceMidnight(10, 0)))
    }

    @Test
    fun crossMidnightFridayScheduleMatchesFridayNightAndSaturdayMorning() {
        val schedule = schedule(
            startHour = 22,
            startMinute = 30,
            endHour = 7,
            endMinute = 0,
            weekdays = setOf(PauseWeekday.FRIDAY)
        )

        assertFalse(schedule.matches(PauseWeekday.FRIDAY, toMinutesSinceMidnight(22, 29)))
        assertTrue(schedule.matches(PauseWeekday.FRIDAY, toMinutesSinceMidnight(22, 30)))
        assertTrue(schedule.matches(PauseWeekday.FRIDAY, toMinutesSinceMidnight(23, 59)))
        assertTrue(schedule.matches(PauseWeekday.SATURDAY, toMinutesSinceMidnight(0, 1)))
        assertTrue(schedule.matches(PauseWeekday.SATURDAY, toMinutesSinceMidnight(6, 59)))
        assertFalse(schedule.matches(PauseWeekday.SATURDAY, toMinutesSinceMidnight(7, 0)))
    }

    @Test
    fun crossMidnightMorningUsesPreviousWeekdayAsOriginatingScheduleDay() {
        val fridayNightSchedule = schedule(
            startHour = 22,
            startMinute = 30,
            endHour = 7,
            endMinute = 0,
            weekdays = setOf(PauseWeekday.FRIDAY)
        )
        val saturdayNightSchedule = fridayNightSchedule.copy(
            weekdays = setOf(PauseWeekday.SATURDAY)
        )

        assertTrue(fridayNightSchedule.matches(PauseWeekday.SATURDAY, toMinutesSinceMidnight(2, 0)))
        assertFalse(saturdayNightSchedule.matches(PauseWeekday.SATURDAY, toMinutesSinceMidnight(2, 0)))
    }

    @Test
    fun disabledRuleNeverMatches() {
        val schedule = schedule(
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
            weekdays = PauseWeekday.everyDay,
            enabled = false
        )

        assertFalse(schedule.matches(PauseWeekday.MONDAY, toMinutesSinceMidnight(10, 0)))
    }

    @Test
    fun zeroLengthRuleNeverMatches() {
        val schedule = schedule(
            startHour = 9,
            startMinute = 0,
            endHour = 9,
            endMinute = 0,
            weekdays = PauseWeekday.everyDay
        )

        assertFalse(schedule.matches(PauseWeekday.MONDAY, toMinutesSinceMidnight(9, 0)))
    }

    @Test
    fun multipleRulesRestrictWhenAnyEnabledRuleMatches() {
        val schedules = listOf(
            schedule(
                startHour = 9,
                startMinute = 0,
                endHour = 10,
                endMinute = 0,
                weekdays = setOf(PauseWeekday.MONDAY)
            ),
            schedule(
                id = "rule-b",
                startHour = 12,
                startMinute = 30,
                endHour = 14,
                endMinute = 0,
                weekdays = setOf(PauseWeekday.MONDAY)
            )
        )

        assertTrue(
            isLocalDateTimeInsideAnyRestrictionSchedule(
                schedules = schedules,
                currentWeekday = PauseWeekday.MONDAY,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(13, 0)
            )
        )
    }

    @Test
    fun overlappingRulesReturnOneMatchingRuleWithoutProblems() {
        val schedules = listOf(
            schedule(
                id = "rule-a",
                startHour = 9,
                startMinute = 0,
                endHour = 12,
                endMinute = 0,
                weekdays = setOf(PauseWeekday.MONDAY)
            ),
            schedule(
                id = "rule-b",
                startHour = 10,
                startMinute = 0,
                endHour = 14,
                endMinute = 0,
                weekdays = setOf(PauseWeekday.MONDAY)
            )
        )

        val match = findMatchingRestrictionSchedule(
            schedules = schedules,
            currentWeekday = PauseWeekday.MONDAY,
            currentMinutesSinceMidnight = toMinutesSinceMidnight(10, 30)
        )

        assertTrue(match != null)
    }

    @Test
    fun emptyScheduleListIsNotRestricted() {
        assertFalse(
            isLocalDateTimeInsideAnyRestrictionSchedule(
                schedules = emptyList(),
                currentWeekday = PauseWeekday.MONDAY,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(10, 0)
            )
        )
    }

    @Test
    fun migrationCreatesOneEveryDayScheduleAndDoesNotDuplicateIt() {
        val migratedSchedules = migrateRestrictionSchedulesIfMissing(
            existingSchedules = null,
            oldStartMinutes = toMinutesSinceMidnight(22, 30),
            oldEndMinutes = toMinutesSinceMidnight(7, 0),
            selectedPackages = setOf(PACKAGE_A, PACKAGE_B)
        )
        val migratedAgain = migrateRestrictionSchedulesIfMissing(
            existingSchedules = migratedSchedules,
            oldStartMinutes = toMinutesSinceMidnight(22, 30),
            oldEndMinutes = toMinutesSinceMidnight(7, 0)
        )

        assertEquals(1, migratedSchedules.size)
        assertEquals(MIGRATED_RESTRICTION_SCHEDULE_ID, migratedSchedules.single().id)
        assertEquals(PauseWeekday.everyDay, migratedSchedules.single().weekdays)
        assertTrue(migratedSchedules.single().enabled)
        assertEquals(AppRestrictionMode.BLOCK_SELECTED, migratedSchedules.single().appRestrictionMode)
        assertEquals(setOf(PACKAGE_A, PACKAGE_B), migratedSchedules.single().selectedPackages)
        assertEquals(1, migratedAgain.size)
    }

    @Test
    fun oldStoredScheduleEntriesUseLegacySelectedPackagesAsBlockList() {
        val oldScheduleEntry = "legacy|540|1020|true|1,2,3,4,5,6,7"

        val parsedSchedules = parseRestrictionScheduleEntries(
            entries = setOf(oldScheduleEntry),
            fallbackSelectedPackages = setOf(PACKAGE_A, PACKAGE_B)
        )

        assertEquals(1, parsedSchedules.size)
        assertEquals(AppRestrictionMode.BLOCK_SELECTED, parsedSchedules.single().appRestrictionMode)
        assertEquals(setOf(PACKAGE_A, PACKAGE_B), parsedSchedules.single().selectedPackages)
    }

    @Test
    fun blockSelectedRestrictsSelectedApp() {
        val schedule = activeSchedule(
            appRestrictionMode = AppRestrictionMode.BLOCK_SELECTED,
            selectedPackages = setOf(PACKAGE_A)
        )

        assertTrue(
            shouldRestrictPackageForSchedules(
                schedules = listOf(schedule),
                packageName = PACKAGE_A,
                systemExcludedPackages = SYSTEM_EXCLUDED_PACKAGES,
                currentWeekday = PauseWeekday.MONDAY,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(10, 0)
            )
        )
    }

    @Test
    fun blockSelectedAllowsUnselectedApp() {
        val schedule = activeSchedule(
            appRestrictionMode = AppRestrictionMode.BLOCK_SELECTED,
            selectedPackages = setOf(PACKAGE_A)
        )

        assertFalse(
            shouldRestrictPackageForSchedules(
                schedules = listOf(schedule),
                packageName = PACKAGE_B,
                systemExcludedPackages = SYSTEM_EXCLUDED_PACKAGES,
                currentWeekday = PauseWeekday.MONDAY,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(10, 0)
            )
        )
    }

    @Test
    fun allowSelectedOnlyAllowsSelectedApp() {
        val schedule = activeSchedule(
            appRestrictionMode = AppRestrictionMode.ALLOW_SELECTED_ONLY,
            selectedPackages = setOf(PACKAGE_A)
        )

        assertFalse(
            shouldRestrictPackageForSchedules(
                schedules = listOf(schedule),
                packageName = PACKAGE_A,
                systemExcludedPackages = SYSTEM_EXCLUDED_PACKAGES,
                currentWeekday = PauseWeekday.MONDAY,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(10, 0)
            )
        )
    }

    @Test
    fun allowSelectedOnlyRestrictsUnselectedOrdinaryApp() {
        val schedule = activeSchedule(
            appRestrictionMode = AppRestrictionMode.ALLOW_SELECTED_ONLY,
            selectedPackages = setOf(PACKAGE_A)
        )

        assertTrue(
            shouldRestrictPackageForSchedules(
                schedules = listOf(schedule),
                packageName = PACKAGE_B,
                systemExcludedPackages = SYSTEM_EXCLUDED_PACKAGES,
                currentWeekday = PauseWeekday.MONDAY,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(10, 0)
            )
        )
    }

    @Test
    fun systemExcludedPackageIsAllowedRegardlessOfWhitelist() {
        val schedule = activeSchedule(
            appRestrictionMode = AppRestrictionMode.ALLOW_SELECTED_ONLY,
            selectedPackages = setOf(PACKAGE_A)
        )

        assertFalse(
            shouldRestrictPackageForSchedules(
                schedules = listOf(schedule),
                packageName = SYSTEM_UI_PACKAGE,
                systemExcludedPackages = SYSTEM_EXCLUDED_PACKAGES,
                currentWeekday = PauseWeekday.MONDAY,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(10, 0)
            )
        )
    }

    @Test
    fun pausePackageIsNeverRestricted() {
        val schedule = activeSchedule(
            appRestrictionMode = AppRestrictionMode.ALLOW_SELECTED_ONLY,
            selectedPackages = setOf(PACKAGE_A)
        )

        assertFalse(
            shouldRestrictPackageForSchedules(
                schedules = listOf(schedule),
                packageName = PAUSE_PACKAGE,
                systemExcludedPackages = SYSTEM_EXCLUDED_PACKAGES,
                currentWeekday = PauseWeekday.MONDAY,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(10, 0)
            )
        )
    }

    @Test
    fun overlappingSchedulesUseMostRestrictiveResult() {
        val blockPackageA = activeSchedule(
            id = "block-a",
            appRestrictionMode = AppRestrictionMode.BLOCK_SELECTED,
            selectedPackages = setOf(PACKAGE_A)
        )
        val allowPackageA = activeSchedule(
            id = "allow-a",
            appRestrictionMode = AppRestrictionMode.ALLOW_SELECTED_ONLY,
            selectedPackages = setOf(PACKAGE_A)
        )

        assertTrue(
            shouldRestrictPackageForSchedules(
                schedules = listOf(blockPackageA, allowPackageA),
                packageName = PACKAGE_A,
                systemExcludedPackages = SYSTEM_EXCLUDED_PACKAGES,
                currentWeekday = PauseWeekday.MONDAY,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(10, 0)
            )
        )
    }

    @Test
    fun inactiveScheduleDoesNotParticipateInPackageRestriction() {
        val disabledSchedule = activeSchedule(
            enabled = false,
            appRestrictionMode = AppRestrictionMode.BLOCK_SELECTED,
            selectedPackages = setOf(PACKAGE_A)
        )

        assertFalse(
            shouldRestrictPackageForSchedules(
                schedules = listOf(disabledSchedule),
                packageName = PACKAGE_A,
                systemExcludedPackages = SYSTEM_EXCLUDED_PACKAGES,
                currentWeekday = PauseWeekday.MONDAY,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(10, 0)
            )
        )
    }

    @Test
    fun overnightScheduleStillRestrictsSelectedPackageAcrossMidnight() {
        val schedule = schedule(
            startHour = 22,
            startMinute = 30,
            endHour = 7,
            endMinute = 0,
            weekdays = setOf(PauseWeekday.FRIDAY),
            selectedPackages = setOf(PACKAGE_A)
        )

        assertTrue(
            shouldRestrictPackageForSchedules(
                schedules = listOf(schedule),
                packageName = PACKAGE_A,
                systemExcludedPackages = SYSTEM_EXCLUDED_PACKAGES,
                currentWeekday = PauseWeekday.SATURDAY,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(2, 0)
            )
        )
    }

    @Test
    fun weekdayScheduleStillRestrictsOnlySelectedWeekday() {
        val schedule = schedule(
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
            weekdays = setOf(PauseWeekday.MONDAY),
            selectedPackages = setOf(PACKAGE_A)
        )

        assertTrue(
            shouldRestrictPackageForSchedules(
                schedules = listOf(schedule),
                packageName = PACKAGE_A,
                systemExcludedPackages = SYSTEM_EXCLUDED_PACKAGES,
                currentWeekday = PauseWeekday.MONDAY,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(10, 0)
            )
        )
        assertFalse(
            shouldRestrictPackageForSchedules(
                schedules = listOf(schedule),
                packageName = PACKAGE_A,
                systemExcludedPackages = SYSTEM_EXCLUDED_PACKAGES,
                currentWeekday = PauseWeekday.TUESDAY,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(10, 0)
            )
        )
    }

    @Test
    fun emptyBlockSelectedRestrictsNothing() {
        val schedule = activeSchedule(
            appRestrictionMode = AppRestrictionMode.BLOCK_SELECTED,
            selectedPackages = emptySet()
        )

        assertFalse(
            shouldRestrictPackageForSchedules(
                schedules = listOf(schedule),
                packageName = PACKAGE_A,
                systemExcludedPackages = SYSTEM_EXCLUDED_PACKAGES,
                currentWeekday = PauseWeekday.MONDAY,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(10, 0)
            )
        )
    }

    @Test
    fun emptyAllowSelectedOnlyScheduleIsRejectedByValidation() {
        val schedule = activeSchedule(
            appRestrictionMode = AppRestrictionMode.ALLOW_SELECTED_ONLY,
            selectedPackages = emptySet()
        )

        assertEquals(
            RestrictionScheduleValidationError.EMPTY_ALLOWLIST,
            validateRestrictionSchedule(schedule)
        )
    }

    private fun activeSchedule(
        id: String = "rule-a",
        enabled: Boolean = true,
        appRestrictionMode: AppRestrictionMode,
        selectedPackages: Set<String>
    ): RestrictionSchedule {
        return schedule(
            id = id,
            startHour = 9,
            startMinute = 0,
            endHour = 17,
            endMinute = 0,
            weekdays = setOf(PauseWeekday.MONDAY),
            enabled = enabled,
            appRestrictionMode = appRestrictionMode,
            selectedPackages = selectedPackages
        )
    }

    private fun schedule(
        id: String = "rule-a",
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int,
        weekdays: Set<PauseWeekday>,
        enabled: Boolean = true,
        appRestrictionMode: AppRestrictionMode = AppRestrictionMode.BLOCK_SELECTED,
        selectedPackages: Set<String> = emptySet()
    ): RestrictionSchedule {
        return RestrictionSchedule(
            id = id,
            startMinutesOfDay = toMinutesSinceMidnight(startHour, startMinute),
            endMinutesOfDay = toMinutesSinceMidnight(endHour, endMinute),
            weekdays = weekdays,
            enabled = enabled,
            appRestrictionMode = appRestrictionMode,
            selectedPackages = selectedPackages
        )
    }

    companion object {
        private const val PACKAGE_A = "com.example.a"
        private const val PACKAGE_B = "com.example.b"
        private const val PAUSE_PACKAGE = "io.github.xia92.pause"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private val SYSTEM_EXCLUDED_PACKAGES = setOf(PAUSE_PACKAGE, SYSTEM_UI_PACKAGE)
    }
}
