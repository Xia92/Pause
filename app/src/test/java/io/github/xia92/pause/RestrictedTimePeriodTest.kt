package io.github.xia92.pause

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictedTimePeriodTest {
    @Test
    fun crossMidnightPeriod_containsLateNightTime() {
        val start = toMinutesSinceMidnight(22, 30)
        val end = toMinutesSinceMidnight(7, 0)

        assertTrue(
            isCurrentLocalTimeInsideRestrictedPeriod(
                startMinutes = start,
                endMinutes = end,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(23, 0)
            )
        )
    }

    @Test
    fun crossMidnightPeriod_containsEarlyMorningTime() {
        val start = toMinutesSinceMidnight(22, 30)
        val end = toMinutesSinceMidnight(7, 0)

        assertTrue(
            isCurrentLocalTimeInsideRestrictedPeriod(
                startMinutes = start,
                endMinutes = end,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(2, 0)
            )
        )
    }

    @Test
    fun crossMidnightPeriod_excludesDaytime() {
        val start = toMinutesSinceMidnight(22, 30)
        val end = toMinutesSinceMidnight(7, 0)

        assertFalse(
            isCurrentLocalTimeInsideRestrictedPeriod(
                startMinutes = start,
                endMinutes = end,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(12, 0)
            )
        )
    }

    @Test
    fun sameDayPeriod_containsTimeBetweenStartAndEnd() {
        val start = toMinutesSinceMidnight(9, 0)
        val end = toMinutesSinceMidnight(17, 0)

        assertTrue(
            isCurrentLocalTimeInsideRestrictedPeriod(
                startMinutes = start,
                endMinutes = end,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(10, 0)
            )
        )
    }

    @Test
    fun sameDayPeriod_excludesTimeAfterEnd() {
        val start = toMinutesSinceMidnight(9, 0)
        val end = toMinutesSinceMidnight(17, 0)

        assertFalse(
            isCurrentLocalTimeInsideRestrictedPeriod(
                startMinutes = start,
                endMinutes = end,
                currentMinutesSinceMidnight = toMinutesSinceMidnight(18, 0)
            )
        )
    }

    @Test
    fun periodIncludesStartMinute() {
        val start = toMinutesSinceMidnight(9, 0)
        val end = toMinutesSinceMidnight(17, 0)

        assertTrue(
            isCurrentLocalTimeInsideRestrictedPeriod(
                startMinutes = start,
                endMinutes = end,
                currentMinutesSinceMidnight = start
            )
        )
    }

    @Test
    fun periodExcludesEndMinute() {
        val start = toMinutesSinceMidnight(9, 0)
        val end = toMinutesSinceMidnight(17, 0)

        assertFalse(
            isCurrentLocalTimeInsideRestrictedPeriod(
                startMinutes = start,
                endMinutes = end,
                currentMinutesSinceMidnight = end
            )
        )
    }

    @Test
    fun matchingStartAndEndMeansNotRestricted() {
        val start = toMinutesSinceMidnight(9, 0)

        assertFalse(
            isCurrentLocalTimeInsideRestrictedPeriod(
                startMinutes = start,
                endMinutes = start,
                currentMinutesSinceMidnight = start
            )
        )
    }
}
