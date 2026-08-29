package io.github.xia92.pause

const val DEFAULT_FINAL_WARNING_DURATION_MILLIS: Long = DEFAULT_FINAL_WARNING_SECONDS * 1_000L

data class SessionExpiryTiming(
    val allowedUntilMillis: Long,
    val remainingMillis: Long,
    val warningDelayMillis: Long,
    val expiryDelayMillis: Long,
    val isExpired: Boolean,
    val shouldStartWarningNow: Boolean
)

fun computeSessionExpiryTiming(
    currentTimeMillis: Long,
    allowedUntilMillis: Long,
    warningDurationMillis: Long = DEFAULT_FINAL_WARNING_DURATION_MILLIS
): SessionExpiryTiming {
    val rawRemainingMillis = allowedUntilMillis - currentTimeMillis
    val remainingMillis = rawRemainingMillis.coerceAtLeast(0L)
    val isExpired = rawRemainingMillis <= 0L
    val warningDelayMillis = when {
        isExpired -> 0L
        remainingMillis <= warningDurationMillis -> 0L
        else -> remainingMillis - warningDurationMillis
    }

    return SessionExpiryTiming(
        allowedUntilMillis = allowedUntilMillis,
        remainingMillis = remainingMillis,
        warningDelayMillis = warningDelayMillis,
        expiryDelayMillis = remainingMillis,
        isExpired = isExpired,
        shouldStartWarningNow = !isExpired && warningDelayMillis == 0L
    )
}

fun computeSessionExpiryTimingForPackage(
    activeUsageSessions: Map<String, Long>,
    packageName: String,
    currentTimeMillis: Long,
    warningDurationMillis: Long = DEFAULT_FINAL_WARNING_DURATION_MILLIS
): SessionExpiryTiming? {
    val allowedUntilMillis = activeUsageSessions[packageName] ?: return null

    return computeSessionExpiryTiming(
        currentTimeMillis = currentTimeMillis,
        allowedUntilMillis = allowedUntilMillis,
        warningDurationMillis = warningDurationMillis
    )
}

fun remainingSecondsUntilExpiry(
    currentTimeMillis: Long,
    allowedUntilMillis: Long
): Int {
    val remainingMillis = allowedUntilMillis - currentTimeMillis
    if (remainingMillis <= 0L) return 0

    return ((remainingMillis + 999L) / 1_000L).toInt()
}

fun shouldTrackForegroundPackageForExpiry(
    eventPackageName: String,
    pausePackageName: String,
    sessionPromptVisible: Boolean,
    isInputMethodWindow: Boolean = false
): Boolean {
    return !isInputMethodWindow && !(eventPackageName == pausePackageName && sessionPromptVisible)
}

fun shouldDismissPromptForForegroundPackage(
    targetPackageName: String,
    eventPackageName: String,
    pausePackageName: String,
    isInputMethodWindow: Boolean
): Boolean {
    return eventPackageName != targetPackageName &&
        eventPackageName != pausePackageName &&
        !isInputMethodWindow
}

fun withExpiryScheduleForPackage(
    scheduledExpiries: Map<String, Long>,
    packageName: String,
    allowedUntilMillis: Long
): Map<String, Long> {
    return scheduledExpiries + (packageName to allowedUntilMillis)
}

fun shouldShowFinalWarningForPackage(
    targetPackageName: String,
    currentForegroundPackageName: String?,
    systemUiIsActive: Boolean
): Boolean {
    return !systemUiIsActive && currentForegroundPackageName == targetPackageName
}

fun shouldHideFinalWarningBecauseLogicalForegroundLeftTarget(
    targetPackageName: String?,
    currentForegroundPackageName: String?
): Boolean {
    return targetPackageName != null && currentForegroundPackageName != targetPackageName
}

fun shouldSuppressPromptDuringExpiry(
    expiringPackageName: String?,
    packageName: String
): Boolean {
    return expiringPackageName == packageName
}

fun shouldClearExpiryEnforcementForForegroundPackage(
    expiringPackageName: String?,
    eventPackageName: String,
    pausePackageName: String,
    isSystemUiPackage: Boolean,
    isInputMethodWindow: Boolean
): Boolean {
    return expiringPackageName != null &&
        eventPackageName != expiringPackageName &&
        eventPackageName != pausePackageName &&
        !isSystemUiPackage &&
        !isInputMethodWindow
}

fun isFinalWarningAcknowledged(
    acknowledgedWarnings: Map<String, Long>,
    packageName: String,
    allowedUntilMillis: Long
): Boolean {
    return acknowledgedWarnings[packageName] == allowedUntilMillis
}

fun withFinalWarningAcknowledged(
    acknowledgedWarnings: Map<String, Long>,
    packageName: String,
    allowedUntilMillis: Long
): Map<String, Long> {
    return acknowledgedWarnings + (packageName to allowedUntilMillis)
}
