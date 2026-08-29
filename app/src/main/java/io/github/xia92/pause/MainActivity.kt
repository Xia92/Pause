package io.github.xia92.pause

import android.Manifest
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import io.github.xia92.pause.ui.theme.PauseTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val settingsRepository by lazy {
        PauseSettingsRepository(applicationContext)
    }
    private val permissionRefreshToken = mutableIntStateOf(0)
    private val accessibilityForegroundVisitToken = mutableIntStateOf(0)
    private var accessibilityCheckedThisForegroundVisit = false
    private var notificationPermissionRequestedThisRun = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i(NOTIFICATION_TAG, "Notification permission result: granted=$isGranted")
        permissionRefreshToken.intValue += 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(PAUSE_LOCALE_TAG, "Activity onCreate: savedInstanceState=${savedInstanceState != null}")
        if (savedInstanceState != null) {
            Log.i(PAUSE_LOCALE_TAG, "Activity recreated; branded splash will not replay.")
        }
        val initialSettings = loadInitialSettings()
        applyPauseAppLocalePreference(
            languagePreference = initialSettings.languagePreference,
            source = "MainActivity.onCreate",
            explicitUserAction = false
        )
        val shouldShowBrandedSplash = savedInstanceState == null &&
            !hasStartedBrandedSplashThisProcess
        if (shouldShowBrandedSplash) {
            hasStartedBrandedSplashThisProcess = true
            Log.i(PAUSE_LOCALE_TAG, "Cold launch branded splash scheduled.")
        }
        enableEdgeToEdge()
        setContent {
            PauseSettingsApp(
                settingsRepository = settingsRepository,
                initialSettings = initialSettings,
                permissionRefreshToken = permissionRefreshToken.intValue,
                accessibilityForegroundVisitToken = accessibilityForegroundVisitToken.intValue,
                showBrandedSplash = shouldShowBrandedSplash,
                versionName = BuildConfig.VERSION_NAME,
                onRequestNotificationPermission = ::requestOrOpenNotificationSettings
            )
        }
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        permissionRefreshToken.intValue += 1
        if (!accessibilityCheckedThisForegroundVisit) {
            accessibilityCheckedThisForegroundVisit = true
            accessibilityForegroundVisitToken.intValue += 1
        }
    }

    override fun onStop() {
        accessibilityCheckedThisForegroundVisit = false
        super.onStop()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Log.i(
                NOTIFICATION_TAG,
                "Notification permission state: source=MainActivity sdk=${Build.VERSION.SDK_INT} canPost=true"
            )
            return
        }

        val isGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        Log.i(
            NOTIFICATION_TAG,
            "Notification permission state: source=MainActivity sdk=${Build.VERSION.SDK_INT} canPost=$isGranted"
        )

        if (!isGranted) {
            notificationPermissionRequestedThisRun = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestOrOpenNotificationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            openPauseNotificationSettings(applicationContext)
            return
        }

        val isGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (isGranted) {
            openPauseNotificationSettings(applicationContext)
            return
        }

        val canShowRuntimePrompt =
            !notificationPermissionRequestedThisRun ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)

        if (shouldRequestPostNotificationPermission(this)) {
            if (canShowRuntimePrompt) {
                notificationPermissionRequestedThisRun = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openPauseNotificationSettings(applicationContext)
            }
        } else {
            openPauseNotificationSettings(applicationContext)
        }
    }

    private fun loadInitialSettings(): PauseSettings {
        return runCatching {
            runBlocking {
                settingsRepository.settings.first()
            }
        }.onSuccess { settings ->
            Log.i(
                PAUSE_LOCALE_TAG,
                "Saved language preference loaded: " +
                    "preference=${settings.languagePreference.storageValue}"
            )
        }.onFailure { exception ->
            Log.e(
                PAUSE_LOCALE_TAG,
                "Saved language preference load failed; using defaults: " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
        }.getOrDefault(PauseSettings())
    }

    companion object {
        private const val NOTIFICATION_TAG = "PauseNotification"
        private var hasStartedBrandedSplashThisProcess = false
    }
}

@Composable
private fun stringResource(@StringRes id: Int, vararg formatArgs: Any): String {
    val context = LocalContext.current
    return if (formatArgs.isEmpty()) {
        context.getString(id)
    } else {
        context.getString(id, *formatArgs)
    }
}

