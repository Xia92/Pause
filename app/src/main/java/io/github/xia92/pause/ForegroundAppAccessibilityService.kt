package io.github.xia92.pause

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ForegroundAppAccessibilityService : AccessibilityService() {
    private var lastPackageName: String? = null
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e(
            EXPIRY_TAG,
            "Service coroutine failed: exception=${exception.javaClass.name} " +
                "message=${exception.message}",
            exception
        )
    }
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + serviceExceptionHandler
    )
    private val settingsRepository by lazy {
        PauseSettingsRepository(applicationContext)
    }
    private val notificationHelper by lazy {
        SessionNotificationHelper(applicationContext)
    }
    private var sessionPromptOverlay: SessionPromptOverlay? = null
    private var visiblePromptPackageName: String? = null
    private var suspendedPromptPackageName: String? = null
    private var hiddenPromptPackageName: String? = null
    private var inputMethodWindowVisibleWhilePrompt = false
    private var systemUiIsActive = false
    private var currentForegroundPackageName: String? = null
    private var finalWarningOverlay: FinalWarningOverlay? = null
    private var finalWarningPackageName: String? = null
    private var finalWarningAllowedUntilMillis: Long? = null
    private var finalWarningLastSecond: Int? = null
    private var latestRawAccessibilityPackageName: String? = null
    private var expiringPackageName: String? = null
    private var expiryBlockOverlay: ExpiredSessionBlockOverlay? = null
    private var expiryBlockOverlayPackageName: String? = null
    private val acknowledgedFinalWarningsByPackageName = mutableMapOf<String, Long>()
    private val hapticPlayedFinalWarningsByPackageName = mutableMapOf<String, Long>()
    private val warningJobsByPackageName = mutableMapOf<String, Job>()
    private val expiryJobsByPackageName = mutableMapOf<String, Job>()
    private val scheduledAllowedUntilByPackageName = mutableMapOf<String, Long>()
    private val scheduledWarningDurationByPackageName = mutableMapOf<String, Long>()
    private var currentLanguagePreference = PauseLanguagePreference.FOLLOW_SYSTEM
    private var currentThemePreference = PauseThemePreference.FOLLOW_SYSTEM
    private var currentFinalWarningSeconds = DEFAULT_FINAL_WARNING_SECONDS
    private var currentFinalWarningDurationMillis = finalWarningDurationMillis(
        DEFAULT_FINAL_WARNING_SECONDS
    )
    private var currentWarningVibrationEnabled = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Pause accessibility service connected. Waiting for foreground app changes.")
        Log.i(
            EXPIRY_TAG,
            "AccessibilityService connected; expiry scheduler scope ready: " +
                "scopeJob=${serviceScope.coroutineContext[Job].debugText()}"
        )
        notificationHelper.logPermissionState(source = "AccessibilityService connected")
        serviceScope.launch {
            settingsRepository.migrateSingleTimeSettingsToSchedulesIfNeeded()
            restoreActiveSessionNotifications()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !event.isWindowChangeEvent()) return

        val packageName = event.packageName?.toString() ?: return
        latestRawAccessibilityPackageName = packageName
        val promptVisibleAtEventStart = sessionPromptOverlay != null
        val pauseOverlayVisibleAtEventStart = sessionPromptOverlay != null ||
            finalWarningOverlay != null ||
            expiryBlockOverlay != null
        val isInputMethodEvent = event.isInputMethodWindowEvent(
            promptVisible = promptVisibleAtEventStart
        )
        val previousForegroundPackageName = currentForegroundPackageName
        if (shouldTrackForegroundPackageForExpiry(
                eventPackageName = packageName,
                pausePackageName = applicationContext.packageName,
                sessionPromptVisible = pauseOverlayVisibleAtEventStart,
                isInputMethodWindow = isInputMethodEvent
            )
        ) {
            currentForegroundPackageName = packageName
            Log.i(
                EXPIRY_TAG,
                "Foreground package tracked for expiry: eventPackage=$packageName " +
                    "previousForeground=$previousForegroundPackageName " +
                    "currentForeground=$currentForegroundPackageName"
            )
        } else {
            Log.i(
                EXPIRY_TAG,
                "Foreground package event ignored for expiry tracking: " +
                    "eventPackage=$packageName previousForeground=$previousForegroundPackageName " +
                    "reason=${ignoredForegroundReason(packageName, isInputMethodEvent)}"
            )
        }
        val isDuplicatePackage = packageName == lastPackageName
        Log.i(
            SESSION_TAG,
            "Considering foreground package: package=$packageName " +
                "eventType=${event.eventTypeName()} lastPackage=$lastPackageName " +
                "isDuplicatePackage=$isDuplicatePackage overlayShowing=${sessionPromptOverlay != null} " +
                "visiblePromptPackage=$visiblePromptPackageName " +
                "suspendedPromptPackage=$suspendedPromptPackageName " +
                "hiddenPromptPackage=$hiddenPromptPackageName " +
                "isInputMethodEvent=$isInputMethodEvent " +
                "inputMethodWindowVisibleWhilePrompt=$inputMethodWindowVisibleWhilePrompt " +
                "expiringPackage=$expiringPackageName " +
                "expiryBlockOverlayPackage=$expiryBlockOverlayPackageName " +
                "systemUiIsActive=$systemUiIsActive " +
                "currentForegroundPackage=$currentForegroundPackageName"
        )

        if (isInputMethodEvent) {
            handleInputMethodEvent(packageName, event)
            return
        }

        if (packageName.isSystemUiPackage()) {
            handleSystemUiEvent(packageName, event)
            logForegroundPackageChange(packageName, isDuplicatePackage)
            return
        }

        handleExpiryEnforcementForegroundChange(packageName, event, isInputMethodEvent)
        handleKeyboardDismissedIfNeeded(packageName, event)
        handleVisiblePromptTargetChange(packageName, event)
        val shouldRestoreHiddenPrompt = handleHiddenPromptTargetReturn(packageName, event)
        val shouldRestoreSuspendedPrompt = handleNonSystemUiEvent(packageName, event)
        serviceScope.launch {
            maybeShowSessionPrompt(
                packageName = packageName,
                restoreSuspendedPrompt = shouldRestoreSuspendedPrompt,
                restoreHiddenPrompt = shouldRestoreHiddenPrompt
            )
        }

        logForegroundPackageChange(packageName, isDuplicatePackage)
    }

    private fun logForegroundPackageChange(packageName: String, isDuplicatePackage: Boolean) {
        if (isDuplicatePackage) {
            Log.i(
                SESSION_TAG,
                "Duplicate package event still considered by Phase 3; PauseForeground log suppressed."
            )
            return
        }

        lastPackageName = packageName
        Log.i(TAG, "Foreground app changed: $packageName")
    }

    override fun onInterrupt() {
        Log.i(TAG, "Pause accessibility service interrupted.")
    }

    override fun onDestroy() {
        Log.i(
            EXPIRY_TAG,
            "AccessibilityService destroyed; cancelling expiry scheduler: " +
                "warningJobs=${warningJobsByPackageName.size} " +
                "expiryJobs=${expiryJobsByPackageName.size}"
        )
        dismissSessionPrompt()
        hideFinalWarning(reason = "AccessibilityService destroyed")
        dismissExpiryBlockOverlay(reason = "AccessibilityService destroyed")
        cancelAllExpiryWork(reason = "AccessibilityService destroyed")
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun maybeShowSessionPrompt(
        packageName: String,
        restoreSuspendedPrompt: Boolean = false,
        restoreHiddenPrompt: Boolean = false
    ) {
        Log.i(
            SESSION_TAG,
            "Phase 3 check started: package=$packageName " +
                "restoreSuspendedPrompt=$restoreSuspendedPrompt " +
                "restoreHiddenPrompt=$restoreHiddenPrompt " +
                "suspendedPromptPackage=$suspendedPromptPackageName"
        )
        Log.i(
            RESTRICTION_TAG,
            "Evaluation started: packageName=$packageName " +
                "localDateTime=${formatRestrictionDiagnosticDateTime(System.currentTimeMillis())}"
        )

        if (packageName == applicationContext.packageName) {
            Log.i(SESSION_TAG, "Decision: no prompt; ignoring Pause package itself.")
            logRestrictionFinalAction(
                packageName = packageName,
                action = "SKIP",
                reason = "PAUSE_PACKAGE_SELF"
            )
            return
        }
        if (systemUiIsActive) {
            Log.i(
                SESSION_TAG,
                "Decision: no prompt; SystemUI is active. " +
                    "package=$packageName suspendedPromptPackage=$suspendedPromptPackageName"
            )
            logRestrictionFinalAction(
                packageName = packageName,
                action = "SKIP",
                reason = "SYSTEM_UI_ACTIVE"
            )
            return
        }
        if (
            shouldSuppressPromptDuringExpiry(
                expiringPackageName = expiringPackageName,
                packageName = packageName
            ) || expiryBlockOverlayPackageName == packageName
        ) {
            Log.i(
                SESSION_TAG,
                "Decision: no prompt; expiry enforcement is in progress. " +
                    "package=$packageName expiringPackage=$expiringPackageName " +
                    "expiryBlockOverlayPackage=$expiryBlockOverlayPackageName"
            )
            Log.i(
                EXPIRY_TAG,
                "Normal prompt logic suppressed during expiry enforcement: " +
                    "package=$packageName logicalForeground=$currentForegroundPackageName " +
                    "rawLatestPackage=$latestRawAccessibilityPackageName " +
                    "expiringPackage=$expiringPackageName"
            )
            logRestrictionFinalAction(
                packageName = packageName,
                action = "SKIP",
                reason = "EXPIRY_ENFORCEMENT_IN_PROGRESS"
            )
            return
        }
        if (sessionPromptOverlay != null) {
            Log.i(SESSION_TAG, "Decision: no prompt; a session prompt is already showing.")
            logRestrictionFinalAction(
                packageName = packageName,
                action = "SKIP",
                reason = "SESSION_PROMPT_ALREADY_SHOWING"
            )
            return
        }

        val nowMillis = System.currentTimeMillis()
        val currentMinutes = currentLocalMinutesSinceMidnight()
        val currentTimeText = formatMinutesSinceMidnight(currentMinutes)
        val settings = runCatching {
            settingsRepository.settings.first()
        }.getOrElse { exception ->
            Log.e(
                SESSION_TAG,
                "Settings read failed: package=$packageName " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
            Log.e(
                RESTRICTION_TAG,
                "Settings read failed before restriction evaluation: packageName=$packageName " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
            logRestrictionFinalAction(
                packageName = packageName,
                action = "SKIP",
                reason = "SETTINGS_READ_FAILED"
            )
            return
        }
        applyUserPreferences(settings, source = "foreground check")

        val currentWeekday = currentLocalWeekday()
        val savedAllowedUntilMillis = savedAllowedUntilForPackage(
            activeUsageSessions = settings.activeUsageSessions,
            packageName = packageName
        )
        val savedSessionIsValid = savedAllowedUntilMillis != null &&
            hasValidSessionForPackage(
                activeUsageSessions = settings.activeUsageSessions,
                packageName = packageName,
                currentTimeMillis = nowMillis
            )
        val systemExcludedPackages = runCatching {
            buildWhitelistSystemExcludedPackages(applicationContext)
        }.getOrElse { exception ->
            Log.e(
                RESTRICTION_TAG,
                "System exclusion lookup failed before restriction evaluation: " +
                    "packageName=$packageName exception=${exception.javaClass.name} " +
                    "message=${exception.message}",
                exception
            )
            logRestrictionFinalAction(
                packageName = packageName,
                action = "SKIP",
                reason = "SYSTEM_EXCLUSION_LOOKUP_FAILED",
                activeSessionExists = savedAllowedUntilMillis != null
            )
            return
        }
        val restrictionEvaluation = runCatching {
            evaluatePackageRestriction(
                schedules = settings.restrictionSchedules,
                packageName = packageName,
                systemExcludedPackages = systemExcludedPackages,
                currentWeekday = currentWeekday,
                currentMinutesSinceMidnight = currentMinutes
            )
        }.getOrElse { exception ->
            Log.e(
                RESTRICTION_TAG,
                "Restriction evaluation failed: packageName=$packageName " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
            logRestrictionFinalAction(
                packageName = packageName,
                action = "SKIP",
                reason = "RESTRICTION_EVALUATION_FAILED",
                activeSessionExists = savedAllowedUntilMillis != null
            )
            return
        }
        logRestrictionEvaluationDetails(
            packageName = packageName,
            nowMillis = nowMillis,
            currentWeekday = currentWeekday,
            currentMinutes = currentMinutes,
            schedules = settings.restrictionSchedules,
            systemExcludedPackages = systemExcludedPackages,
            restrictionEvaluation = restrictionEvaluation,
            activeSessionAllowedUntilMillis = savedAllowedUntilMillis,
            activeSessionIsValid = savedSessionIsValid
        )
        val matchedSchedule = restrictionEvaluation.restrictingSchedule
        val activeScheduleIds = restrictionEvaluation.activeSchedules.joinToString(
            separator = ","
        ) { schedule -> schedule.id }
        Log.i(
            SCHEDULE_TAG,
            "Current schedule match result: schedules=${settings.restrictionSchedules.size} " +
                "nowMillis=$nowMillis weekday=${currentWeekday.shortLabel} " +
                "currentTime=$currentTimeText activeSchedules=${restrictionEvaluation.activeSchedules.size} " +
                "activeScheduleIds=$activeScheduleIds package=$packageName " +
                "isRestrictedNow=${restrictionEvaluation.isRestricted} " +
                "matchedScheduleId=${matchedSchedule?.id} " +
                "matchedMode=${matchedSchedule?.appRestrictionMode?.storageValue} " +
                "systemExcluded=${packageName in systemExcludedPackages}"
        )
        Log.i(
            SESSION_TAG,
            "Restricted schedule check: nowMillis=$nowMillis currentTime=$currentTimeText " +
                "weekday=${currentWeekday.shortLabel} schedules=${settings.restrictionSchedules.size} " +
                "currentMinutes=$currentMinutes " +
                "isRestrictedNow=${restrictionEvaluation.isRestricted} " +
                "matchedScheduleId=${matchedSchedule?.id}"
        )

        if (!restrictionEvaluation.isRestricted) {
            cleanupPackageSessionThatIsNoLongerRestricted(
                packageName = packageName,
                allowedUntilMillis = settings.activeUsageSessions[packageName]
            )
            Log.i(
                SESSION_TAG,
                "Decision: no prompt; active schedules do not restrict this package."
            )
            logRestrictionFinalAction(
                packageName = packageName,
                action = "SKIP",
                reason = "ACTIVE_SCHEDULES_DO_NOT_RESTRICT_PACKAGE",
                finalShouldRestrict = false,
                activeSessionExists = savedAllowedUntilMillis != null
            )
            clearSuspendedPromptAfterRestorationSkip(
                packageName = packageName,
                restoreSuspendedPrompt = restoreSuspendedPrompt,
                reason = "active schedules do not restrict this package"
            )
            clearHiddenPromptAfterRestorationSkip(
                packageName = packageName,
                restoreHiddenPrompt = restoreHiddenPrompt,
                reason = "active schedules do not restrict this package"
            )
            return
        }

        Log.i(
            SESSION_TAG,
            "Restricted app detected: package=$packageName " +
                "scheduleId=${matchedSchedule?.id} mode=${matchedSchedule?.appRestrictionMode?.storageValue}"
        )

        val allowedUntilMillis = savedAllowedUntilMillis
        Log.i(
            SESSION_TAG,
            "Saved session check: package=$packageName " +
                "exists=${allowedUntilMillis != null} allowedUntil=$allowedUntilMillis"
        )
        if (allowedUntilMillis != null) {
            val isValid = savedSessionIsValid
            Log.i(
                SESSION_TAG,
                "Exact package session status: package=$packageName valid=$isValid " +
                    "expired=${!isValid} nowMillis=$nowMillis allowedUntil=$allowedUntilMillis"
            )

            if (isValid) {
                Log.i(
                    SESSION_TAG,
                    "Active valid session detected: $packageName allowedUntil=$allowedUntilMillis"
                )
                logRestrictionFinalAction(
                    packageName = packageName,
                    action = "SKIP",
                    reason = "ACTIVE_VALID_SESSION_EXISTS",
                    finalShouldRestrict = true,
                    activeSessionExists = true
                )
                scheduleExpiryWorkForPackageIfNeeded(
                    packageName = packageName,
                    allowedUntilMillis = allowedUntilMillis,
                    source = "valid session observed"
                )
                showOrUpdateFinalWarningIfNeeded(
                    packageName = packageName,
                    allowedUntilMillis = allowedUntilMillis,
                    source = "foreground app returned with active session"
                )
                clearSuspendedPromptAfterRestorationSkip(
                    packageName = packageName,
                    restoreSuspendedPrompt = restoreSuspendedPrompt,
                    reason = "valid session now exists"
                )
                clearHiddenPromptAfterRestorationSkip(
                    packageName = packageName,
                    restoreHiddenPrompt = restoreHiddenPrompt,
                    reason = "valid session now exists"
                )
                return
            }

            Log.i(SESSION_TAG, "Session expired: $packageName allowedUntil=$allowedUntilMillis")
            cancelExpiryWorkForPackage(
                packageName = packageName,
                reason = "expired session noticed during foreground check"
            )
            runCatching {
                settingsRepository.removeUsageSession(packageName)
            }.onFailure { exception ->
                Log.e(
                    RESTRICTION_TAG,
                    "Expired session cleanup failed before prompt: packageName=$packageName " +
                        "operation=removeUsageSession exception=${exception.javaClass.name} " +
                        "message=${exception.message}",
                    exception
                )
            }
            runCatching {
                notificationHelper.removeSessionNotification(packageName)
            }.onFailure { exception ->
                Log.e(
                    RESTRICTION_TAG,
                    "Expired session cleanup failed before prompt: packageName=$packageName " +
                        "operation=removeSessionNotification exception=${exception.javaClass.name} " +
                        "message=${exception.message}",
                    exception
                )
            }
        }

        if (sessionPromptOverlay != null) {
            Log.i(SESSION_TAG, "Decision: no prompt; a session prompt appeared during checks.")
            logRestrictionFinalAction(
                packageName = packageName,
                action = "SKIP",
                reason = "SESSION_PROMPT_APPEARED_DURING_CHECKS",
                finalShouldRestrict = true,
                activeSessionExists = allowedUntilMillis != null
            )
            return
        }

        Log.i(SESSION_TAG, "Decision: show prompt for package=$packageName")
        logRestrictionFinalAction(
            packageName = packageName,
            action = "SHOW_PROMPT",
            reason = "PACKAGE_RESTRICTED_NO_VALID_SESSION",
            finalShouldRestrict = true,
            activeSessionExists = allowedUntilMillis != null
        )
        if (restoreSuspendedPrompt) {
            Log.i(SESSION_TAG, "Restoring pending prompt: package=$packageName")
        }
        if (restoreHiddenPrompt) {
            Log.i(SESSION_TAG, "Prompt restored after returning to target app: package=$packageName")
        }
        showSessionPrompt(
            packageName = packageName,
            languagePreference = settings.languagePreference,
            themePreference = settings.themePreference
        )
    }

    private fun showSessionPrompt(
        packageName: String,
        languagePreference: PauseLanguagePreference,
        themePreference: PauseThemePreference
    ) {
        Log.i(OVERLAY_TAG, "showPrompt requested for package=$packageName")
        val appLabel = loadAppLabel(packageName)

        Log.i(SESSION_TAG, "Prompt show requested: package=$packageName appLabel=$appLabel")
        Log.i(
            OVERLAY_TAG,
            "showPrompt requested for package=$packageName appLabel=$appLabel"
        )
        val overlay = SessionPromptOverlay(
            service = this,
            appLabel = appLabel,
            packageName = packageName,
            languagePreference = languagePreference,
            themePreference = themePreference,
            onDurationSelected = { durationMinutes ->
                createSessionAndDismissPrompt(
                    packageName = packageName,
                    durationMinutes = durationMinutes
                )
            },
            onLeave = {
                Log.i(SESSION_TAG, "Prompt cancelled, returning Home: $packageName")
                dismissSessionPrompt()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        )

        if (overlay.show()) {
            sessionPromptOverlay = overlay
            visiblePromptPackageName = packageName
            if (suspendedPromptPackageName == packageName) {
                suspendedPromptPackageName = null
            }
            if (hiddenPromptPackageName == packageName) {
                hiddenPromptPackageName = null
            }
            Log.i(SESSION_TAG, "Prompt shown: $packageName")
            Log.i(OVERLAY_TAG, "showPrompt succeeded for package=$packageName")
        } else {
            Log.e(SESSION_TAG, "Prompt not shown because overlay creation failed: $packageName")
            Log.e(OVERLAY_TAG, "showPrompt failed for package=$packageName")
        }
    }

    private fun createSessionAndDismissPrompt(packageName: String, durationMinutes: Int) {
        serviceScope.launch {
            val createdAtMillis = System.currentTimeMillis()
            val allowedUntilMillis = createAllowedUntilMillis(
                currentTimeMillis = createdAtMillis,
                durationMinutes = durationMinutes
            )
            currentForegroundPackageName = packageName
            Log.i(
                EXPIRY_TAG,
                "Session selected from prompt; foreground restored to target package: " +
                    "package=$packageName durationMinutes=$durationMinutes " +
                    "createdAt=$createdAtMillis allowedUntil=$allowedUntilMillis"
            )

            settingsRepository.createUsageSession(
                packageName = packageName,
                allowedUntilMillis = allowedUntilMillis
            )
            Log.i(
                EXPIRY_TAG,
                "Session persisted before expiry scheduling: " +
                    "package=$packageName allowedUntil=$allowedUntilMillis"
            )
            notificationHelper.showSessionNotification(
                packageName = packageName,
                appLabel = loadAppLabel(packageName),
                allowedUntilMillis = allowedUntilMillis,
                languagePreference = currentLanguagePreference
            )
            scheduleExpiryWorkForPackage(
                packageName = packageName,
                allowedUntilMillis = allowedUntilMillis,
                source = "session created"
            )
            Log.i(
                SESSION_TAG,
                "Session created: $packageName durationMinutes=$durationMinutes allowedUntil=$allowedUntilMillis"
            )
            dismissSessionPrompt()
        }
    }

    private fun dismissSessionPrompt() {
        dismissVisiblePromptOverlay()
        suspendedPromptPackageName = null
        hiddenPromptPackageName = null
    }

    private fun dismissVisiblePromptOverlay() {
        sessionPromptOverlay?.dismiss()
        sessionPromptOverlay = null
        visiblePromptPackageName = null
        inputMethodWindowVisibleWhilePrompt = false
    }

    private fun handleSystemUiEvent(packageName: String, event: AccessibilityEvent) {
        systemUiIsActive = true
        Log.i(
            SESSION_TAG,
            "SystemUI window event detected: ${event.windowDebugText()} " +
                "visiblePromptPackage=$visiblePromptPackageName " +
                "suspendedPromptPackage=$suspendedPromptPackageName"
        )
        hideFinalWarning(reason = "SystemUI visible")

        val targetPackageName = visiblePromptPackageName
        if (sessionPromptOverlay == null || targetPackageName == null) {
            if (suspendedPromptPackageName == null) {
                Log.i(
                    SESSION_TAG,
                    "SystemUI detected; no visible prompt to suspend: " +
                        "systemPackage=$packageName"
                )
            } else {
                Log.i(
                    SESSION_TAG,
                    "SystemUI detected; prompt is already suspended: " +
                        "systemPackage=$packageName pendingPackage=$suspendedPromptPackageName"
                )
            }
            return
        }

        Log.i(
            SESSION_TAG,
            "SystemUI detected; suspending prompt: " +
                "systemPackage=$packageName targetPackage=$targetPackageName " +
                "event=${event.windowDebugText()}"
        )
        suspendedPromptPackageName = targetPackageName
        dismissVisiblePromptOverlay()
        Log.i(
            SESSION_TAG,
            "Prompt overlay temporarily removed for SystemUI: " +
                "targetPackage=$targetPackageName"
        )
    }

    private fun handleNonSystemUiEvent(
        packageName: String,
        event: AccessibilityEvent
    ): Boolean {
        val pendingPackageName = suspendedPromptPackageName

        if (systemUiIsActive) {
            systemUiIsActive = false
            Log.i(
                SESSION_TAG,
                "SystemUI dismissed or no longer active: " +
                    "foregroundPackage=$packageName pendingPackage=$pendingPackageName " +
                    "event=${event.windowDebugText()}"
            )
        }

        hideFinalWarningIfLogicalForegroundLeftTarget(
            rawEventPackageName = packageName,
            event = event
        )

        if (pendingPackageName == null) {
            return false
        }

        return if (packageName == pendingPackageName) {
            Log.i(
                SESSION_TAG,
                "SystemUI dismissed; target package foreground again: " +
                    "targetPackage=$packageName event=${event.windowDebugText()}"
            )
            true
        } else {
            Log.i(
                SESSION_TAG,
                "SystemUI dismissed; foreground package is not pending target: " +
                    "foregroundPackage=$packageName pendingPackage=$pendingPackageName"
            )
            false
        }
    }

    private fun handleInputMethodEvent(packageName: String, event: AccessibilityEvent) {
        if (sessionPromptOverlay != null) {
            inputMethodWindowVisibleWhilePrompt = true
            Log.i(
                SESSION_TAG,
                "IME window detected; preserving target foreground package: " +
                    "imePackage=$packageName logicalForeground=$currentForegroundPackageName " +
                    "visiblePromptPackage=$visiblePromptPackageName " +
                    "event=${event.windowDebugText()} " +
                    "eventWindowType=${event.windowTypeName()} " +
                    "activeInputMethodPackage=${activeInputMethodPackageName()} " +
                    "inputMethodWindows=${inputMethodWindowsDebugText()}"
            )
            Log.i(
                SESSION_TAG,
                "Ignoring IME package for logical foreground tracking: " +
                    "imePackage=$packageName targetPackage=$visiblePromptPackageName"
            )
            Log.i(
                SESSION_TAG,
                "IME event ignored for prompt decision; prompt remains active: " +
                    "targetPackage=$visiblePromptPackageName"
            )
            return
        }

        Log.i(
            SESSION_TAG,
            "IME window detected without visible session prompt; ignoring as real app switch: " +
                "imePackage=$packageName logicalForeground=$currentForegroundPackageName " +
                "event=${event.windowDebugText()} eventWindowType=${event.windowTypeName()} " +
                "activeInputMethodPackage=${activeInputMethodPackageName()} " +
                "inputMethodWindows=${inputMethodWindowsDebugText()}"
        )
    }

    private fun handleKeyboardDismissedIfNeeded(packageName: String, event: AccessibilityEvent) {
        if (!inputMethodWindowVisibleWhilePrompt || sessionPromptOverlay == null) return
        if (hasInputMethodWindow()) return

        val targetPackageName = visiblePromptPackageName ?: return
        if (packageName == targetPackageName || packageName == applicationContext.packageName) {
            inputMethodWindowVisibleWhilePrompt = false
            Log.i(
                SESSION_TAG,
                "Keyboard dismissed; prompt remains active: " +
                    "targetPackage=$targetPackageName foregroundPackage=$packageName " +
                    "event=${event.windowDebugText()}"
            )
        }
    }

    private fun handleVisiblePromptTargetChange(
        packageName: String,
        event: AccessibilityEvent
    ): Boolean {
        val targetPackageName = visiblePromptPackageName ?: return false
        if (sessionPromptOverlay == null) return false

        if (packageName == targetPackageName) {
            Log.i(
                SESSION_TAG,
                "Target app still foreground while prompt is visible: " +
                    "targetPackage=$targetPackageName event=${event.windowDebugText()}"
            )
            return false
        }

        if (packageName == applicationContext.packageName) {
            Log.i(
                SESSION_TAG,
                "Pause package event ignored while prompt is visible: " +
                    "targetPackage=$targetPackageName event=${event.windowDebugText()}"
            )
            return false
        }

        val shouldDismissPrompt = shouldDismissPromptForForegroundPackage(
            targetPackageName = targetPackageName,
            eventPackageName = packageName,
            pausePackageName = applicationContext.packageName,
            isInputMethodWindow = false
        )
        if (!shouldDismissPrompt) {
            return false
        }

        hiddenPromptPackageName = targetPackageName
        Log.i(
            SESSION_TAG,
            "Real foreground app changed away from target; removing prompt: " +
                "targetPackage=$targetPackageName foregroundPackage=$packageName " +
                "event=${event.windowDebugText()}"
        )
        Log.i(
            SESSION_TAG,
            "Target app left foreground; removing prompt: " +
                "targetPackage=$targetPackageName foregroundPackage=$packageName"
        )
        dismissVisiblePromptOverlay()
        Log.i(
            SESSION_TAG,
            "Prompt removed because foreground package changed to $packageName: " +
                "pendingTargetPackage=$hiddenPromptPackageName"
        )

        return false
    }

    private fun handleHiddenPromptTargetReturn(
        packageName: String,
        event: AccessibilityEvent
    ): Boolean {
        val targetPackageName = hiddenPromptPackageName ?: return false

        return if (packageName == targetPackageName) {
            Log.i(
                SESSION_TAG,
                "Target app foreground again; re-evaluating prompt: " +
                    "targetPackage=$targetPackageName event=${event.windowDebugText()}"
            )
            true
        } else {
            false
        }
    }

    private fun clearSuspendedPromptAfterRestorationSkip(
        packageName: String,
        restoreSuspendedPrompt: Boolean,
        reason: String
    ) {
        if (restoreSuspendedPrompt && suspendedPromptPackageName == packageName) {
            Log.i(
                SESSION_TAG,
                "Pending prompt restoration skipped because $reason: package=$packageName"
            )
            suspendedPromptPackageName = null
        }
    }

    private fun clearHiddenPromptAfterRestorationSkip(
        packageName: String,
        restoreHiddenPrompt: Boolean,
        reason: String
    ) {
        if (restoreHiddenPrompt && hiddenPromptPackageName == packageName) {
            Log.i(
                SESSION_TAG,
                "Hidden prompt restoration skipped because $reason: package=$packageName"
            )
            hiddenPromptPackageName = null
        }
    }

    private fun logRestrictionEvaluationDetails(
        packageName: String,
        nowMillis: Long,
        currentWeekday: PauseWeekday,
        currentMinutes: Int,
        schedules: List<RestrictionSchedule>,
        systemExcludedPackages: Set<String>,
        restrictionEvaluation: PackageRestrictionEvaluation,
        activeSessionAllowedUntilMillis: Long?,
        activeSessionIsValid: Boolean
    ) {
        val enabledScheduleCount = schedules.count { schedule -> schedule.enabled }
        Log.i(
            RESTRICTION_TAG,
            "Evaluation result: packageName=$packageName " +
                "localDateTime=${formatRestrictionDiagnosticDateTime(nowMillis)} " +
                "weekday=${currentWeekday.shortLabel} " +
                "currentTime=${formatMinutesSinceMidnight(currentMinutes)} " +
                "enabledScheduleCount=$enabledScheduleCount " +
                "activeScheduleCount=${restrictionEvaluation.activeSchedules.size} " +
                "finalShouldRestrict=${restrictionEvaluation.isRestricted} " +
                "activeSessionExists=${activeSessionAllowedUntilMillis != null} " +
                "activeSessionValid=$activeSessionIsValid " +
                "activeSessionAllowedUntil=$activeSessionAllowedUntilMillis " +
                "systemExcluded=${packageName in systemExcludedPackages}"
        )

        if (restrictionEvaluation.activeSchedules.isEmpty()) {
            Log.i(
                RESTRICTION_TAG,
                "Active schedule detail: packageName=$packageName none"
            )
            return
        }

        restrictionEvaluation.activeSchedules.forEach { schedule ->
            val currentPackageSelected = packageName in schedule.selectedPackages
            val scheduleRestricts = schedule.restrictsPackage(
                packageName = packageName,
                systemExcludedPackages = systemExcludedPackages
            )
            Log.i(
                RESTRICTION_TAG,
                "Active schedule detail: packageName=$packageName " +
                    "scheduleId=${schedule.id} " +
                    "start=${formatMinutesSinceMidnight(schedule.startMinutesOfDay)} " +
                    "end=${formatMinutesSinceMidnight(schedule.endMinutesOfDay)} " +
                    "weekdays=${schedule.weekdays.toRestrictionDiagnosticWeekdayText()} " +
                    "mode=${schedule.appRestrictionMode.storageValue} " +
                    "selectedPackagesCount=${schedule.selectedPackages.size} " +
                    "currentPackageSelected=$currentPackageSelected " +
                    "scheduleRestricts=$scheduleRestricts"
            )
        }
    }

    private fun logRestrictionFinalAction(
        packageName: String,
        action: String,
        reason: String,
        finalShouldRestrict: Boolean? = null,
        activeSessionExists: Boolean? = null
    ) {
        Log.i(
            RESTRICTION_TAG,
            "Final action: packageName=$packageName action=$action reason=$reason " +
                "finalShouldRestrict=$finalShouldRestrict " +
                "activeSessionExists=$activeSessionExists"
        )
    }

    private fun formatRestrictionDiagnosticDateTime(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .format(Date(timeMillis))
    }

    private fun Set<PauseWeekday>.toRestrictionDiagnosticWeekdayText(): String {
        if (isEmpty()) return "none"

        return sortedBy { weekday -> weekday.ordinal }
            .joinToString(separator = ",") { weekday -> weekday.shortLabel }
    }

    private fun applyUserPreferences(settings: PauseSettings, source: String) {
        currentLanguagePreference = settings.languagePreference
        currentThemePreference = settings.themePreference
        currentFinalWarningSeconds = settings.finalWarningSeconds
        currentFinalWarningDurationMillis = finalWarningDurationMillis(settings.finalWarningSeconds)
        currentWarningVibrationEnabled = settings.warningVibrationEnabled
        Log.i(
            EXPIRY_TAG,
            "Warning preferences loaded: source=$source " +
                "language=${settings.languagePreference.storageValue} " +
                "theme=${settings.themePreference.storageValue} " +
                "warningSeconds=${settings.finalWarningSeconds} " +
                "warningDurationMillis=$currentFinalWarningDurationMillis " +
                "vibrationEnabled=${settings.warningVibrationEnabled}"
        )
    }

    private suspend fun cleanupPackageSessionThatIsNoLongerRestricted(
        packageName: String,
        allowedUntilMillis: Long?
    ) {
        if (allowedUntilMillis == null) return

        Log.i(
            SCHEDULE_TAG,
            "Package no longer restricted by active schedules; cleaning stale session: " +
                "package=$packageName allowedUntil=$allowedUntilMillis"
        )
        if (finalWarningPackageName == packageName) {
            hideFinalWarning(reason = "package no longer restricted by active schedules")
        }
        cancelExpiryWorkForPackage(
            packageName = packageName,
            reason = "package no longer restricted by active schedules"
        )
        settingsRepository.removeUsageSession(packageName)
        notificationHelper.removeSessionNotification(packageName)
    }

    private fun scheduleExpiryWorkForPackageIfNeeded(
        packageName: String,
        allowedUntilMillis: Long,
        source: String
    ) {
        if (scheduledAllowedUntilByPackageName[packageName] == allowedUntilMillis &&
            scheduledWarningDurationByPackageName[packageName] == currentFinalWarningDurationMillis
        ) {
            Log.i(
                EXPIRY_TAG,
                "Expiry schedule already exists for package: package=$packageName " +
                    "allowedUntil=$allowedUntilMillis source=$source " +
                    "warningDurationMillis=$currentFinalWarningDurationMillis " +
                    "warningJob=${warningJobsByPackageName[packageName].debugText()} " +
                    "expiryJob=${expiryJobsByPackageName[packageName].debugText()}"
            )
            return
        }

        scheduleExpiryWorkForPackage(
            packageName = packageName,
            allowedUntilMillis = allowedUntilMillis,
            source = source
        )
    }

    private fun scheduleExpiryWorkForPackage(
        packageName: String,
        allowedUntilMillis: Long,
        source: String
    ) {
        val previousAllowedUntilMillis = scheduledAllowedUntilByPackageName[packageName]
        if (previousAllowedUntilMillis != null) {
            cancelExpiryWorkForPackage(
                packageName = packageName,
                reason = "schedule replaced; previousAllowedUntil=$previousAllowedUntilMillis"
            )
        }

        val nowMillis = System.currentTimeMillis()
        val warningDurationMillis = currentFinalWarningDurationMillis
        val warningSeconds = currentFinalWarningSeconds
        val languagePreference = currentLanguagePreference
        val themePreference = currentThemePreference
        val warningVibrationEnabled = currentWarningVibrationEnabled

        val timing = computeSessionExpiryTiming(
            currentTimeMillis = nowMillis,
            allowedUntilMillis = allowedUntilMillis,
            warningDurationMillis = warningDurationMillis
        )
        val warningTargetMillis = allowedUntilMillis - warningDurationMillis
        Log.i(
            EXPIRY_TAG,
            "Scheduling expiry: package=$packageName source=$source now=$nowMillis " +
                "allowedUntil=$allowedUntilMillis remaining=${timing.remainingMillis} " +
                "warningSeconds=$warningSeconds " +
                "warningDurationMillis=$warningDurationMillis " +
                "warningTarget=$warningTargetMillis warningDelay=${timing.warningDelayMillis} " +
                "expiryDelay=${timing.expiryDelayMillis} " +
                "existingWarningJob=${warningJobsByPackageName[packageName].debugText()} " +
                "existingExpiryJob=${expiryJobsByPackageName[packageName].debugText()}"
        )

        if (timing.isExpired) {
            Log.i(
                EXPIRY_TAG,
                "Expired session recognized immediately while scheduling: " +
                    "package=$packageName source=$source allowedUntil=$allowedUntilMillis " +
                    "nowMillis=$nowMillis"
            )
            serviceScope.launch {
                handleExactExpiry(
                    packageName = packageName,
                    allowedUntilMillis = allowedUntilMillis,
                    source = "already expired while scheduling"
                )
            }
            return
        }

        scheduledAllowedUntilByPackageName[packageName] = allowedUntilMillis
        scheduledWarningDurationByPackageName[packageName] = warningDurationMillis
        Log.i(
            EXPIRY_TAG,
            "Expiry schedule created: package=$packageName source=$source " +
                "allowedUntil=$allowedUntilMillis nowMillis=$nowMillis " +
                "expiryDelayMillis=${timing.expiryDelayMillis}"
        )
        Log.i(
            EXPIRY_TAG,
            "Warning schedule created: package=$packageName source=$source " +
                "allowedUntil=$allowedUntilMillis warningDelayMillis=${timing.warningDelayMillis} " +
                "warningDurationMillis=$warningDurationMillis " +
                "shouldStartWarningNow=${timing.shouldStartWarningNow}"
        )

        val warningJob = serviceScope.launch {
            Log.i(
                EXPIRY_TAG,
                "Warning job started waiting: package=$packageName " +
                    "allowedUntil=$allowedUntilMillis delayMillis=${timing.warningDelayMillis} " +
                    "job=${coroutineContext[Job].debugText()}"
            )
            if (timing.warningDelayMillis > 0L) {
                delay(timing.warningDelayMillis)
            }
            Log.i(
                EXPIRY_TAG,
                "Warning delay completed: package=$packageName " +
                    "allowedUntil=$allowedUntilMillis now=${System.currentTimeMillis()}"
            )
            runFinalWarningLoop(
                packageName = packageName,
                allowedUntilMillis = allowedUntilMillis,
                warningDurationMillis = warningDurationMillis,
                warningSeconds = warningSeconds,
                languagePreference = languagePreference,
                themePreference = themePreference,
                vibrationEnabled = warningVibrationEnabled
            )
        }
        warningJob.invokeOnCompletion { cause ->
            Log.i(
                EXPIRY_TAG,
                "Warning job completed: package=$packageName " +
                    "allowedUntil=$allowedUntilMillis cancelled=${warningJob.isCancelled} " +
                    "completed=${warningJob.isCompleted} cause=${cause?.javaClass?.name} " +
                    "message=${cause?.message}"
            )
        }

        val expiryJob = serviceScope.launch {
            Log.i(
                EXPIRY_TAG,
                "Expiry job started waiting: package=$packageName " +
                    "allowedUntil=$allowedUntilMillis delayMillis=${timing.expiryDelayMillis} " +
                    "job=${coroutineContext[Job].debugText()}"
            )
            if (timing.expiryDelayMillis > 0L) {
                delay(timing.expiryDelayMillis)
            }
            Log.i(
                EXPIRY_TAG,
                "Exact expiry delay completed: package=$packageName " +
                    "allowedUntil=$allowedUntilMillis now=${System.currentTimeMillis()}"
            )
            handleExactExpiry(
                packageName = packageName,
                allowedUntilMillis = allowedUntilMillis,
                source = "scheduled exact expiry"
            )
        }
        expiryJob.invokeOnCompletion { cause ->
            Log.i(
                EXPIRY_TAG,
                "Expiry job completed: package=$packageName " +
                    "allowedUntil=$allowedUntilMillis cancelled=${expiryJob.isCancelled} " +
                    "completed=${expiryJob.isCompleted} cause=${cause?.javaClass?.name} " +
                    "message=${cause?.message}"
            )
        }

        warningJobsByPackageName[packageName] = warningJob
        expiryJobsByPackageName[packageName] = expiryJob
        Log.i(
            EXPIRY_TAG,
            "Expiry jobs created: package=$packageName " +
                "warningJobCreated=${warningJobsByPackageName[packageName] != null} " +
                "expiryJobCreated=${expiryJobsByPackageName[packageName] != null} " +
                "warningJob=${warningJob.debugText()} expiryJob=${expiryJob.debugText()}"
        )
    }

    private suspend fun runFinalWarningLoop(
        packageName: String,
        allowedUntilMillis: Long,
        warningDurationMillis: Long,
        warningSeconds: Int,
        languagePreference: PauseLanguagePreference,
        themePreference: PauseThemePreference,
        vibrationEnabled: Boolean
    ) {
        val startStatus = loadSavedSessionStatus(
            packageName = packageName,
            allowedUntilMillis = allowedUntilMillis,
            currentTimeMillis = System.currentTimeMillis()
        ) ?: return
        val startRemainingSeconds = remainingSecondsUntilExpiry(
            currentTimeMillis = System.currentTimeMillis(),
            allowedUntilMillis = allowedUntilMillis
        )
        Log.i(
            EXPIRY_TAG,
            "Warning start check: targetPackage=$packageName " +
                "currentForeground=$currentForegroundPackageName " +
                "targetForeground=${currentForegroundPackageName == packageName} " +
                "systemUiIsActive=$systemUiIsActive sessionExists=${startStatus.exists} " +
                "sessionMatches=${startStatus.matchesScheduledSession} " +
                "sessionValid=${startStatus.isValid} remainingSeconds=$startRemainingSeconds " +
                "warningSeconds=$warningSeconds vibrationEnabled=$vibrationEnabled"
        )

        if (!startStatus.matchesScheduledSession || !startStatus.isValid) {
            Log.i(
                EXPIRY_TAG,
                "Warning start skipped because saved session is not the scheduled valid session: " +
                    "package=$packageName allowedUntil=$allowedUntilMillis " +
                    "savedAllowedUntil=${startStatus.savedAllowedUntilMillis}"
            )
            return
        }

        Log.i(
            EXPIRY_TAG,
            "Warning started: package=$packageName allowedUntil=$allowedUntilMillis"
        )

        while (true) {
            val nowMillis = System.currentTimeMillis()
            val remainingSeconds = remainingSecondsUntilExpiry(
                currentTimeMillis = nowMillis,
                allowedUntilMillis = allowedUntilMillis
            )
            Log.i(
                EXPIRY_TAG,
                "Warning countdown tick: package=$packageName " +
                    "remainingSeconds=$remainingSeconds currentForeground=$currentForegroundPackageName " +
                    "targetForeground=${currentForegroundPackageName == packageName} " +
                    "systemUiIsActive=$systemUiIsActive"
            )
            if (remainingSeconds <= 0) {
                hideFinalWarningForPackage(
                    packageName = packageName,
                    reason = "session reached expiry"
                )
                return
            }

            showOrUpdateFinalWarningIfNeeded(
                packageName = packageName,
                allowedUntilMillis = allowedUntilMillis,
                warningDurationMillis = warningDurationMillis,
                languagePreference = languagePreference,
                themePreference = themePreference,
                vibrationEnabled = vibrationEnabled,
                source = "scheduled warning tick"
            )

            val delayMillis = (allowedUntilMillis - System.currentTimeMillis())
                .coerceAtMost(1_000L)
                .coerceAtLeast(0L)
            if (delayMillis == 0L) {
                hideFinalWarningForPackage(
                    packageName = packageName,
                    reason = "session reached expiry"
                )
                return
            }
            delay(delayMillis)
        }
    }

    private fun showOrUpdateFinalWarningIfNeeded(
        packageName: String,
        allowedUntilMillis: Long,
        warningDurationMillis: Long = currentFinalWarningDurationMillis,
        languagePreference: PauseLanguagePreference = currentLanguagePreference,
        themePreference: PauseThemePreference = currentThemePreference,
        vibrationEnabled: Boolean = currentWarningVibrationEnabled,
        source: String
    ) {
        val nowMillis = System.currentTimeMillis()
        val timing = computeSessionExpiryTiming(
            currentTimeMillis = nowMillis,
            allowedUntilMillis = allowedUntilMillis,
            warningDurationMillis = warningDurationMillis
        )

        if (timing.isExpired || timing.remainingMillis > warningDurationMillis) {
            Log.i(
                EXPIRY_TAG,
                "Final warning display skipped because it is not in warning window: " +
                    "package=$packageName source=$source remainingMillis=${timing.remainingMillis} " +
                    "warningDurationMillis=$warningDurationMillis isExpired=${timing.isExpired}"
            )
            return
        }

        if (!shouldShowFinalWarningForPackage(
                targetPackageName = packageName,
                currentForegroundPackageName = currentForegroundPackageName,
                systemUiIsActive = systemUiIsActive
            )
        ) {
            Log.i(
                EXPIRY_TAG,
                "Final warning display skipped because target is not visible: " +
                    "package=$packageName source=$source " +
                    "currentForeground=$currentForegroundPackageName " +
                    "targetForeground=${currentForegroundPackageName == packageName} " +
                    "systemUiIsActive=$systemUiIsActive"
            )
            hideFinalWarningForPackage(
                packageName = packageName,
                reason = "package left foreground; currentForeground=$currentForegroundPackageName " +
                    "systemUiIsActive=$systemUiIsActive"
            )
            return
        }

        val remainingSeconds = remainingSecondsUntilExpiry(
            currentTimeMillis = nowMillis,
            allowedUntilMillis = allowedUntilMillis
        ).coerceAtMost((warningDurationMillis / 1_000L).toInt())
        if (remainingSeconds <= 0) return

        if (isFinalWarningAcknowledged(
                acknowledgedWarnings = acknowledgedFinalWarningsByPackageName,
                packageName = packageName,
                allowedUntilMillis = allowedUntilMillis
            )
        ) {
            Log.i(
                EXPIRY_TAG,
                "Final warning display skipped because this session was acknowledged: " +
                    "package=$packageName allowedUntil=$allowedUntilMillis " +
                    "remainingSeconds=$remainingSeconds"
            )
            hideFinalWarningForPackage(
                packageName = packageName,
                reason = "warning acknowledged for this session"
            )
            return
        }

        val existingOverlay = finalWarningOverlay
        if (existingOverlay != null && finalWarningPackageName != packageName) {
            hideFinalWarning(reason = "another package needs the final warning")
        }

        val wasAlreadyVisibleForPackage = finalWarningOverlay != null &&
            finalWarningPackageName == packageName
        if (!wasAlreadyVisibleForPackage && source.contains("foreground app returned")) {
            Log.i(
                EXPIRY_TAG,
                "Warning restored after package returned: " +
                    "package=$packageName remainingSeconds=$remainingSeconds"
            )
        }

        val overlay = finalWarningOverlay ?: FinalWarningOverlay(
            service = this,
            appLabel = loadAppLabel(packageName),
            packageName = packageName,
            languagePreference = languagePreference,
            themePreference = themePreference,
            onAcknowledged = {
                acknowledgeFinalWarning(
                    packageName = packageName,
                    allowedUntilMillis = allowedUntilMillis
                )
            },
            onEndSession = {
                endSessionFromFinalWarning(
                    packageName = packageName,
                    allowedUntilMillis = allowedUntilMillis
                )
            }
        ).also {
            finalWarningOverlay = it
            finalWarningPackageName = packageName
            finalWarningAllowedUntilMillis = allowedUntilMillis
            finalWarningLastSecond = null
        }

        Log.i(
            EXPIRY_TAG,
            "Final warning showOrUpdate called: package=$packageName source=$source " +
                "currentForeground=$currentForegroundPackageName " +
                "targetForeground=${currentForegroundPackageName == packageName} " +
                "systemUiIsActive=$systemUiIsActive remainingSeconds=$remainingSeconds " +
                "warningDurationMillis=$warningDurationMillis " +
                "vibrationEnabled=$vibrationEnabled " +
                "creatingOverlay=${!wasAlreadyVisibleForPackage}"
        )
        val shouldPlayHaptic = vibrationEnabled &&
            hapticPlayedFinalWarningsByPackageName[packageName] != allowedUntilMillis
        if (overlay.showOrUpdate(
                remainingSeconds = remainingSeconds,
                playHaptic = shouldPlayHaptic
            )
        ) {
            if (shouldPlayHaptic) {
                hapticPlayedFinalWarningsByPackageName[packageName] = allowedUntilMillis
            }
            Log.i(
                EXPIRY_TAG,
                "Final warning showOrUpdate succeeded: package=$packageName " +
                    "remainingSeconds=$remainingSeconds hapticPlayed=$shouldPlayHaptic"
            )
            finalWarningPackageName = packageName
            finalWarningAllowedUntilMillis = allowedUntilMillis
            if (finalWarningLastSecond != remainingSeconds) {
                finalWarningLastSecond = remainingSeconds
                Log.i(
                    EXPIRY_TAG,
                    "Warning second updated: package=$packageName " +
                    "remainingSeconds=$remainingSeconds allowedUntil=$allowedUntilMillis"
                )
            }
        } else {
            Log.e(
                EXPIRY_TAG,
                "Final warning showOrUpdate failed: package=$packageName " +
                    "remainingSeconds=$remainingSeconds"
            )
        }
    }

    private fun acknowledgeFinalWarning(
        packageName: String,
        allowedUntilMillis: Long
    ) {
        acknowledgedFinalWarningsByPackageName[packageName] = allowedUntilMillis
        Log.i(
            EXPIRY_TAG,
            "Final warning acknowledged: package=$packageName " +
                "allowedUntil=$allowedUntilMillis"
        )
        hideFinalWarningForPackage(
            packageName = packageName,
            reason = "user tapped 知道了"
        )
    }

    private fun endSessionFromFinalWarning(
        packageName: String,
        allowedUntilMillis: Long
    ) {
        Log.i(
            EXPIRY_TAG,
            "Final warning end-session requested: package=$packageName " +
                "allowedUntil=$allowedUntilMillis logicalForeground=$currentForegroundPackageName " +
                "rawLatestPackage=$latestRawAccessibilityPackageName"
        )
        serviceScope.launch {
            handleExactExpiry(
                packageName = packageName,
                allowedUntilMillis = allowedUntilMillis,
                source = "user tapped 结束使用"
            )
        }
    }

    private suspend fun handleExactExpiry(
        packageName: String,
        allowedUntilMillis: Long,
        source: String
    ) {
        val nowMillis = System.currentTimeMillis()
        Log.i(
            EXPIRY_TAG,
            "Exact expiry reached: package=$packageName source=$source " +
                "allowedUntil=$allowedUntilMillis nowMillis=$nowMillis " +
                "logicalForeground=$currentForegroundPackageName " +
                "rawLatestPackage=$latestRawAccessibilityPackageName " +
                "systemUiIsActive=$systemUiIsActive"
        )

        val settings = runCatching {
            settingsRepository.settings.first()
        }.getOrElse { exception ->
            Log.e(
                EXPIRY_TAG,
                "Exact expiry settings read failed: package=$packageName " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
            return
        }

        val savedAllowedUntilMillis = savedAllowedUntilForPackage(
            activeUsageSessions = settings.activeUsageSessions,
            packageName = packageName
        )
        val sessionMatches = savedAllowedUntilMillis == allowedUntilMillis
        val sessionStillValid = savedAllowedUntilMillis != null && nowMillis < savedAllowedUntilMillis
        Log.i(
            EXPIRY_TAG,
            "Exact expiry saved session status: package=$packageName " +
                "savedAllowedUntil=$savedAllowedUntilMillis " +
                "scheduledAllowedUntil=$allowedUntilMillis " +
                "sessionExists=${savedAllowedUntilMillis != null} " +
                "sessionMatches=$sessionMatches sessionStillValid=$sessionStillValid"
        )
        if (savedAllowedUntilMillis != allowedUntilMillis) {
            Log.i(
                EXPIRY_TAG,
                "Exact expiry skipped because schedule is stale: package=$packageName " +
                    "scheduledAllowedUntil=$allowedUntilMillis " +
                    "savedAllowedUntil=$savedAllowedUntilMillis"
            )
            clearCompletedExpiryWorkForPackage(packageName, allowedUntilMillis)
            return
        }

        hideFinalWarningForPackage(
            packageName = packageName,
            reason = "exact expiry reached"
        )
        val isExpiredPackageForeground = shouldShowFinalWarningForPackage(
            targetPackageName = packageName,
            currentForegroundPackageName = currentForegroundPackageName,
            systemUiIsActive = systemUiIsActive
        )
        if (isExpiredPackageForeground) {
            expiringPackageName = packageName
            Log.i(
                EXPIRY_TAG,
                "Expiry enforcement state set: package=$packageName " +
                    "logicalForeground=$currentForegroundPackageName " +
                    "rawLatestPackage=$latestRawAccessibilityPackageName"
            )
        } else {
            Log.i(
                EXPIRY_TAG,
                "Expiry enforcement state not set because target is not foreground: " +
                    "package=$packageName logicalForeground=$currentForegroundPackageName " +
                    "rawLatestPackage=$latestRawAccessibilityPackageName " +
                    "systemUiIsActive=$systemUiIsActive"
            )
        }

        val sessionRemovalSucceeded = runCatching {
            settingsRepository.removeUsageSession(packageName)
        }.onSuccess {
            Log.i(
                EXPIRY_TAG,
                "Session removal completed: package=$packageName " +
                    "allowedUntil=$allowedUntilMillis"
            )
        }.onFailure { exception ->
            Log.e(
                EXPIRY_TAG,
                "Session removal failed: package=$packageName " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
        }.isSuccess

        val notificationRemovalSucceeded = runCatching {
            notificationHelper.removeSessionNotification(packageName)
        }.onSuccess {
            Log.i(EXPIRY_TAG, "Notification removal completed: package=$packageName")
        }.onFailure { exception ->
            Log.e(
                EXPIRY_TAG,
                "Notification removal failed: package=$packageName " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
        }.isSuccess

        Log.i(
            EXPIRY_TAG,
            "Expired package foreground=$isExpiredPackageForeground: " +
                "package=$packageName currentForeground=$currentForegroundPackageName " +
                "rawLatestPackage=$latestRawAccessibilityPackageName " +
                "systemUiIsActive=$systemUiIsActive"
        )

        if (isExpiredPackageForeground) {
            startExpiryHomeEnforcement(
                packageName = packageName,
                source = source
            )
        } else {
            Log.i(
                EXPIRY_TAG,
                "Home action skipped because expired package is not foreground: " +
                    "package=$packageName currentForeground=$currentForegroundPackageName"
            )
        }

        clearCompletedExpiryWorkForPackage(packageName, allowedUntilMillis)
        Log.i(
            EXPIRY_TAG,
            "Session cleanup completed: package=$packageName allowedUntil=$allowedUntilMillis " +
                "sessionRemovalSucceeded=$sessionRemovalSucceeded " +
                "notificationRemovalSucceeded=$notificationRemovalSucceeded"
        )
    }

    private fun startExpiryHomeEnforcement(
        packageName: String,
        source: String
    ) {
        expiringPackageName = packageName
        dismissSessionPrompt()
        hideFinalWarningForPackage(
            packageName = packageName,
            reason = "expiry Home enforcement started"
        )
        Log.i(
            EXPIRY_TAG,
            "Expiry enforcement started: package=$packageName source=$source " +
                "logicalForeground=$currentForegroundPackageName " +
                "rawLatestPackage=$latestRawAccessibilityPackageName " +
                "stateSet=${expiringPackageName == packageName}"
        )

        val firstResult = performGlobalAction(GLOBAL_ACTION_HOME)
        Log.i(
            EXPIRY_TAG,
            "Home action executed: package=$packageName result=$firstResult " +
                "logicalForeground=$currentForegroundPackageName " +
                "rawLatestPackage=$latestRawAccessibilityPackageName " +
                "expiringStateSet=${expiringPackageName == packageName}"
        )
        if (!firstResult) {
            Log.e(
                EXPIRY_TAG,
                "Home action attempted and returned false: package=$packageName"
            )
        }

        serviceScope.launch {
            delay(HOME_ACTION_OBSERVE_DELAY_MILLIS)
            Log.i(
                EXPIRY_TAG,
                "Package observed after Home action: package=$packageName " +
                    "logicalForeground=$currentForegroundPackageName " +
                    "rawLatestPackage=$latestRawAccessibilityPackageName " +
                    "expiringPackage=$expiringPackageName " +
                    "systemUiIsActive=$systemUiIsActive"
            )

            if (expiringPackageName != packageName) {
                Log.i(
                    EXPIRY_TAG,
                    "Home enforcement observation stopped; state already cleared: " +
                        "package=$packageName expiringPackage=$expiringPackageName"
                )
                return@launch
            }

            if (!shouldShowFinalWarningForPackage(
                    targetPackageName = packageName,
                    currentForegroundPackageName = currentForegroundPackageName,
                    systemUiIsActive = systemUiIsActive
                )
            ) {
                Log.i(
                    EXPIRY_TAG,
                    "Home enforcement succeeded or target is no longer visible: " +
                        "package=$packageName logicalForeground=$currentForegroundPackageName"
                )
                return@launch
            }

            Log.i(
                EXPIRY_TAG,
                "Expired package still foreground after Home action; retrying once: " +
                    "package=$packageName"
            )
            val retryResult = performGlobalAction(GLOBAL_ACTION_HOME)
            Log.i(
                EXPIRY_TAG,
                "Home action retry executed: package=$packageName result=$retryResult " +
                    "logicalForeground=$currentForegroundPackageName " +
                    "rawLatestPackage=$latestRawAccessibilityPackageName"
            )
            if (!retryResult) {
                Log.e(
                    EXPIRY_TAG,
                    "Home action retry returned false: package=$packageName"
                )
            }

            delay(HOME_ACTION_OBSERVE_DELAY_MILLIS)
            Log.i(
                EXPIRY_TAG,
                "Package observed after Home retry: package=$packageName " +
                    "logicalForeground=$currentForegroundPackageName " +
                    "rawLatestPackage=$latestRawAccessibilityPackageName " +
                    "expiringPackage=$expiringPackageName " +
                    "systemUiIsActive=$systemUiIsActive"
            )

            if (expiringPackageName == packageName &&
                shouldShowFinalWarningForPackage(
                    targetPackageName = packageName,
                    currentForegroundPackageName = currentForegroundPackageName,
                    systemUiIsActive = systemUiIsActive
                )
            ) {
                showExpiryBlockOverlay(packageName)
            }
        }
    }

    private fun showExpiryBlockOverlay(packageName: String) {
        if (expiryBlockOverlayPackageName == packageName && expiryBlockOverlay != null) {
            Log.i(
                EXPIRY_TAG,
                "Expired block overlay already visible: package=$packageName"
            )
            return
        }

        dismissExpiryBlockOverlay(reason = "showing block overlay for $packageName")
        val overlay = ExpiredSessionBlockOverlay(
            service = this,
            appLabel = loadAppLabel(packageName),
            packageName = packageName,
            languagePreference = currentLanguagePreference,
            themePreference = currentThemePreference,
            onReturnHome = {
                Log.i(
                    EXPIRY_TAG,
                    "Expired block overlay Return Home tapped: package=$packageName"
                )
                val result = performGlobalAction(GLOBAL_ACTION_HOME)
                Log.i(
                    EXPIRY_TAG,
                    "Home action from expired block overlay executed: " +
                        "package=$packageName result=$result"
                )
            }
        )

        if (overlay.show()) {
            expiryBlockOverlay = overlay
            expiryBlockOverlayPackageName = packageName
            Log.i(
                EXPIRY_TAG,
                "Expired block overlay shown: package=$packageName"
            )
        } else {
            Log.e(
                EXPIRY_TAG,
                "Expired block overlay failed to show: package=$packageName"
            )
        }
    }

    private fun dismissExpiryBlockOverlay(reason: String) {
        val packageName = expiryBlockOverlayPackageName
        expiryBlockOverlay?.dismiss()
        expiryBlockOverlay = null
        expiryBlockOverlayPackageName = null
        if (packageName != null) {
            Log.i(
                EXPIRY_TAG,
                "Expired block overlay dismissed: package=$packageName reason=$reason"
            )
        }
    }

    private fun handleExpiryEnforcementForegroundChange(
        packageName: String,
        event: AccessibilityEvent,
        isInputMethodEvent: Boolean
    ) {
        val targetPackageName = expiringPackageName ?: return

        if (packageName == targetPackageName) {
            Log.i(
                EXPIRY_TAG,
                "Expiry enforcement still active; target package remains foreground: " +
                    "package=$packageName event=${event.windowDebugText()}"
            )
            return
        }

        if (shouldClearExpiryEnforcementForForegroundPackage(
                expiringPackageName = targetPackageName,
                eventPackageName = packageName,
                pausePackageName = applicationContext.packageName,
                isSystemUiPackage = packageName.isSystemUiPackage(),
                isInputMethodWindow = isInputMethodEvent
            )
        ) {
            Log.i(
                EXPIRY_TAG,
                "Expiry enforcement cleared after foreground changed away: " +
                    "expiringPackage=$targetPackageName foregroundPackage=$packageName " +
                    "event=${event.windowDebugText()}"
            )
            clearExpiryEnforcement(
                packageName = targetPackageName,
                reason = "foreground changed to $packageName"
            )
        }
    }

    private fun clearExpiryEnforcement(
        packageName: String,
        reason: String
    ) {
        if (expiringPackageName == packageName) {
            expiringPackageName = null
        }
        dismissExpiryBlockOverlay(reason = reason)
        Log.i(
            EXPIRY_TAG,
            "Expiry enforcement state cleared: package=$packageName reason=$reason " +
                "logicalForeground=$currentForegroundPackageName " +
                "rawLatestPackage=$latestRawAccessibilityPackageName"
        )
    }

    private suspend fun loadSavedSessionStatus(
        packageName: String,
        allowedUntilMillis: Long,
        currentTimeMillis: Long
    ): SavedSessionStatus? {
        val settings = runCatching {
            settingsRepository.settings.first()
        }.getOrElse { exception ->
            Log.e(
                EXPIRY_TAG,
                "Saved session check failed: package=$packageName " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
            return null
        }

        val savedAllowedUntilMillis = savedAllowedUntilForPackage(
            activeUsageSessions = settings.activeUsageSessions,
            packageName = packageName
        )
        return SavedSessionStatus(
            exists = savedAllowedUntilMillis != null,
            savedAllowedUntilMillis = savedAllowedUntilMillis,
            matchesScheduledSession = savedAllowedUntilMillis == allowedUntilMillis,
            isValid = savedAllowedUntilMillis != null && currentTimeMillis < savedAllowedUntilMillis
        )
    }

    private fun hideFinalWarningIfLogicalForegroundLeftTarget(
        rawEventPackageName: String,
        event: AccessibilityEvent
    ) {
        val warningPackageName = finalWarningPackageName ?: return
        val logicalForegroundPackageName = currentForegroundPackageName

        if (!shouldHideFinalWarningBecauseLogicalForegroundLeftTarget(
                targetPackageName = warningPackageName,
                currentForegroundPackageName = logicalForegroundPackageName
            )
        ) {
            if (rawEventPackageName != warningPackageName) {
                Log.i(
                    EXPIRY_TAG,
                    "Final warning kept because logical foreground is still target: " +
                        "targetPackage=$warningPackageName " +
                        "logicalForeground=$logicalForegroundPackageName " +
                        "rawEventPackage=$rawEventPackageName " +
                        "event=${event.windowDebugText()}"
                )
            }
            return
        }

        hideFinalWarning(
            reason = "logical foreground left target; " +
                "logicalForeground=$logicalForegroundPackageName " +
                "rawEventPackage=$rawEventPackageName event=${event.windowDebugText()}"
        )
    }

    private fun hideFinalWarningForPackage(packageName: String, reason: String) {
        if (finalWarningPackageName != packageName) return

        hideFinalWarning(reason = reason)
    }

    private fun hideFinalWarning(reason: String) {
        val packageName = finalWarningPackageName ?: return

        finalWarningOverlay?.dismiss()
        finalWarningOverlay = null
        finalWarningPackageName = null
        finalWarningAllowedUntilMillis = null
        finalWarningLastSecond = null
        Log.i(
            EXPIRY_TAG,
            "Final warning overlay removed: " +
                "package=$packageName currentForeground=$currentForegroundPackageName " +
                "reason=$reason"
        )
    }

    private fun cancelExpiryWorkForPackage(packageName: String, reason: String) {
        val warningJob = warningJobsByPackageName.remove(packageName)
        val expiryJob = expiryJobsByPackageName.remove(packageName)
        val scheduledAllowedUntilMillis = scheduledAllowedUntilByPackageName.remove(packageName)
        val scheduledWarningDurationMillis = scheduledWarningDurationByPackageName.remove(packageName)
        warningJob?.cancel()
        expiryJob?.cancel()
        hideFinalWarningForPackage(
            packageName = packageName,
            reason = "schedule cancelled; $reason"
        )

        if (warningJob != null || expiryJob != null || scheduledAllowedUntilMillis != null) {
            Log.i(
                EXPIRY_TAG,
                "Schedule replaced/cancelled: package=$packageName reason=$reason " +
                    "scheduledAllowedUntil=$scheduledAllowedUntilMillis " +
                    "scheduledWarningDurationMillis=$scheduledWarningDurationMillis " +
                    "warningJobCancelled=${warningJob != null} " +
                    "expiryJobCancelled=${expiryJob != null}"
            )
        }
    }

    private fun cancelAllExpiryWork(reason: String) {
        val packageNames = (
            warningJobsByPackageName.keys +
                expiryJobsByPackageName.keys +
                scheduledAllowedUntilByPackageName.keys
            ).toSet()

        packageNames.forEach { packageName ->
            cancelExpiryWorkForPackage(packageName, reason)
        }
    }

    private fun clearCompletedExpiryWorkForPackage(
        packageName: String,
        allowedUntilMillis: Long
    ) {
        if (scheduledAllowedUntilByPackageName[packageName] != allowedUntilMillis) {
            Log.i(
                EXPIRY_TAG,
                "Completed schedule cleanup skipped because a newer schedule exists: " +
                    "package=$packageName completedAllowedUntil=$allowedUntilMillis " +
                    "currentScheduledAllowedUntil=${scheduledAllowedUntilByPackageName[packageName]}"
            )
            return
        }

        warningJobsByPackageName.remove(packageName)?.cancel()
        expiryJobsByPackageName.remove(packageName)
        scheduledAllowedUntilByPackageName.remove(packageName)
    }

    private suspend fun restoreActiveSessionNotifications() {
        val nowMillis = System.currentTimeMillis()
        val settings = runCatching {
            settingsRepository.settings.first()
        }.getOrElse { exception ->
            Log.e(
                NOTIFICATION_TAG,
                "Active session restore failed: exception=${exception.javaClass.name} " +
                    "message=${exception.message}",
                exception
            )
            return
        }
        applyUserPreferences(settings, source = "AccessibilityService reconnect")

        var restoredCount = 0
        val currentWeekday = currentLocalWeekday()
        val currentMinutes = currentLocalMinutesSinceMidnight()
        val systemExcludedPackages = buildWhitelistSystemExcludedPackages(applicationContext)
        settings.activeUsageSessions.forEach { (packageName, allowedUntilMillis) ->
            val session = UsageSession(packageName, allowedUntilMillis)
            if (session.isValidAt(nowMillis)) {
                val restrictionEvaluation = evaluatePackageRestriction(
                    schedules = settings.restrictionSchedules,
                    packageName = packageName,
                    systemExcludedPackages = systemExcludedPackages,
                    currentWeekday = currentWeekday,
                    currentMinutesSinceMidnight = currentMinutes
                )
                if (!restrictionEvaluation.isRestricted) {
                    settingsRepository.removeUsageSession(packageName)
                    notificationHelper.removeSessionNotification(packageName)
                    Log.i(
                        SCHEDULE_TAG,
                        "Active session skipped during service reconnect because package is no longer restricted: " +
                            "package=$packageName activeSchedules=${restrictionEvaluation.activeSchedules.size}"
                    )
                    return@forEach
                }

                notificationHelper.showSessionNotification(
                    packageName = packageName,
                    appLabel = loadAppLabel(packageName),
                    allowedUntilMillis = allowedUntilMillis,
                    languagePreference = settings.languagePreference
                )
                scheduleExpiryWorkForPackage(
                    packageName = packageName,
                    allowedUntilMillis = allowedUntilMillis,
                    source = "AccessibilityService reconnect"
                )
                restoredCount += 1
                Log.i(
                    NOTIFICATION_TAG,
                    "Active session restored after service reconnect: " +
                        "package=$packageName allowedUntil=$allowedUntilMillis"
                )
                Log.i(
                    EXPIRY_TAG,
                    "Restored schedule after AccessibilityService reconnect: " +
                        "package=$packageName allowedUntil=$allowedUntilMillis"
                )
            } else {
                settingsRepository.removeUsageSession(packageName)
                notificationHelper.removeSessionNotification(packageName)
                Log.i(
                    NOTIFICATION_TAG,
                    "Expired session removed during service reconnect: " +
                        "package=$packageName allowedUntil=$allowedUntilMillis"
                )
            }
        }

        Log.i(
            NOTIFICATION_TAG,
            "Active session restore complete: restoredCount=$restoredCount " +
                "savedSessionCount=${settings.activeUsageSessions.size}"
        )
    }

    private fun loadAppLabel(packageName: String): String {
        return runCatching {
            val applicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }

            packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrDefault(packageName)
    }

    private fun AccessibilityEvent.isWindowChangeEvent(): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    }

    private fun AccessibilityEvent.eventTypeName(): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "TYPE_WINDOWS_CHANGED"
            else -> eventType.toString()
        }
    }

    private fun AccessibilityEvent.windowDebugText(): String {
        return "package=${packageName?.toString()} eventType=${eventTypeName()} " +
            "className=${className?.toString()} windowId=$windowId eventTime=$eventTime"
    }

    private fun AccessibilityEvent.isInputMethodWindowEvent(promptVisible: Boolean): Boolean {
        val eventPackageName = packageName?.toString()
        val eventClassName = className?.toString()
        val eventWindowType = windowType()
        val activeImePackageName = activeInputMethodPackageName()
        val hasInputMethodWindow = hasInputMethodWindow()
        val detectedByWindowType = eventWindowType == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        val detectedByClassName = eventClassName.hasInputMethodClassName()
        val detectedByActiveImePackage = eventPackageName == activeImePackageName &&
            (promptVisible || hasInputMethodWindow || detectedByClassName)

        return detectedByWindowType || detectedByClassName || detectedByActiveImePackage
    }

    private fun AccessibilityEvent.windowType(): Int? {
        return activeWindowSnapshot()
            .firstOrNull { window -> window.id == windowId }
            ?.type
    }

    private fun AccessibilityEvent.windowTypeName(): String {
        return windowType()?.accessibilityWindowTypeName() ?: "unknown"
    }

    private fun activeWindowSnapshot(): List<AccessibilityWindowInfo> {
        return runCatching {
            windows?.toList() ?: emptyList()
        }.getOrElse { exception ->
            Log.e(
                SESSION_TAG,
                "Unable to read accessibility windows: " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
            emptyList()
        }
    }

    private fun hasInputMethodWindow(): Boolean {
        return activeWindowSnapshot()
            .any { window -> window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }

    private fun inputMethodWindowsDebugText(): String {
        val inputMethodWindows = activeWindowSnapshot()
            .filter { window -> window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

        if (inputMethodWindows.isEmpty()) return "none"

        return inputMethodWindows.joinToString(separator = "; ") { window ->
            "id=${window.id} type=${window.type.accessibilityWindowTypeName()} " +
                "layer=${window.layer} active=${window.isActive} focused=${window.isFocused} " +
                "title=${window.title}"
        }
    }

    private fun activeInputMethodPackageName(): String? {
        val inputMethodComponent = runCatching {
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
        }.getOrElse { exception ->
            Log.e(
                RESTRICTION_TAG,
                "Active input method lookup failed during foreground classification: " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
            null
        } ?: return null

        return inputMethodComponent.substringBefore('/').takeIf { it.isNotBlank() }
    }

    private fun String?.hasInputMethodClassName(): Boolean {
        if (this == null) return false

        return contains("InputMethod", ignoreCase = true) ||
            contains("SoftInputWindow", ignoreCase = true) ||
            contains("InputMethodService", ignoreCase = true)
    }

    private fun Int.accessibilityWindowTypeName(): String {
        return when (this) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "TYPE_APPLICATION"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "TYPE_INPUT_METHOD"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "TYPE_SYSTEM"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "TYPE_ACCESSIBILITY_OVERLAY"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "TYPE_SPLIT_SCREEN_DIVIDER"
            else -> toString()
        }
    }

    private fun ignoredForegroundReason(
        packageName: String,
        isInputMethodEvent: Boolean
    ): String {
        return when {
            isInputMethodEvent -> "IME/input method window"
            packageName == applicationContext.packageName -> "Pause prompt overlay is visible"
            else -> "not tracked"
        }
    }

    private fun String.isSystemUiPackage(): Boolean {
        return this == SYSTEM_UI_PACKAGE
    }

    private fun Job?.debugText(): String {
        if (this == null) return "null"

        return "Job@${System.identityHashCode(this).toString(16)}" +
            "(active=$isActive cancelled=$isCancelled completed=$isCompleted)"
    }

    private data class SavedSessionStatus(
        val exists: Boolean,
        val savedAllowedUntilMillis: Long?,
        val matchesScheduledSession: Boolean,
        val isValid: Boolean
    )

    companion object {
        private const val TAG = "PauseForeground"
        private const val SESSION_TAG = "PauseSession"
        private const val NOTIFICATION_TAG = "PauseNotification"
        private const val EXPIRY_TAG = "PauseExpiry"
        private const val SCHEDULE_TAG = "PauseSchedule"
        private const val RESTRICTION_TAG = "PauseRestriction"
        private const val OVERLAY_TAG = "PauseOverlay"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val HOME_ACTION_OBSERVE_DELAY_MILLIS = 500L
    }
}
