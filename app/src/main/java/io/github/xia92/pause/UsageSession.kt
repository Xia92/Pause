package io.github.xia92.pause

data class UsageSession(
    val packageName: String,
    val allowedUntilMillis: Long
) {
    fun isValidAt(currentTimeMillis: Long): Boolean {
        return currentTimeMillis < allowedUntilMillis
    }

    fun authorizes(targetPackageName: String, currentTimeMillis: Long): Boolean {
        return packageName == targetPackageName && isValidAt(currentTimeMillis)
    }
}

fun createAllowedUntilMillis(
    currentTimeMillis: Long,
    durationMinutes: Int
): Long {
    require(durationMinutes > 0) { "Duration must be positive." }
    return currentTimeMillis + durationMinutes * 60_000L
}

fun savedAllowedUntilForPackage(
    activeUsageSessions: Map<String, Long>,
    packageName: String
): Long? {
    return activeUsageSessions[packageName]
}

fun hasValidSessionForPackage(
    activeUsageSessions: Map<String, Long>,
    packageName: String,
    currentTimeMillis: Long
): Boolean {
    val allowedUntilMillis = savedAllowedUntilForPackage(activeUsageSessions, packageName)
        ?: return false

    return UsageSession(packageName, allowedUntilMillis)
        .authorizes(packageName, currentTimeMillis)
}

fun withUsageSessionForPackage(
    activeUsageSessions: Map<String, Long>,
    packageName: String,
    allowedUntilMillis: Long
): Map<String, Long> {
    return activeUsageSessions + (packageName to allowedUntilMillis)
}

fun withoutUsageSessionForPackage(
    activeUsageSessions: Map<String, Long>,
    packageName: String
): Map<String, Long> {
    return activeUsageSessions - packageName
}
