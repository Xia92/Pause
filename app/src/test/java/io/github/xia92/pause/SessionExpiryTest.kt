package io.github.xia92.pause

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExpiryTest {
    @Test
    fun sessionWithMoreThanTwentySecondsRemainingComputesFutureWarningDelay() {
        val timing = computeSessionExpiryTiming(
            currentTimeMillis = 10_000L,
            allowedUntilMillis = 70_000L
        )

        assertFalse(timing.isExpired)
        assertFalse(timing.shouldStartWarningNow)
        assertEquals(40_000L, timing.warningDelayMillis)
        assertEquals(60_000L, timing.expiryDelayMillis)
    }

    @Test
    fun oneMinuteSessionComputesFortySecondWarningDelayAndSixtySecondExpiryDelay() {
        val timing = computeSessionExpiryTiming(
            currentTimeMillis = 1_000_000L,
            allowedUntilMillis = 1_060_000L
        )

        assertEquals(60_000L, timing.remainingMillis)
        assertEquals(40_000L, timing.warningDelayMillis)
        assertEquals(60_000L, timing.expiryDelayMillis)
    }

    @Test
    fun oneMinuteSessionUsesConfiguredWarningDurations() {
        val warningTenSeconds = computeSessionExpiryTiming(
            currentTimeMillis = 1_000_000L,
            allowedUntilMillis = 1_060_000L,
            warningDurationMillis = finalWarningDurationMillis(10)
        )
        val warningTwentySeconds = computeSessionExpiryTiming(
            currentTimeMillis = 1_000_000L,
            allowedUntilMillis = 1_060_000L,
            warningDurationMillis = finalWarningDurationMillis(20)
        )
        val warningThirtySeconds = computeSessionExpiryTiming(
            currentTimeMillis = 1_000_000L,
            allowedUntilMillis = 1_060_000L,
            warningDurationMillis = finalWarningDurationMillis(30)
        )

        assertEquals(50_000L, warningTenSeconds.warningDelayMillis)
        assertEquals(40_000L, warningTwentySeconds.warningDelayMillis)
        assertEquals(30_000L, warningThirtySeconds.warningDelayMillis)
        assertEquals(60_000L, warningTenSeconds.expiryDelayMillis)
        assertEquals(60_000L, warningTwentySeconds.expiryDelayMillis)
        assertEquals(60_000L, warningThirtySeconds.expiryDelayMillis)
    }

    @Test
    fun sessionWithLessThanTwentySecondsRemainingStartsWarningImmediately() {
        val timing = computeSessionExpiryTiming(
            currentTimeMillis = 10_000L,
            allowedUntilMillis = 25_000L
        )

        assertFalse(timing.isExpired)
        assertTrue(timing.shouldStartWarningNow)
        assertEquals(0L, timing.warningDelayMillis)
        assertEquals(15_000L, timing.expiryDelayMillis)
    }

    @Test
    fun sessionShorterThanConfiguredWarningDurationStartsWarningImmediately() {
        val timing = computeSessionExpiryTiming(
            currentTimeMillis = 10_000L,
            allowedUntilMillis = 25_000L,
            warningDurationMillis = finalWarningDurationMillis(30)
        )

        assertFalse(timing.isExpired)
        assertTrue(timing.shouldStartWarningNow)
        assertEquals(0L, timing.warningDelayMillis)
        assertEquals(15_000L, timing.expiryDelayMillis)
    }

    @Test
    fun expiredSessionIsRecognizedImmediately() {
        val timing = computeSessionExpiryTiming(
            currentTimeMillis = 25_000L,
            allowedUntilMillis = 25_000L
        )

        assertTrue(timing.isExpired)
        assertFalse(timing.shouldStartWarningNow)
        assertEquals(0L, timing.warningDelayMillis)
        assertEquals(0L, timing.expiryDelayMillis)
    }

    @Test
    fun packageAExpiryTimingDoesNotUsePackageB() {
        val sessions = mapOf(
            PACKAGE_A to 70_000L,
            PACKAGE_B to 30_000L
        )

        val timingA = computeSessionExpiryTimingForPackage(
            activeUsageSessions = sessions,
            packageName = PACKAGE_A,
            currentTimeMillis = 10_000L
        )
        val timingB = computeSessionExpiryTimingForPackage(
            activeUsageSessions = sessions,
            packageName = PACKAGE_B,
            currentTimeMillis = 10_000L
        )

        assertNotNull(timingA)
        assertNotNull(timingB)
        assertEquals(60_000L, timingA!!.expiryDelayMillis)
        assertEquals(20_000L, timingB!!.expiryDelayMillis)
    }

    @Test
    fun replacingPackageASessionUpdatesOnlyPackageAExpiry() {
        val sessions = withUsageSessionForPackage(
            activeUsageSessions = mapOf(
                PACKAGE_A to 70_000L,
                PACKAGE_B to 80_000L
            ),
            packageName = PACKAGE_A,
            allowedUntilMillis = 40_000L
        )

        val timingA = computeSessionExpiryTimingForPackage(
            activeUsageSessions = sessions,
            packageName = PACKAGE_A,
            currentTimeMillis = 10_000L
        )
        val timingB = computeSessionExpiryTimingForPackage(
            activeUsageSessions = sessions,
            packageName = PACKAGE_B,
            currentTimeMillis = 10_000L
        )

        assertNotNull(timingA)
        assertNotNull(timingB)
        assertEquals(30_000L, timingA!!.expiryDelayMillis)
        assertEquals(70_000L, timingB!!.expiryDelayMillis)
    }

    @Test
    fun leavingForegroundDoesNotRemoveExpirySchedule() {
        val schedules = mapOf(PACKAGE_A to 70_000L)
        val foregroundPackage = PACKAGE_B

        assertFalse(
            shouldShowFinalWarningForPackage(
                targetPackageName = PACKAGE_A,
                currentForegroundPackageName = foregroundPackage,
                systemUiIsActive = false
            )
        )
        assertEquals(70_000L, schedules[PACKAGE_A])
    }

    @Test
    fun replacingPackageAExpiryScheduleLeavesPackageBUntouched() {
        val schedules = withExpiryScheduleForPackage(
            scheduledExpiries = mapOf(
                PACKAGE_A to 70_000L,
                PACKAGE_B to 80_000L
            ),
            packageName = PACKAGE_A,
            allowedUntilMillis = 90_000L
        )

        assertEquals(90_000L, schedules[PACKAGE_A])
        assertEquals(80_000L, schedules[PACKAGE_B])
    }

    @Test
    fun pauseOverlayEventIsNotTrackedAsForegroundWhilePromptIsVisible() {
        assertFalse(
            shouldTrackForegroundPackageForExpiry(
                eventPackageName = PAUSE_PACKAGE,
                pausePackageName = PAUSE_PACKAGE,
                sessionPromptVisible = true
            )
        )
    }

    @Test
    fun inputMethodEventIsNotTrackedAsForeground() {
        assertFalse(
            shouldTrackForegroundPackageForExpiry(
                eventPackageName = "com.example.keyboard",
                pausePackageName = PAUSE_PACKAGE,
                sessionPromptVisible = true,
                isInputMethodWindow = true
            )
        )
    }

    @Test
    fun pauseAppEventIsTrackedWhenPromptIsNotVisible() {
        assertTrue(
            shouldTrackForegroundPackageForExpiry(
                eventPackageName = PAUSE_PACKAGE,
                pausePackageName = PAUSE_PACKAGE,
                sessionPromptVisible = false
            )
        )
    }

    @Test
    fun inputMethodEventDoesNotDismissVisiblePrompt() {
        assertFalse(
            shouldDismissPromptForForegroundPackage(
                targetPackageName = PACKAGE_A,
                eventPackageName = "com.example.keyboard",
                pausePackageName = PAUSE_PACKAGE,
                isInputMethodWindow = true
            )
        )
    }

    @Test
    fun pauseOverlayEventDoesNotHideFinalWarningWhenLogicalForegroundIsTarget() {
        assertFalse(
            shouldHideFinalWarningBecauseLogicalForegroundLeftTarget(
                targetPackageName = PACKAGE_A,
                currentForegroundPackageName = PACKAGE_A
            )
        )
    }

    @Test
    fun realForegroundChangeHidesFinalWarningWhenLogicalForegroundLeavesTarget() {
        assertTrue(
            shouldHideFinalWarningBecauseLogicalForegroundLeftTarget(
                targetPackageName = PACKAGE_A,
                currentForegroundPackageName = PACKAGE_B
            )
        )
    }

    @Test
    fun normalDifferentAppDismissesVisiblePrompt() {
        assertTrue(
            shouldDismissPromptForForegroundPackage(
                targetPackageName = PACKAGE_A,
                eventPackageName = PACKAGE_B,
                pausePackageName = PAUSE_PACKAGE,
                isInputMethodWindow = false
            )
        )
    }

    @Test
    fun expiryEnforcementSuppressesPromptForExpiringPackage() {
        assertTrue(
            shouldSuppressPromptDuringExpiry(
                expiringPackageName = PACKAGE_A,
                packageName = PACKAGE_A
            )
        )
        assertFalse(
            shouldSuppressPromptDuringExpiry(
                expiringPackageName = PACKAGE_A,
                packageName = PACKAGE_B
            )
        )
    }

    @Test
    fun realForegroundChangeClearsExpiryEnforcement() {
        assertTrue(
            shouldClearExpiryEnforcementForForegroundPackage(
                expiringPackageName = PACKAGE_A,
                eventPackageName = PACKAGE_B,
                pausePackageName = PAUSE_PACKAGE,
                isSystemUiPackage = false,
                isInputMethodWindow = false
            )
        )
    }

    @Test
    fun systemUiAndImeDoNotClearExpiryEnforcement() {
        assertFalse(
            shouldClearExpiryEnforcementForForegroundPackage(
                expiringPackageName = PACKAGE_A,
                eventPackageName = "com.android.systemui",
                pausePackageName = PAUSE_PACKAGE,
                isSystemUiPackage = true,
                isInputMethodWindow = false
            )
        )
        assertFalse(
            shouldClearExpiryEnforcementForForegroundPackage(
                expiringPackageName = PACKAGE_A,
                eventPackageName = "com.example.keyboard",
                pausePackageName = PAUSE_PACKAGE,
                isSystemUiPackage = false,
                isInputMethodWindow = true
            )
        )
    }

    @Test
    fun finalWarningAcknowledgmentIsTiedToAllowedUntil() {
        val acknowledged = withFinalWarningAcknowledged(
            acknowledgedWarnings = emptyMap(),
            packageName = PACKAGE_A,
            allowedUntilMillis = 70_000L
        )

        assertTrue(
            isFinalWarningAcknowledged(
                acknowledgedWarnings = acknowledged,
                packageName = PACKAGE_A,
                allowedUntilMillis = 70_000L
            )
        )
        assertFalse(
            isFinalWarningAcknowledged(
                acknowledgedWarnings = acknowledged,
                packageName = PACKAGE_A,
                allowedUntilMillis = 90_000L
            )
        )
    }

    @Test
    fun remainingSecondsDerivesFromAbsoluteAllowedUntil() {
        val allowedUntilMillis = 30_000L

        assertEquals(
            20,
            remainingSecondsUntilExpiry(
                currentTimeMillis = 10_000L,
                allowedUntilMillis = allowedUntilMillis
            )
        )
        assertEquals(
            19,
            remainingSecondsUntilExpiry(
                currentTimeMillis = 11_500L,
                allowedUntilMillis = allowedUntilMillis
            )
        )
        assertEquals(
            0,
            remainingSecondsUntilExpiry(
                currentTimeMillis = 30_000L,
                allowedUntilMillis = allowedUntilMillis
            )
        )
    }

    companion object {
        private const val PACKAGE_A = "com.example.a"
        private const val PACKAGE_B = "com.example.b"
        private const val PAUSE_PACKAGE = "io.github.xia92.pause"
    }
}