@Composable
private fun PauseSettingsApp(
    settingsRepository: PauseSettingsRepository,
    initialSettings: PauseSettings,
    permissionRefreshToken: Int,
    accessibilityForegroundVisitToken: Int,
    showBrandedSplash: Boolean,
    versionName: String,
    onRequestNotificationPermission: () -> Unit
) {
    val activityContext = LocalContext.current
    val context = activityContext.applicationContext
    val coroutineScope = rememberCoroutineScope()
    val settings by settingsRepository.settings.collectAsState(initial = initialSettings)
    val useDarkTheme = shouldUseDarkTheme(settings.themePreference)

    var launchableApps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var isSettingsOpen by rememberSaveable { mutableStateOf(false) }
    var isSplashVisible by remember { mutableStateOf(showBrandedSplash) }
    var splashAlphaTarget by remember { mutableFloatStateOf(1f) }
    var showAccessibilityOffDialog by rememberSaveable { mutableStateOf(false) }
    var accessibilityDialogShownForVisitToken by rememberSaveable { mutableStateOf(0) }
    val splashAlpha by animateFloatAsState(
        targetValue = splashAlphaTarget,
        animationSpec = tween(durationMillis = BRANDED_SPLASH_FADE_OUT_MILLIS),
        label = "Pause branded splash alpha"
    )

    BackHandler(enabled = isSettingsOpen) {
        isSettingsOpen = false
    }

    LaunchedEffect(context) {
        launchableApps = withContext(Dispatchers.IO) {
            loadLaunchableApps(context)
        }
        isLoadingApps = false
    }
    LaunchedEffect(settingsRepository) {
        settingsRepository.migrateSingleTimeSettingsToSchedulesIfNeeded()
    }
    LaunchedEffect(showBrandedSplash) {
        if (showBrandedSplash) {
            delay(BRANDED_SPLASH_STABLE_MILLIS)
            splashAlphaTarget = 0f
            delay(BRANDED_SPLASH_FADE_OUT_MILLIS.toLong())
            isSplashVisible = false
        }
    }

    LaunchedEffect(accessibilityForegroundVisitToken, isSplashVisible, context) {
        if (accessibilityForegroundVisitToken <= 0 || isSplashVisible) {
            return@LaunchedEffect
        }

        val accessibilityEnabled = isPauseAccessibilityServiceEnabled(context)
        if (accessibilityEnabled) {
            showAccessibilityOffDialog = false
            return@LaunchedEffect
        }

        if (accessibilityDialogShownForVisitToken != accessibilityForegroundVisitToken) {
            accessibilityDialogShownForVisitToken = accessibilityForegroundVisitToken
            showAccessibilityOffDialog = true
        }
    }

    val localizedContext = remember(activityContext, settings.languagePreference) {
        activityContext.localizedForPause(settings.languagePreference)
    }

    PauseTheme(darkTheme = useDarkTheme) {
        CompositionLocalProvider(LocalContext provides localizedContext) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (isSettingsOpen) {
                PausePreferencesScreen(
                    settings = settings,
                    permissionRefreshToken = permissionRefreshToken,
                    versionName = versionName,
                    onBack = { isSettingsOpen = false },
                    onLanguageChanged = { languagePreference ->
                        if (settings.languagePreference == languagePreference) {
                            Log.i(
                                PAUSE_LOCALE_TAG,
                                "Language selection skipped; already saved: " +
                                    "preference=${languagePreference.storageValue}"
                            )
                            return@PausePreferencesScreen
                        }

                        coroutineScope.launch {
                            settingsRepository.setLanguagePreference(languagePreference)
                            activityContext.applyPauseAppLocalePreference(
                                languagePreference = languagePreference,
                                source = "Settings explicit user action",
                                explicitUserAction = true
                            )
                        }
                    },
                    onThemeChanged = { themePreference ->
                        coroutineScope.launch {
                            settingsRepository.setThemePreference(themePreference)
                        }
                    },
                    onFinalWarningSecondsChanged = { seconds ->
                        coroutineScope.launch {
                            settingsRepository.setFinalWarningSeconds(seconds)
                        }
                    },
                    onWarningVibrationChanged = { enabled ->
                        coroutineScope.launch {
                            settingsRepository.setWarningVibrationEnabled(enabled)
                        }
                    },
                    onResetSettings = {
                        coroutineScope.launch {
                            settingsRepository.resetUserPreferences()
                            activityContext.applyPauseAppLocalePreference(
                                languagePreference = PauseLanguagePreference.FOLLOW_SYSTEM,
                                source = "Reset settings explicit user action",
                                explicitUserAction = true
                            )
                        }
                    },
                    onOpenAccessibilitySettings = {
                        openPauseAccessibilitySettings(context)
                    },
                    onNotificationPermissionClick = onRequestNotificationPermission
                )
            } else {
                PauseSettingsScreen(
                    settings = settings,
                    launchableApps = launchableApps,
                    isLoadingApps = isLoadingApps,
                    onOpenSettings = { isSettingsOpen = true },
                    onScheduleSaved = { schedule ->
                        coroutineScope.launch {
                            settingsRepository.upsertRestrictionSchedule(schedule)
                        }
                    },
                    onScheduleEnabledChanged = { scheduleId, enabled ->
                        coroutineScope.launch {
                            settingsRepository.setRestrictionScheduleEnabled(scheduleId, enabled)
                        }
                    },
                    onScheduleDeleted = { scheduleId ->
                        coroutineScope.launch {
                            settingsRepository.deleteRestrictionSchedule(scheduleId)
                        }
                    }
                )
            }

            if (showAccessibilityOffDialog) {
                AccessibilityOffDialog(
                    onDismiss = { showAccessibilityOffDialog = false },
                    onGoToSettings = {
                        showAccessibilityOffDialog = false
                        openPauseAccessibilitySettings(context)
                    }
                )
            }

            if (isSplashVisible) {
                BrandedSplashScreen(
                    versionName = versionName,
                    useDarkTheme = useDarkTheme,
                    alpha = splashAlpha
                )
            }
        }
        }
    }
}

