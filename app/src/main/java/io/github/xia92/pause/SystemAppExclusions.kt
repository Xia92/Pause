package io.github.xia92.pause

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager

private val BASE_WHITELIST_EXCLUDED_PACKAGES = setOf(
    "android",
    "com.android.systemui",
    "com.android.settings",
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.android.providers.settings",
    "com.google.android.gms",
    "com.coloros.safecenter",
    "com.oplus.safecenter",
    "com.coloros.securitypermission",
    "com.oplus.securitypermission"
)

@Volatile
private var loggedSecureInputMethodReadBlocked = false

fun buildWhitelistSystemExcludedPackages(context: Context): Set<String> {
    return buildSet {
        add(context.packageName)
        addAll(BASE_WHITELIST_EXCLUDED_PACKAGES)
        addAll(resolveHomePackageNames(context))
        addAll(resolveInputMethodPackageNames(context))
    }
}

private fun resolveHomePackageNames(context: Context): Set<String> {
    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
    }
    val packageManager = context.packageManager
    val queriedPackages = packageManager.queryIntentActivitiesCompat(homeIntent)
        .mapNotNull { resolveInfo -> resolveInfo.activityInfo?.packageName }
    val resolvedPackage = packageManager.resolveActivityCompat(homeIntent)
        ?.activityInfo
        ?.packageName

    return (queriedPackages + listOfNotNull(resolvedPackage))
        .filter { packageName -> packageName.isNotBlank() }
        .toSet()
}

private fun resolveInputMethodPackageNames(context: Context): Set<String> {
    val inputMethodManagerPackageNames = resolveInputMethodPackageNamesFromManager(context)
    val secureSettingsPackageNames = resolveInputMethodPackageNamesFromSecureSettings(context)

    return inputMethodManagerPackageNames + secureSettingsPackageNames
}

private fun resolveInputMethodPackageNamesFromManager(context: Context): Set<String> {
    val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE)
        as? InputMethodManager
        ?: return emptySet()

    return runCatching {
        inputMethodManager.enabledInputMethodList
            .map { inputMethodInfo -> inputMethodInfo.packageName }
            .filter { packageName -> packageName.isNotBlank() }
            .toSet()
    }.getOrElse { exception ->
        Log.w(
            TAG,
            "Input method package lookup failed through InputMethodManager: " +
                "exception=${exception.javaClass.name} message=${exception.message}"
        )
        emptySet()
    }
}

private fun resolveInputMethodPackageNamesFromSecureSettings(context: Context): Set<String> {
    if (context.applicationInfo.targetSdkVersion > Build.VERSION_CODES.TIRAMISU) {
        logSecureInputMethodSettingsSkipped(
            "targetSdk=${context.applicationInfo.targetSdkVersion}"
        )
        return emptySet()
    }

    val packageNames = mutableSetOf<String>()

    readSecureSettingString(context, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.packageNameFromComponent()
        ?.let(packageNames::add)

    readSecureSettingString(context, Settings.Secure.ENABLED_INPUT_METHODS)
        ?.split(':')
        ?.mapNotNull { componentText -> componentText.packageNameFromComponent() }
        ?.let(packageNames::addAll)

    return packageNames
}

private fun readSecureSettingString(context: Context, key: String): String? {
    return runCatching {
        Settings.Secure.getString(context.contentResolver, key)
    }.getOrElse { exception ->
        if (exception is SecurityException) {
            logSecureInputMethodSettingsSkipped(
                "key=$key exception=${exception.javaClass.name} message=${exception.message}"
            )
        }
        null
    }
}

private fun logSecureInputMethodSettingsSkipped(reason: String) {
    if (loggedSecureInputMethodReadBlocked) return

    loggedSecureInputMethodReadBlocked = true
    Log.i(
        TAG,
        "Secure input-method settings skipped for system exclusion lookup: $reason"
    )
}

private fun String.packageNameFromComponent(): String? {
    return substringBefore('/').takeIf { packageName -> packageName.isNotBlank() }
}

private fun PackageManager.queryIntentActivitiesCompat(intent: Intent): List<ResolveInfo> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, 0)
    }
}

private fun PackageManager.resolveActivityCompat(intent: Intent): ResolveInfo? {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        resolveActivity(intent, 0)
    }
}

private const val TAG = "PauseRestriction"
