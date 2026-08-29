package io.github.xia92.pause

import java.util.Calendar
import java.util.Locale

private const val MINUTES_PER_DAY = 24 * 60

data class TimeOfDay(val hour: Int, val minute: Int) {
    init {
        require(hour in 0..23) { "Hour must be between 0 and 23." }
        require(minute in 0..59) { "Minute must be between 0 and 59." }
    }

    val minutesSinceMidnight: Int = hour * 60 + minute

    fun format(): String = String.format(Locale.US, "%02d:%02d", hour, minute)

    companion object {
        fun fromMinutes(minutesSinceMidnight: Int): TimeOfDay {
            val normalizedMinutes = normalizeMinutes(minutesSinceMidnight)
            return TimeOfDay(
                hour = normalizedMinutes / 60,
                minute = normalizedMinutes % 60
            )
        }
    }
}

data class RestrictedTimePeriod(
    val startMinutes: Int,
    val endMinutes: Int
) {
    init {
        require(startMinutes in 0 until MINUTES_PER_DAY) {
            "Start time must be between 0 and 1439 minutes."
        }
        require(endMinutes in 0 until MINUTES_PER_DAY) {
            "End time must be between 0 and 1439 minutes."
        }
    }

    fun contains(currentMinutesSinceMidnight: Int): Boolean {
        val currentMinutes = normalizeMinutes(currentMinutesSinceMidnight)

        return when {
            startMinutes == endMinutes -> false
            startMinutes < endMinutes -> currentMinutes >= startMinutes &&
                currentMinutes < endMinutes
            else -> currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }
}

fun isCurrentLocalTimeInsideRestrictedPeriod(
    startMinutes: Int,
    endMinutes: Int,
    currentMinutesSinceMidnight: Int = currentLocalMinutesSinceMidnight()
): Boolean {
    return RestrictedTimePeriod(startMinutes, endMinutes).contains(currentMinutesSinceMidnight)
}

fun currentLocalMinutesSinceMidnight(): Int {
    val now = Calendar.getInstance()
    return toMinutesSinceMidnight(
        hour = now.get(Calendar.HOUR_OF_DAY),
        minute = now.get(Calendar.MINUTE)
    )
}

fun toMinutesSinceMidnight(hour: Int, minute: Int): Int {
    return TimeOfDay(hour, minute).minutesSinceMidnight
}

fun formatMinutesSinceMidnight(minutesSinceMidnight: Int): String {
    return TimeOfDay.fromMinutes(minutesSinceMidnight).format()
}

private fun normalizeMinutes(minutesSinceMidnight: Int): Int {
    return ((minutesSinceMidnight % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
}