@Composable
private fun shouldUseDarkTheme(themePreference: PauseThemePreference): Boolean {
    val systemIsDark = isSystemInDarkTheme()
    return when (themePreference) {
        PauseThemePreference.FOLLOW_SYSTEM -> systemIsDark
        PauseThemePreference.LIGHT -> false
        PauseThemePreference.DARK -> true
    }
}

@Composable
private fun BrandedSplashScreen(
    versionName: String,
    useDarkTheme: Boolean,
    alpha: Float
) {
    val colors = remember(useDarkTheme) {
        PauseSplashColors.fromTheme(useDarkTheme)
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha),
        color = colors.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_pause_symbol_splash),
                    contentDescription = null,
                    modifier = Modifier.size(
                        width = SPLASH_SYMBOL_WIDTH_DP.dp,
                        height = SPLASH_SYMBOL_HEIGHT_DP.dp
                    ),
                    colorFilter = ColorFilter.tint(colors.symbol)
                )
                Text(
                    text = stringResource(R.string.splash_tagline),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.tagline,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                text = stringResource(R.string.version_name, versionName),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 28.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.version,
                textAlign = TextAlign.Center
            )
        }
    }
}

private data class PauseSplashColors(
    val background: Color,
    val symbol: Color,
    val tagline: Color,
    val version: Color
) {
    companion object {
        fun fromTheme(isDark: Boolean): PauseSplashColors {
            return if (isDark) {
                PauseSplashColors(
                    background = Color(0xFF101418),
                    symbol = Color(0xFFF1F5F9),
                    tagline = Color(0xFFE7ECF3),
                    version = Color(0xFF8A94A3)
                )
            } else {
                PauseSplashColors(
                    background = Color(0xFFF6F8FB),
                    symbol = Color(0xFF1E1E1E),
                    tagline = Color(0xFF1F2933),
                    version = Color(0xFF667085)
                )
            }
        }
    }
}

private const val BRANDED_SPLASH_STABLE_MILLIS = 1_320L
private const val BRANDED_SPLASH_FADE_OUT_MILLIS = 220
private const val SPLASH_SYMBOL_WIDTH_DP = 78
private const val SPLASH_SYMBOL_HEIGHT_DP = 150

