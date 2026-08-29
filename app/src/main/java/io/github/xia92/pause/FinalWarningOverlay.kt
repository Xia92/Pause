package io.github.xia92.pause

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import io.github.xia92.pause.ui.theme.PauseLightColorScheme

class FinalWarningOverlay(
    private val service: AccessibilityService,
    private val appLabel: String,
    private val packageName: String,
    private val languagePreference: PauseLanguagePreference,
    private val themePreference: PauseThemePreference,
    private val onAcknowledged: () -> Unit,
    private val onEndSession: () -> Unit
) {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var remainingTextView: TextView? = null

    fun showOrUpdate(
        remainingSeconds: Int,
        playHaptic: Boolean
    ): Boolean {
        return if (overlayView == null) {
            show(
                remainingSeconds = remainingSeconds,
                playHaptic = playHaptic
            )
        } else {
            updateRemainingSeconds(remainingSeconds)
            true
        }
    }

    fun dismiss() {
        val view = overlayView ?: return
        val overlayWindowManager = windowManager ?: return
        overlayView = null
        remainingTextView = null
        windowManager = null

        runCatching {
            overlayWindowManager.removeView(view)
        }.onSuccess {
            Log.i(TAG, "Final warning overlay removed: package=$packageName")
        }.onFailure { exception ->
            Log.e(
                TAG,
                "Final warning overlay dismiss failed: package=$packageName " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
        }
    }

    private fun show(
        remainingSeconds: Int,
        playHaptic: Boolean
    ): Boolean {
        Log.i(
            TAG,
            "Final warning overlay creation starts: " +
                "package=$packageName appLabel=$appLabel " +
                "remainingSeconds=$remainingSeconds playHaptic=$playHaptic"
        )

        return runCatching {
            val overlayWindowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val overlayBounds = overlayWindowManager.overlayBounds(service)
            val view = createContentView(
                remainingSeconds = remainingSeconds,
                overlayBounds = overlayBounds
            )
            val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayBounds.usableHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                y = overlayBounds.topInset
                title = "Pause final warning"
            }

            Log.i(
                TAG,
                "Final warning layout params: package=$packageName " +
                    "width=${params.width} height=${params.height} type=${params.type} " +
                    "flags=${params.flags} gravity=${params.gravity} y=${params.y} " +
                    "screenWidth=${overlayBounds.screenWidth} " +
                    "screenHeight=${overlayBounds.screenHeight} " +
                    "topInset=${overlayBounds.topInset} " +
                    "bottomInset=${overlayBounds.bottomInset} " +
                    "cardWidth=${overlayBounds.cardWidth}"
            )

            overlayWindowManager.addView(view, params)
            windowManager = overlayWindowManager
            overlayView = view
            Log.i(
                TAG,
                "Final warning overlay created: " +
                    "package=$packageName isAttached=${view.isAttachedToWindow}"
            )
            if (playHaptic) {
                playHapticCue()
            }
            true
        }.getOrElse { exception ->
            Log.e(
                TAG,
                "Final warning overlay creation failed: package=$packageName " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
            false
        }
    }

    private fun updateRemainingSeconds(remainingSeconds: Int) {
        val textView = remainingTextView
        if (textView == null) {
            Log.w(
                TAG,
                "Final warning text update skipped; text view is missing: " +
                    "package=$packageName remainingSeconds=$remainingSeconds"
            )
            return
        }

        textView.text = remainingSeconds.toString()
        Log.i(
            TAG,
            "Final warning text updated: package=$packageName " +
                "remainingSeconds=$remainingSeconds"
        )
    }

    private fun createContentView(
        remainingSeconds: Int,
        overlayBounds: OverlayBounds
    ): View {
        val textContext = service.localizedForPause(languagePreference)
        val colors = FinalWarningColors.fromPauseTheme(textContext, themePreference)

        return FrameLayout(service).apply {
            setBackgroundColor(colors.scrim)
            isClickable = true
            isFocusable = false

            addView(
                createWarningCard(
                    remainingSeconds = remainingSeconds,
                    colors = colors,
                    textContext = textContext
                ),
                FrameLayout.LayoutParams(
                    overlayBounds.cardWidth,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }
    }

    private fun createWarningCard(
        remainingSeconds: Int,
        colors: FinalWarningColors,
        textContext: Context
    ): View {
        return LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(26), dp(28), dp(24))
            background = roundedBackground(colors.background, colors.stroke)
            elevation = dp(18).toFloat()

            addView(
                TextView(service).apply {
                    text = textContext.getString(R.string.app_name)
                    setTextColor(colors.titleText)
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                }
            )
            addView(
                TextView(service).apply {
                    text = appLabel
                    setTextColor(colors.appText)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setPadding(0, dp(6), 0, 0)
                }
            )
            addView(
                TextView(service).apply {
                    text = remainingSeconds.toString()
                    setTextColor(colors.countdownText)
                    textSize = 76f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setPadding(0, dp(22), 0, 0)
                    remainingTextView = this
                }
            )
            addView(
                TextView(service).apply {
                    text = textContext.getString(R.string.final_warning_seconds_left)
                    setTextColor(colors.bodyText)
                    textSize = 17f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(0, dp(2), 0, 0)
                }
            )
            addView(
                TextView(service).apply {
                    text = textContext.getString(R.string.final_warning_wrap_up)
                    setTextColor(colors.bodyText)
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(16), 0, 0)
                }
            )
            addView(
                LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(24), 0, 0)

                    addView(
                        createButton(
                            label = textContext.getString(R.string.final_warning_got_it),
                            backgroundColor = colors.primaryButtonBackground,
                            strokeColor = colors.primaryButtonStroke,
                            textColor = colors.primaryButtonText,
                            onClick = onAcknowledged
                        ),
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    )
                    addView(
                        createButton(
                            label = textContext.getString(R.string.final_warning_end_session),
                            backgroundColor = colors.endButtonBackground,
                            strokeColor = colors.endButtonStroke,
                            textColor = colors.endButtonText,
                            onClick = onEndSession
                        ),
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        ).apply {
                            leftMargin = dp(10)
                        }
                    )
                }
            )
        }
    }

    private fun createButton(
        label: String,
        backgroundColor: Int,
        strokeColor: Int,
        textColor: Int,
        onClick: () -> Unit
    ): Button {
        return Button(service).apply {
            text = label
            setAllCaps(false)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            background = roundedBackground(backgroundColor, strokeColor)
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onClick() }
        }
    }

    private fun playHapticCue() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = service.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                service.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (!vibrator.hasVibrator()) {
                Log.i(TAG, "Final warning haptic skipped; device has no vibrator.")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        HAPTIC_DURATION_MILLIS,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(HAPTIC_DURATION_MILLIS)
            }
            Log.i(
                TAG,
                "Final warning haptic played: package=$packageName " +
                    "durationMillis=$HAPTIC_DURATION_MILLIS"
            )
        }.onFailure { exception ->
            Log.e(
                TAG,
                "Final warning haptic failed: package=$packageName " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
        }
    }

    private fun roundedBackground(fillColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
            cornerRadius = dp(10).toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (value * service.resources.displayMetrics.density).toInt()
    }

    private fun Context.statusBarHeightResourcePixels(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            dp(24)
        }
    }

    private fun Context.navigationBarHeightResourcePixels(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    private fun WindowManager.overlayBounds(context: Context): OverlayBounds {
        val displayMetrics = context.resources.displayMetrics
        val screenWidth: Int
        val screenHeight: Int
        val systemBarInsets: SystemBarInsets

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = currentWindowMetrics
            screenWidth = metrics.bounds.width()
            screenHeight = metrics.bounds.height()
            val insets = metrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())

            systemBarInsets = SystemBarInsets(
                top = insets.top.takeIf { it > 0 } ?: context.statusBarHeightResourcePixels(),
                bottom = insets.bottom.coerceAtLeast(0)
            )
        } else {
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
            systemBarInsets = SystemBarInsets(
                top = context.statusBarHeightResourcePixels(),
                bottom = context.navigationBarHeightResourcePixels()
            )
        }

        val usableHeight = (
            screenHeight - systemBarInsets.top - systemBarInsets.bottom
            ).coerceAtLeast(displayMetrics.heightPixels / 2)
        val cardWidth = (screenWidth * CARD_WIDTH_FRACTION).toInt()
            .coerceAtMost(screenWidth - dp(32))
            .coerceAtLeast(dp(280).coerceAtMost(screenWidth - dp(32)))

        return OverlayBounds(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            topInset = systemBarInsets.top,
            bottomInset = systemBarInsets.bottom,
            usableHeight = usableHeight,
            cardWidth = cardWidth
        )
    }

    private data class OverlayBounds(
        val screenWidth: Int,
        val screenHeight: Int,
        val topInset: Int,
        val bottomInset: Int,
        val usableHeight: Int,
        val cardWidth: Int
    )

    private data class SystemBarInsets(
        val top: Int,
        val bottom: Int
    )

    private data class FinalWarningColors(
        val scrim: Int,
        val background: Int,
        val stroke: Int,
        val titleText: Int,
        val appText: Int,
        val bodyText: Int,
        val countdownText: Int,
        val primaryButtonBackground: Int,
        val primaryButtonStroke: Int,
        val primaryButtonText: Int,
        val endButtonBackground: Int,
        val endButtonStroke: Int,
        val endButtonText: Int
    ) {
        companion object {
            fun fromPauseTheme(
                context: Context,
                themePreference: PauseThemePreference
            ): FinalWarningColors {
                val isDark = context.isDarkThemeForPause(themePreference)

                return if (isDark) {
                    FinalWarningColors(
                        scrim = Color.argb(190, 0, 0, 0),
                        background = Color.rgb(24, 28, 33),
                        stroke = Color.rgb(62, 72, 84),
                        titleText = Color.rgb(245, 247, 250),
                        appText = Color.rgb(159, 174, 190),
                        bodyText = Color.rgb(205, 214, 224),
                        countdownText = Color.rgb(132, 184, 255),
                        primaryButtonBackground = Color.rgb(43, 82, 120),
                        primaryButtonStroke = Color.rgb(79, 124, 166),
                        primaryButtonText = Color.rgb(245, 249, 255),
                        endButtonBackground = Color.rgb(67, 49, 56),
                        endButtonStroke = Color.rgb(132, 87, 99),
                        endButtonText = Color.rgb(255, 238, 242)
                    )
                } else {
                    val scheme = PauseLightColorScheme
                    FinalWarningColors(
                        scrim = Color.argb(178, 0, 0, 0),
                        background = scheme.surface.toArgb(),
                        stroke = scheme.outline.toArgb(),
                        titleText = scheme.onSurface.toArgb(),
                        appText = scheme.onSurfaceVariant.toArgb(),
                        bodyText = scheme.onSurfaceVariant.toArgb(),
                        countdownText = scheme.primary.toArgb(),
                        primaryButtonBackground = scheme.primaryContainer.toArgb(),
                        primaryButtonStroke = scheme.primary.toArgb(),
                        primaryButtonText = scheme.onPrimaryContainer.toArgb(),
                        endButtonBackground = scheme.surfaceVariant.toArgb(),
                        endButtonStroke = scheme.outline.toArgb(),
                        endButtonText = scheme.onSurfaceVariant.toArgb()
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "PauseExpiry"
        private const val CARD_WIDTH_FRACTION = 0.8f
        private const val HAPTIC_DURATION_MILLIS = 180L
    }
}
