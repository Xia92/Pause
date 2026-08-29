package io.github.xia92.pause

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import io.github.xia92.pause.ui.theme.PauseLightColorScheme

class SessionPromptOverlay(
    private val service: AccessibilityService,
    private val appLabel: String,
    private val packageName: String,
    private val languagePreference: PauseLanguagePreference,
    private val themePreference: PauseThemePreference,
    private val onDurationSelected: (Int) -> Unit,
    private val onLeave: () -> Unit
) {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    fun show(): Boolean {
        Log.i(TAG, "Overlay creation starts: package=$packageName appLabel=$appLabel")
        Log.i(OVERLAY_TAG, "showPrompt requested for package=$packageName")

        if (overlayView != null) {
            Log.i(TAG, "Overlay already has a root view: package=$packageName")
            Log.i(OVERLAY_TAG, "showPrompt already has overlay for package=$packageName")
            return true
        }

        return runCatching {
            val overlayWindowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            Log.i(
                TAG,
                "WindowManager obtained from AccessibilityService: ${overlayWindowManager.javaClass.name}"
            )

            val screenHeight = overlayWindowManager.screenHeightPixels(service)
            val statusBarHeight = overlayWindowManager.topSystemInsetPixels(service)
            val overlayHeight = service.overlayHeightBelowStatusBar(
                statusBarHeight = statusBarHeight,
                screenHeight = screenHeight
            )
            val view = createContentView(statusBarHeight)
            Log.i(TAG, "Overlay root view created: rootClass=${view.javaClass.name}")

            Log.i(
                TAG,
                "Overlay status bar inset: package=$packageName " +
                    "screenHeight=$screenHeight statusBarHeight=$statusBarHeight " +
                    "overlayHeight=$overlayHeight"
            )

            val overlayFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                overlayFlags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                y = statusBarHeight
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                title = "Pause session prompt"
            }
            Log.i(
                TAG,
                "Overlay layout params: width=${params.width} height=${params.height} " +
                    "type=${params.type} flags=${params.flags} format=${params.format} " +
                    "gravity=${params.gravity} y=${params.y} focusable=${params.isFocusable()} " +
                    "touchable=${params.isTouchable()} touchModal=${params.isTouchModal()} " +
                    "watchOutsideTouch=${params.watchesOutsideTouch()}"
            )

            Log.i(OVERLAY_TAG, "WindowManager.addView started for package=$packageName")
            overlayWindowManager.addView(view, params)
            windowManager = overlayWindowManager
            overlayView = view
            Log.i(OVERLAY_TAG, "WindowManager.addView success for package=$packageName")
            Log.i(
                TAG,
                "WindowManager.addView succeeded: package=$packageName " +
                    "isAttached=${view.isAttachedToWindow}"
            )

            view.post {
                Log.i(
                    TAG,
                    "Overlay root attached check: package=$packageName " +
                        "isAttached=${view.isAttachedToWindow} width=${view.width} " +
                        "height=${view.height} visibility=${view.visibility}"
                )
            }

            view.requestFocus()
            true
        }.getOrElse { exception ->
            Log.e(
                TAG,
                "Overlay creation failed: package=$packageName " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
            Log.e(
                OVERLAY_TAG,
                "WindowManager.addView failed for package=$packageName " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
            false
        }
    }

    fun dismiss() {
        val view = overlayView ?: return
        val overlayWindowManager = windowManager ?: return
        overlayView = null
        windowManager = null

        runCatching {
            overlayWindowManager.removeView(view)
        }.onSuccess {
            Log.i(TAG, "Overlay dismissed: package=$packageName")
        }.onFailure { exception ->
            Log.e(
                TAG,
                "Overlay dismiss failed: package=$packageName " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
        }
    }

    private fun createContentView(statusBarHeight: Int): View {
        val textContext = service.localizedForPause(languagePreference)
        val colors = SessionPromptColors.fromPauseTheme(textContext, themePreference)
        Log.i(TAG, "Overlay theme detected: isDark=${colors.isDark}")

        val root = FrameLayout(service).apply {
            setBackgroundColor(colors.scrim)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnKeyListener { _, keyCode, _ -> keyCode == KeyEvent.KEYCODE_BACK }
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_OUTSIDE
                ) {
                    Log.i(
                        TAG,
                        "Overlay touch event: action=${event.actionMaskedName()} " +
                            "localY=${event.y} rawY=${event.rawY} " +
                            "statusBarHeight=$statusBarHeight " +
                            "insideStatusBarRegion=${event.rawY < statusBarHeight}"
                    )
                }
                false
            }
        }

        val customInput = EditText(service).apply {
            hint = textContext.getString(R.string.custom_minutes)
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textSize = 16f
            minHeight = dp(48)
            minimumHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            setSingleLine(true)
            setTextColor(colors.primaryText)
            setHintTextColor(colors.secondaryText)
            background = roundedBackground(colors.inputBackground, colors.inputStroke)
            setOnFocusChangeListener { _, hasFocus ->
                background = roundedBackground(
                    fillColor = colors.inputBackground,
                    strokeColor = if (hasFocus) {
                        colors.inputFocusedStroke
                    } else {
                        colors.inputStroke
                    }
                )
            }
        }
        val errorText = TextView(service).apply {
            text = textContext.getString(R.string.custom_minutes_error)
            setTextColor(colors.errorText)
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = roundedBackground(colors.cardBackground, colors.cardStroke)
        }

        card.addView(
            TextView(service).apply {
                text = textContext.getString(R.string.app_name)
                setTextColor(colors.primaryText)
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            },
            fullWidthLayoutParams(topMargin = 0)
        )
        card.addView(
            TextView(service).apply {
                text = textContext.getString(R.string.session_prompt_question)
                setTextColor(colors.primaryText)
                textSize = 17f
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, 0)
            }
        )
        card.addView(
            TextView(service).apply {
                text = "$appLabel\n$packageName"
                setTextColor(colors.secondaryText)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(12))
            }
        )

        listOf(5, 10, 15, 30).forEach { minutes ->
            card.addView(
                createActionButton(
                    label = textContext.getString(R.string.duration_minutes, minutes),
                    colors = colors,
                    style = PromptButtonStyle.NEUTRAL
                ) {
                    onDurationSelected(minutes)
                }
            )
        }

        card.addView(customInput, fullWidthLayoutParams(topMargin = dp(10)))
        card.addView(errorText, fullWidthLayoutParams(topMargin = dp(6)))
        card.addView(
            createActionButton(
                label = textContext.getString(R.string.start_custom),
                colors = colors,
                style = PromptButtonStyle.PRIMARY
            ) {
                val customMinutes = customInput.text.toString().trim().toIntOrNull()
                if (customMinutes == null || customMinutes <= 0) {
                    errorText.visibility = View.VISIBLE
                } else {
                    errorText.visibility = View.GONE
                    onDurationSelected(customMinutes)
                }
            },
            fullWidthLayoutParams(topMargin = dp(8))
        )
        card.addView(
            createActionButton(
                label = textContext.getString(R.string.cancel_leave),
                colors = colors,
                style = PromptButtonStyle.EXIT
            ) {
                onLeave()
            },
            fullWidthLayoutParams(topMargin = dp(12))
        )

        root.addView(
            card,
            FrameLayout.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = dp(24)
                rightMargin = dp(24)
            }
        )

        return root
    }

    private fun createActionButton(
        label: String,
        colors: SessionPromptColors,
        style: PromptButtonStyle,
        onClick: () -> Unit
    ): Button {
        val buttonColors = colors.buttonColors(style)
        return Button(service).apply {
            text = label
            setAllCaps(false)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(46)
            minimumHeight = dp(46)
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(buttonColors.text)
            background = roundedBackground(buttonColors.background, buttonColors.stroke)
            setOnClickListener { onClick() }
        }.also { button ->
            button.layoutParams = fullWidthLayoutParams(topMargin = dp(8))
        }
    }

    private fun roundedBackground(fillColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
            cornerRadius = dp(8).toFloat()
        }
    }

    private fun fullWidthLayoutParams(topMargin: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            this.topMargin = topMargin
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

    private fun WindowManager.topSystemInsetPixels(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val statusBarInset = currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                .top

            if (statusBarInset > 0) {
                statusBarInset
            } else {
                context.statusBarHeightResourcePixels()
            }
        } else {
            context.statusBarHeightResourcePixels()
        }
    }

    private fun Context.overlayHeightBelowStatusBar(
        statusBarHeight: Int,
        screenHeight: Int
    ): Int {
        return (screenHeight - statusBarHeight).coerceAtLeast(dp(240))
    }

    private fun WindowManager.screenHeightPixels(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            currentWindowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            context.resources.displayMetrics.heightPixels
        }
    }

    private fun WindowManager.LayoutParams.isFocusable(): Boolean {
        return flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
    }

    private fun WindowManager.LayoutParams.isTouchable(): Boolean {
        return flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0
    }

    private fun WindowManager.LayoutParams.isTouchModal(): Boolean {
        return flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL == 0
    }

    private fun WindowManager.LayoutParams.watchesOutsideTouch(): Boolean {
        return flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH != 0
    }

    private fun MotionEvent.actionMaskedName(): String {
        return when (actionMasked) {
            MotionEvent.ACTION_DOWN -> "ACTION_DOWN"
            MotionEvent.ACTION_OUTSIDE -> "ACTION_OUTSIDE"
            MotionEvent.ACTION_UP -> "ACTION_UP"
            MotionEvent.ACTION_MOVE -> "ACTION_MOVE"
            MotionEvent.ACTION_CANCEL -> "ACTION_CANCEL"
            else -> actionMasked.toString()
        }
    }

    private data class SessionPromptColors(
        val isDark: Boolean,
        val scrim: Int,
        val cardBackground: Int,
        val cardStroke: Int,
        val primaryText: Int,
        val secondaryText: Int,
        val neutralButtonBackground: Int,
        val neutralButtonStroke: Int,
        val neutralButtonText: Int,
        val primaryButtonBackground: Int,
        val primaryButtonStroke: Int,
        val primaryButtonText: Int,
        val exitButtonBackground: Int,
        val exitButtonStroke: Int,
        val exitButtonText: Int,
        val inputBackground: Int,
        val inputStroke: Int,
        val inputFocusedStroke: Int,
        val errorText: Int
    ) {
        fun buttonColors(style: PromptButtonStyle): PromptButtonColors {
            return when (style) {
                PromptButtonStyle.NEUTRAL -> PromptButtonColors(
                    background = neutralButtonBackground,
                    stroke = neutralButtonStroke,
                    text = neutralButtonText
                )
                PromptButtonStyle.PRIMARY -> PromptButtonColors(
                    background = primaryButtonBackground,
                    stroke = primaryButtonStroke,
                    text = primaryButtonText
                )
                PromptButtonStyle.EXIT -> PromptButtonColors(
                    background = exitButtonBackground,
                    stroke = exitButtonStroke,
                    text = exitButtonText
                )
            }
        }

        companion object {
            fun fromPauseTheme(
                context: Context,
                themePreference: PauseThemePreference
            ): SessionPromptColors {
                val isDark = context.isDarkThemeForPause(themePreference)

                return if (isDark) {
                    SessionPromptColors(
                        isDark = true,
                        scrim = Color.argb(205, 0, 0, 0),
                        cardBackground = Color.rgb(32, 36, 43),
                        cardStroke = Color.rgb(62, 72, 84),
                        primaryText = Color.rgb(242, 244, 247),
                        secondaryText = Color.rgb(169, 180, 192),
                        neutralButtonBackground = Color.rgb(43, 49, 57),
                        neutralButtonStroke = Color.rgb(73, 85, 99),
                        neutralButtonText = Color.rgb(243, 246, 250),
                        primaryButtonBackground = Color.rgb(48, 105, 157),
                        primaryButtonStroke = Color.rgb(85, 145, 198),
                        primaryButtonText = Color.rgb(246, 250, 255),
                        exitButtonBackground = Color.rgb(41, 39, 48),
                        exitButtonStroke = Color.rgb(105, 77, 88),
                        exitButtonText = Color.rgb(231, 194, 204),
                        inputBackground = Color.rgb(23, 27, 32),
                        inputStroke = Color.rgb(78, 91, 106),
                        inputFocusedStroke = Color.rgb(132, 184, 255),
                        errorText = Color.rgb(255, 180, 171)
                    )
                } else {
                    val scheme = PauseLightColorScheme
                    SessionPromptColors(
                        isDark = false,
                        scrim = Color.argb(135, 0, 0, 0),
                        cardBackground = scheme.surface.toArgb(),
                        cardStroke = scheme.outline.toArgb(),
                        primaryText = scheme.onSurface.toArgb(),
                        secondaryText = scheme.onSurfaceVariant.toArgb(),
                        neutralButtonBackground = scheme.surfaceVariant.toArgb(),
                        neutralButtonStroke = scheme.outline.toArgb(),
                        neutralButtonText = scheme.onSurface.toArgb(),
                        primaryButtonBackground = scheme.primary.toArgb(),
                        primaryButtonStroke = scheme.primary.toArgb(),
                        primaryButtonText = scheme.onPrimary.toArgb(),
                        exitButtonBackground = scheme.surface.toArgb(),
                        exitButtonStroke = scheme.outline.toArgb(),
                        exitButtonText = scheme.onSurfaceVariant.toArgb(),
                        inputBackground = scheme.surface.toArgb(),
                        inputStroke = scheme.outline.toArgb(),
                        inputFocusedStroke = scheme.primary.toArgb(),
                        errorText = scheme.error.toArgb()
                    )
                }
            }
        }
    }

    private enum class PromptButtonStyle {
        NEUTRAL,
        PRIMARY,
        EXIT
    }

    private data class PromptButtonColors(
        val background: Int,
        val stroke: Int,
        val text: Int
    )

    companion object {
        private const val TAG = "PauseSession"
        private const val OVERLAY_TAG = "PauseOverlay"
    }
}
