package io.github.xia92.pause

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

fun isPauseAccessibilityServiceEnabled(context: Context): Boolean {
    val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
        as? AccessibilityManager
        ?: return false
    val expectedPackageName = context.packageName
    val expectedServiceClassName = ForegroundAppAccessibilityService::class.java.name

    return runCatching {
        accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
    }.getOrElse { exception ->
        Log.e(
            TAG,
            "Accessibility enabled-service lookup failed: " +
                "exception=${exception.javaClass.name} message=${exception.message}",
            exception
        )
        emptyList()
    }.any { service ->
        val serviceInfo = service.resolveInfo?.serviceInfo
        serviceInfo?.packageName == expectedPackageName &&
            serviceInfo.name == expectedServiceClassName
    }
}

fun isPauseNotificationPermissionEnabled(context: Context): Boolean {
    val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    if (!runtimePermissionGranted) return false
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val channel = notificationManager.getNotificationChannel(
            SessionNotificationHelper.CHANNEL_ID
        )
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
    }

    return true
}

fun shouldRequestPostNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
}

fun openPauseAccessibilitySettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun openPauseNotificationSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    }

    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private const val TAG = "PausePermission"