@Composable
private fun PauseSettingsScreen(
    settings: PauseSettings,
    launchableApps: List<LaunchableApp>,
    isLoadingApps: Boolean,
    onOpenSettings: () -> Unit,
    onScheduleSaved: (RestrictionSchedule) -> Unit,
    onScheduleEnabledChanged: (String, Boolean) -> Unit,
    onScheduleDeleted: (String) -> Unit
) {
    val isRestrictedNow = isLocalDateTimeInsideAnyRestrictionSchedule(
        schedules = settings.restrictionSchedules
    )
    var scheduleBeingEdited by remember { mutableStateOf<RestrictionSchedule?>(null) }
    var schedulePendingDeletion by remember { mutableStateOf<RestrictionSchedule?>(null) }
    var isAddingSchedule by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    schedulePendingDeletion?.let { schedule ->
        DeleteScheduleConfirmationDialog(
            onDismiss = { schedulePendingDeletion = null },
            onConfirmDelete = {
                onScheduleDeleted(schedule.id)
                schedulePendingDeletion = null
            }
        )
    }

    scheduleBeingEdited?.let { schedule ->
        RestrictionScheduleEditDialog(
            schedule = schedule,
            isNewSchedule = isAddingSchedule,
            launchableApps = launchableApps,
            isLoadingApps = isLoadingApps,
            onDismiss = {
                scheduleBeingEdited = null
                isAddingSchedule = false
            },
            onSave = { updatedSchedule ->
                onScheduleSaved(updatedSchedule)
                scheduleBeingEdited = null
                isAddingSchedule = false
            }
        )
    }

    Scaffold { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.app_name),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            TextButton(onClick = onOpenSettings) {
                                Text(stringResource(R.string.settings))
                            }
                        }

                        Text(
                            text = stringResource(R.string.restricted_schedules),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            text = if (isRestrictedNow) {
                                stringResource(R.string.now_restricted)
                            } else {
                                stringResource(R.string.now_not_restricted)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isRestrictedNow) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )

                        if (settings.restrictionSchedules.isEmpty()) {
                            Text(
                                text = stringResource(R.string.no_schedules_yet),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            settings.restrictionSchedules.forEach { schedule ->
                                RestrictionScheduleRow(
                                    schedule = schedule,
                                    onClick = {
                                        scheduleBeingEdited = schedule
                                        isAddingSchedule = false
                                    },
                                    onLongClick = {
                                        schedulePendingDeletion = schedule
                                    },
                                    onEnabledChange = { enabled ->
                                        onScheduleEnabledChanged(schedule.id, enabled)
                                    }
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                scheduleBeingEdited = RestrictionSchedule.newDefault()
                                isAddingSchedule = true
                            }
                        ) {
                            Text(stringResource(R.string.add_schedule))
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

private fun List<LaunchableApp>.filterBySearchQuery(query: String): List<LaunchableApp> {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return this

    return filter { app ->
        app.label.contains(trimmedQuery, ignoreCase = true) ||
            app.packageName.contains(trimmedQuery, ignoreCase = true)
    }
}

private fun List<LaunchableApp>.sortedForDisplay(
    selectedPackageNames: Set<String>
): List<LaunchableApp> {
    return sortedWith(
        compareByDescending<LaunchableApp> { app ->
            app.packageName in selectedPackageNames
        }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { app -> app.label }
            .thenBy { app -> app.packageName }
    )
}

@Composable
private fun PausePreferencesScreen(
    settings: PauseSettings,
    permissionRefreshToken: Int,
    versionName: String,
    onBack: () -> Unit,
    onLanguageChanged: (PauseLanguagePreference) -> Unit,
    onThemeChanged: (PauseThemePreference) -> Unit,
    onFinalWarningSecondsChanged: (Int) -> Unit,
    onWarningVibrationChanged: (Boolean) -> Unit,
    onResetSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onNotificationPermissionClick: () -> Unit
) {
    val context = LocalContext.current
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    val accessibilityEnabled = remember(permissionRefreshToken, context) {
        isPauseAccessibilityServiceEnabled(context)
    }
    val notificationsEnabled = remember(permissionRefreshToken, context) {
        isPauseNotificationPermissionEnabled(context)
    }

    if (showResetConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            title = {
                Text(stringResource(R.string.reset_settings_title))
            },
            text = {
                Text(stringResource(R.string.reset_settings_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmation = false
                        onResetSettings()
                    }
                ) {
                    Text(stringResource(R.string.reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.settings),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            TextButton(onClick = onBack) {
                                Text(stringResource(R.string.back))
                            }
                        }

                        SettingsSectionTitle(text = stringResource(R.string.language))
                        LanguageOptionRow(
                            text = stringResource(R.string.follow_system),
                            selected = settings.languagePreference ==
                                PauseLanguagePreference.FOLLOW_SYSTEM,
                            onClick = {
                                onLanguageChanged(PauseLanguagePreference.FOLLOW_SYSTEM)
                            }
                        )
                        LanguageOptionRow(
                            text = stringResource(R.string.english),
                            selected = settings.languagePreference ==
                                PauseLanguagePreference.ENGLISH,
                            onClick = {
                                onLanguageChanged(PauseLanguagePreference.ENGLISH)
                            }
                        )
                        LanguageOptionRow(
                            text = stringResource(R.string.simplified_chinese),
                            selected = settings.languagePreference ==
                                PauseLanguagePreference.SIMPLIFIED_CHINESE,
                            onClick = {
                                onLanguageChanged(PauseLanguagePreference.SIMPLIFIED_CHINESE)
                            }
                        )

                        SettingsSectionTitle(text = stringResource(R.string.theme))
                        LanguageOptionRow(
                            text = stringResource(R.string.follow_system),
                            selected = settings.themePreference ==
                                PauseThemePreference.FOLLOW_SYSTEM,
                            onClick = {
                                onThemeChanged(PauseThemePreference.FOLLOW_SYSTEM)
                            }
                        )
                        LanguageOptionRow(
                            text = stringResource(R.string.light),
                            selected = settings.themePreference == PauseThemePreference.LIGHT,
                            onClick = {
                                onThemeChanged(PauseThemePreference.LIGHT)
                            }
                        )
                        LanguageOptionRow(
                            text = stringResource(R.string.dark),
                            selected = settings.themePreference == PauseThemePreference.DARK,
                            onClick = {
                                onThemeChanged(PauseThemePreference.DARK)
                            }
                        )

                        HorizontalDivider()
                        SettingsSectionTitle(text = stringResource(R.string.final_warning))
                        Text(
                            text = stringResource(R.string.warning_time),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        FINAL_WARNING_SECONDS_CHOICES.forEach { seconds ->
                            LanguageOptionRow(
                                text = stringResource(R.string.warning_seconds_option, seconds),
                                selected = settings.finalWarningSeconds == seconds,
                                onClick = {
                                    onFinalWarningSecondsChanged(seconds)
                                }
                            )
                        }
                        SettingsSwitchRow(
                            title = stringResource(R.string.vibration),
                            checked = settings.warningVibrationEnabled,
                            onCheckedChange = onWarningVibrationChanged
                        )

                        HorizontalDivider()
                        SettingsSectionTitle(text = stringResource(R.string.permissions))
                        PermissionStatusRow(
                            title = stringResource(R.string.accessibility),
                            enabled = accessibilityEnabled,
                            onClick = onOpenAccessibilitySettings
                        )
                        PermissionStatusRow(
                            title = stringResource(R.string.notifications),
                            enabled = notificationsEnabled,
                            onClick = onNotificationPermissionClick
                        )

                        HorizontalDivider()
                        SettingsSectionTitle(text = stringResource(R.string.about))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = stringResource(R.string.about_version, versionName),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        TextButton(onClick = { showResetConfirmation = true }) {
                            Text(stringResource(R.string.reset_settings))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun LanguageOptionRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PermissionStatusRow(
    title: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (enabled) {
                    stringResource(R.string.enabled)
                } else {
                    stringResource(R.string.disabled)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onClick) {
            Text(stringResource(R.string.open_settings))
        }
    }
}

@Composable
private fun AccessibilityOffDialog(
    onDismiss: () -> Unit,
    onGoToSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Text(stringResource(R.string.accessibility_off_title))
        },
        text = {
            Text(stringResource(R.string.accessibility_off_message))
        },
        confirmButton = {
            TextButton(onClick = onGoToSettings) {
                Text(stringResource(R.string.go_to_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun DeleteScheduleConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Text(stringResource(R.string.delete_schedule_title))
        },
        text = {
            Text(stringResource(R.string.delete_schedule_message))
        },
        confirmButton = {
            TextButton(onClick = onConfirmDelete) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RestrictionScheduleRow(
    schedule: RestrictionSchedule,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = schedule.timeRangeText(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = schedule.weekdayText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.schedule_mode_app_count,
                        stringResource(schedule.appRestrictionMode.labelResId()),
                        schedule.selectedPackages.size
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Switch(
                checked = schedule.enabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}

@Composable
private fun AppRestrictionModeSelector(
    selectedMode: AppRestrictionMode,
    onSelectedModeChanged: (AppRestrictionMode) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AppRestrictionMode.entries.forEach { mode ->
            AppRestrictionModeOptionRow(
                mode = mode,
                selected = selectedMode == mode,
                onClick = { onSelectedModeChanged(mode) }
            )
        }
    }
}

@Composable
private fun AppRestrictionModeOptionRow(
    mode: AppRestrictionMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(mode.labelResId()),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = stringResource(mode.descriptionResId()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RestrictionScheduleEditDialog(
    schedule: RestrictionSchedule,
    isNewSchedule: Boolean,
    launchableApps: List<LaunchableApp>,
    isLoadingApps: Boolean,
    onDismiss: () -> Unit,
    onSave: (RestrictionSchedule) -> Unit
) {
    var startMinutes by remember(schedule.id) {
        mutableStateOf(schedule.startMinutesOfDay)
    }
    var endMinutes by remember(schedule.id) {
        mutableStateOf(schedule.endMinutesOfDay)
    }
    var weekdays by remember(schedule.id) {
        mutableStateOf(schedule.weekdays)
    }
    var enabled by remember(schedule.id) {
        mutableStateOf(schedule.enabled)
    }
    var appRestrictionMode by remember(schedule.id) {
        mutableStateOf(schedule.appRestrictionMode)
    }
    var selectedPackages by remember(schedule.id) {
        mutableStateOf(schedule.selectedPackages)
    }
    var appSearchQuery by remember(schedule.id) {
        mutableStateOf("")
    }
    var validationError by remember(schedule.id) {
        mutableStateOf<RestrictionScheduleValidationError?>(null)
    }
    val visibleApps = remember(
        launchableApps,
        selectedPackages,
        appSearchQuery
    ) {
        launchableApps
            .filterBySearchQuery(appSearchQuery)
            .sortedForDisplay(selectedPackages)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Text(
                if (isNewSchedule) {
                    stringResource(R.string.add_schedule)
                } else {
                    stringResource(R.string.edit_schedule)
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.enabled),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TimeSettingButton(
                        label = stringResource(R.string.start),
                        minutesSinceMidnight = startMinutes,
                        onTimeChanged = { startMinutes = it },
                        modifier = Modifier.weight(1f)
                    )

                    Text(
                        text = stringResource(R.string.to),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    TimeSettingButton(
                        label = stringResource(R.string.end),
                        minutesSinceMidnight = endMinutes,
                        onTimeChanged = { endMinutes = it },
                        modifier = Modifier.weight(1f)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.weekdays_label),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    WeekdaySelector(
                        selectedWeekdays = weekdays,
                        onSelectedWeekdaysChanged = { weekdays = it }
                    )
                }

                HorizontalDivider()

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.app_mode),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    AppRestrictionModeSelector(
                        selectedMode = appRestrictionMode,
                        onSelectedModeChanged = { selectedMode ->
                            appRestrictionMode = selectedMode
                            validationError = null
                        }
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.apps_for_schedule),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(appRestrictionMode.helperTextResId()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.selected_count, selectedPackages.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (validationError == RestrictionScheduleValidationError.EMPTY_ALLOWLIST) {
                        Text(
                            text = stringResource(R.string.select_at_least_one_allowed_app),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (isLoadingApps) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    } else {
                        OutlinedTextField(
                            value = appSearchQuery,
                            onValueChange = { appSearchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.search_apps)) },
                            singleLine = true,
                            trailingIcon = {
                                if (appSearchQuery.isNotEmpty()) {
                                    TextButton(onClick = { appSearchQuery = "" }) {
                                        Text(stringResource(R.string.clear))
                                    }
                                }
                            }
                        )

                        when {
                            launchableApps.isEmpty() -> {
                                Text(
                                    text = stringResource(R.string.no_launchable_apps),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            visibleApps.isEmpty() -> {
                                Text(
                                    text = stringResource(R.string.no_apps_match_search),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            else -> {
                                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                    visibleApps.forEach { app ->
                                        ScheduleAppSelectionRow(
                                            app = app,
                                            isSelected = app.packageName in selectedPackages,
                                            onCheckedChange = { isSelected ->
                                                selectedPackages = if (isSelected) {
                                                    selectedPackages + app.packageName
                                                } else {
                                                    selectedPackages - app.packageName
                                                }
                                                if (selectedPackages.isNotEmpty()) {
                                                    validationError = null
                                                }
                                            }
                                        )
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            CompactDialogTextButton(
                text = stringResource(R.string.save),
                onClick = {
                    val updatedSchedule = schedule.copy(
                        startMinutesOfDay = startMinutes,
                        endMinutesOfDay = endMinutes,
                        weekdays = weekdays,
                        enabled = enabled,
                        appRestrictionMode = appRestrictionMode,
                        selectedPackages = selectedPackages
                    )
                    val error = validateRestrictionSchedule(updatedSchedule)
                    if (error != null) {
                        validationError = error
                    } else {
                        onSave(updatedSchedule)
                    }
                }
            )
        },
        dismissButton = {
            CompactDialogTextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun WeekdaySelector(
    selectedWeekdays: Set<PauseWeekday>,
    onSelectedWeekdaysChanged: (Set<PauseWeekday>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PauseWeekday.entries.forEach { weekday ->
                WeekdayToggleButton(
                    weekday = weekday,
                    isSelected = weekday in selectedWeekdays,
                    onClick = {
                        onSelectedWeekdaysChanged(
                            if (weekday in selectedWeekdays) {
                                selectedWeekdays - weekday
                            } else {
                                selectedWeekdays + weekday
                            }
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CompactShortcutButton(
                text = stringResource(R.string.every_day),
                onClick = {
                    onSelectedWeekdaysChanged(PauseWeekday.everyDay)
                },
                modifier = Modifier.weight(1f)
            )
            CompactShortcutButton(
                text = stringResource(R.string.weekdays),
                onClick = {
                    onSelectedWeekdaysChanged(PauseWeekday.weekdays)
                },
                modifier = Modifier.weight(1f)
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            CompactShortcutButton(
                text = stringResource(R.string.weekends),
                onClick = {
                    onSelectedWeekdaysChanged(PauseWeekday.weekends)
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun WeekdayToggleButton(
    weekday: PauseWeekday,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chipModifier = modifier
        .height(40.dp)
        .defaultMinSize(minWidth = 1.dp, minHeight = 40.dp)
    val contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)

    if (isSelected) {
        Button(
            onClick = onClick,
            modifier = chipModifier,
            contentPadding = contentPadding
        ) {
            Text(
                text = stringResource(weekday.compactLabelResId()),
                maxLines = 1,
                softWrap = false
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = chipModifier,
            contentPadding = contentPadding
        ) {
            Text(
                text = stringResource(weekday.compactLabelResId()),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun CompactShortcutButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(38.dp)
            .defaultMinSize(minWidth = 1.dp, minHeight = 38.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Text(
            text = text,
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun CompactDialogTextButton(
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Text(
            text = text,
            maxLines = 1,
            softWrap = false
        )
    }
}

private fun RestrictionSchedule.timeRangeText(): String {
    return "${formatMinutesSinceMidnight(startMinutesOfDay)} - " +
        formatMinutesSinceMidnight(endMinutesOfDay)
}

private fun AppRestrictionMode.labelResId(): Int {
    return when (this) {
        AppRestrictionMode.BLOCK_SELECTED -> R.string.block_selected_apps
        AppRestrictionMode.ALLOW_SELECTED_ONLY -> R.string.allow_selected_apps_only
    }
}

private fun AppRestrictionMode.descriptionResId(): Int {
    return when (this) {
        AppRestrictionMode.BLOCK_SELECTED -> R.string.block_selected_apps_description
        AppRestrictionMode.ALLOW_SELECTED_ONLY -> R.string.allow_selected_apps_only_description
    }
}

private fun AppRestrictionMode.helperTextResId(): Int {
    return when (this) {
        AppRestrictionMode.BLOCK_SELECTED -> R.string.select_apps_to_block
        AppRestrictionMode.ALLOW_SELECTED_ONLY -> R.string.select_apps_to_allow
    }
}

@Composable
private fun RestrictionSchedule.weekdayText(): String {
    val resources = LocalContext.current.resources

    return when {
        weekdays.isEmpty() -> stringResource(R.string.no_days_selected)
        weekdays == PauseWeekday.everyDay -> stringResource(R.string.every_day)
        weekdays == PauseWeekday.weekdays -> stringResource(R.string.weekdays)
        weekdays == PauseWeekday.weekends -> stringResource(R.string.weekends)
        else -> weekdays
            .sortedBy { weekday -> weekday.ordinal }
            .joinToString(separator = " ") { weekday ->
                resources.getString(weekday.shortLabelResId())
            }
    }
}

private fun PauseWeekday.compactLabelResId(): Int {
    return when (this) {
        PauseWeekday.MONDAY -> R.string.weekday_monday_compact
        PauseWeekday.TUESDAY -> R.string.weekday_tuesday_compact
        PauseWeekday.WEDNESDAY -> R.string.weekday_wednesday_compact
        PauseWeekday.THURSDAY -> R.string.weekday_thursday_compact
        PauseWeekday.FRIDAY -> R.string.weekday_friday_compact
        PauseWeekday.SATURDAY -> R.string.weekday_saturday_compact
        PauseWeekday.SUNDAY -> R.string.weekday_sunday_compact
    }
}

private fun PauseWeekday.shortLabelResId(): Int {
    return when (this) {
        PauseWeekday.MONDAY -> R.string.weekday_monday_short
        PauseWeekday.TUESDAY -> R.string.weekday_tuesday_short
        PauseWeekday.WEDNESDAY -> R.string.weekday_wednesday_short
        PauseWeekday.THURSDAY -> R.string.weekday_thursday_short
        PauseWeekday.FRIDAY -> R.string.weekday_friday_short
        PauseWeekday.SATURDAY -> R.string.weekday_saturday_short
        PauseWeekday.SUNDAY -> R.string.weekday_sunday_short
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSettingButton(
    label: String,
    minutesSinceMidnight: Int,
    onTimeChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val time = TimeOfDay.fromMinutes(minutesSinceMidnight)
    var isPickerOpen by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { isPickerOpen = true },
        modifier = modifier.height(64.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = time.format(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    if (isPickerOpen) {
        TimeInputDialog(
            label = label,
            initialTime = time,
            onDismiss = { isPickerOpen = false },
            onConfirm = { selectedMinutes ->
                isPickerOpen = false
                onTimeChanged(selectedMinutes)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeInputDialog(
    label: String,
    initialTime: TimeOfDay,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialTime.hour,
        initialMinute = initialTime.minute,
        is24Hour = true
    )
    var isInputMode by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { isInputMode = !isInputMode }) {
                    Text(
                        if (isInputMode) {
                            stringResource(R.string.clock)
                        } else {
                            stringResource(R.string.input)
                        }
                    )
                }
            }
        },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (isInputMode) {
                    TimeInput(state = timePickerState)
                } else {
                    TimePicker(state = timePickerState)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        toMinutesSinceMidnight(
                            hour = timePickerState.hour,
                            minute = timePickerState.minute
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun ScheduleAppSelectionRow(
    app: LaunchableApp,
    isSelected: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isSelected) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIcon(app = app)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Checkbox(
            checked = isSelected,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun AppIcon(app: LaunchableApp) {
    val imageBitmap = remember(app.packageName) {
        app.icon.toBitmap(width = 96, height = 96).asImageBitmap()
    }

    Image(
        bitmap = imageBitmap,
        contentDescription = app.label,
        modifier = Modifier.size(48.dp)
    )
}
