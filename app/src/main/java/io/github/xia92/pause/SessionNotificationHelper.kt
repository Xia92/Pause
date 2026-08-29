package io.github.xia92.pause

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class SessionNotificationHelper(private val context: Context) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun logPermissionState(source: String) {
        Log.i(
            TAG,
            "Notification permission state: source=$source " +
                "sdk=${Build.VERSION.SDK_INT} " +
                "runtimeGranted=${isRuntimePermissionGranted()} " +
                "notificationsEnabled=${areNotificationsEnabled()} " +
                "canPost=${canPostNotifications()}"
        )
    }

    fun showSessionNotification(
        packageName: String,
        appLabel: String,
        allowedUntilMillis: Long,
        languagePreference: PauseLanguagePreference
    ) {
        val remainingMillis = (allowedUntilMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        if (remainingMillis == 0L) {
            Log.i(
                TAG,
                "Notification not shown because session is already expired: " +
                    "package=$packageName allowedUntil=$allowedUntilMillis"
            )
            removeSessionNotification(packageName)
            return
        }

        val textContext = context.localizedForPause(languagePreference)
        createNotificationChannel(textContext)
        logPermissionState(source = "showSessionNotification")

        if (!canPostNotifications()) {
            Log.i(
                TAG,
                "Notification not shown because notifications are not allowed: " +
                    "package=$packageName"
            )
            return
        }

        val notificationId = notificationIdForPackage(packageName)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pause_notification)
            .setContentTitle(textContext.getString(R.string.notification_title, appLabel))
            .setContentText(textContext.getString(R.string.notification_time_remaining))
            .setWhen(allowedUntilMillis)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter(remainingMillis)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        runCatching {
            notificationManager.notify(notificationId, notification)
        }.onSuccess {
            Log.i(
                TAG,
                "Notification shown/updated: package=$packageName id=$notificationId " +
                    "allowedUntil=$allowedUntilMillis remainingMillis=$remainingMillis"
            )
        }.onFailure { exception ->
            Log.e(
                TAG,
                "Notification show failed: package=$packageName id=$notificationId " +
                    "exception=${exception.javaClass.name} message=${exception.message}",
                exception
            )
        }
    }

    fun removeSessionNotification(packageName: String) {
        val notificationId = notificationIdForPackage(packageName)
        notificationManager.cancel(notificationId)
        Log.i(TAG, "Notification removed: package=$packageName id=$notificationId")
    }

    private fun canPostNotifications(): Boolean {
        return isRuntimePermissionGranted() && areNotificationsEnabled()
    }

    private fun isRuntimePermissionGranted(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun areNotificationsEnabled(): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun createNotificationChannel(textContext: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            textContext.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = textContext.getString(R.string.notification_channel_description)
            setSound(null, null)
        }

        notificationManager.createNotificationChannel(channel)
    }

    private fun notificationIdForPackage(packageName: String): Int {
        return packageName.hashCode() and Int.MAX_VALUE
    }

    companion object {
        private const val TAG = "PauseNotification"
        internal const val CHANNEL_ID = "pause_active_sessions"
    }
}
