package io.github.xia92.pause

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import io.github.xia92.pause.ui.theme.PauseLightColorScheme

class ExpiredSessionBlockOverlay(
    private val service: AccessibilityService,
    private val appLabel: String,
    private val packageName: String,
    private val languagePreference: PauseLanguagePreference,
    private val themePreference: PauseThemePreference,
    private val onReturnHome: () -> Unit
) {
    private var windowManager: WindowManager? = null
    private var overlayView: FrameLayout? = null

    fun show(): Boolean {
        if (overlayView != null) return true

        return runCatching {
            val overlayWindowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val screenHeight = overlayWindowManager.screenHeightPixels(service)
            val statusBarHeight = overlayWindowManager.topSystemInsetPixels(service)
            val overlayHeight = (screenHeight - statusBarHeight).coerceAtLeast(dp(240))
            val view = createContentView()
            val flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                y = statusBarHeight
                title = "Pause expired session block"
            }

            Log.i(
                TAG,
                "Expired block overlay addView starts: package=$packageName " +
                    "screenHeight=$screenHeight statusBarHeight=$statusBarHeight " +
                    "height=$overlayHeight flags=${params.flags}"
            )
            overlayWindowManager.addView(view, params)
            windowManager = overlayWindowManager
            overlayView = view
            Log.i(
                TAG,
                "Expired block overlay addView succeeded: " +
                    "package=$packageName isAttached=${view.isAttachedToWindow}"
            )
            true
        }.getOrElse { exception ->
            Log.e(
                TAG,
                "Expired block overlay addView failed: package=$packageName " +
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
            Log.i(TAG, "Expired block overlay dismissed: package=$packageName")
        }.onFailure { exception ->
            Log.e(
                TAG,
                "Expired block overlay dismiss failed: package=$packageName " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
        }
    }

    private fun createContentView(): FrameLayout {
        val textContext = service.localizedForPause(languagePreference)
        val colors = ExpiredBlockColors.fromPauseTheme(textContext, themePreference)
        val root = FrameLayout(service).apply {
            setBackgroundColor(colors.scrim)
            isClickable = true
        }

        val card = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = roundedBackground(colors.cardBackground, colors.cardStroke)
        }

        card.addView(
            TextView(service).apply {
                text = textContext.getString(R.string.notification_title, appLabel)
                setTextColor(colors.titleText)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
        )
        card.addView(
            TextView(service).apply {
                text = textContext.getString(R.string.expired_session_message)
                setTextColor(colors.bodyText)
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(10))
            }
        )
        card.addView(
            Button(service).apply {
                text = textContext.getString(R.string.return_home)
                setTextColor(colors.buttonText)
                background = roundedBackground(colors.buttonBackground, colors.buttonStroke)
                setOnClickListener { onReturnHome() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
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

    private fun roundedBackground(fillColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fillColor)
            setStroke(dp(1), strokeColor)
            cornerRadius = dp(8).toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (value * service.resources.displayMetrics.density).toInt()
    }

    private fun WindowManager.screenHeightPixels(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            currentWindowMetrics.bounds.height()
        } else {
            @Suppress("DEPRECATION")
            context.resources.displayMetrics.heightPixels
        }
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

    private data class ExpiredBlockColors(
        val scrim: Int,
        val cardBackground: Int,
        val cardStroke: Int,
        val titleText: Int,
        val bodyText: Int,
        val buttonBackground: Int,
        val buttonStroke: Int,
        val buttonText: Int
    ) {
        companion object {
            fun fromPauseTheme(
                context: Context,
                themePreference: PauseThemePreference
            ): ExpiredBlockColors {
                val isDark = context.isDarkThemeForPause(themePreference)

                return if (isDark) {
                    ExpiredBlockColors(
                        scrim = Color.argb(190, 0, 0, 0),
                        cardBackground = Color.rgb(36, 38, 42),
                        cardStroke = Color.rgb(66, 70, 76),
                        titleText = Color.rgb(242, 244, 247),
                        bodyText = Color.rgb(210, 216, 224),
                        buttonBackground = Color.rgb(52, 56, 62),
                        buttonStroke = Color.rgb(83, 89, 98),
                        buttonText = Color.rgb(246, 248, 250)
                    )
                } else {
                    val scheme = PauseLightColorScheme
                    ExpiredBlockColors(
                        scrim = Color.argb(130, 0, 0, 0),
                        cardBackground = scheme.surface.toArgb(),
                        cardStroke = scheme.outline.toArgb(),
                        titleText = scheme.onSurface.toArgb(),
                        bodyText = scheme.onSurfaceVariant.toArgb(),
                        buttonBackground = scheme.surfaceVariant.toArgb(),
                        buttonStroke = scheme.outline.toArgb(),
                        buttonText = scheme.onSurface.toArgb()
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "PauseExpiry"
    }
}
