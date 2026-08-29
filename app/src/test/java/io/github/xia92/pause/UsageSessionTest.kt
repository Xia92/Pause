package io.github.xia92.pause

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageSessionTest {
    @Test
    fun sessionBeforeAllowedUntilIsValid() {
        val session = UsageSession(
            packageName = "com.example.app",
            allowedUntilMillis = 10_000L
        )

        assertTrue(session.isValidAt(currentTimeMillis = 9_999L))
    }

    @Test
    fun sessionAtAllowedUntilIsExpired() {
        val session = UsageSession(
            packageName = "com.example.app",
            allowedUntilMillis = 10_000L
        )

        assertFalse(session.isValidAt(currentTimeMillis = 10_000L))
    }

    @Test
    fun sessionAfterAllowedUntilIsExpired() {
        val session = UsageSession(
            packageName = "com.example.app",
            allowedUntilMillis = 10_000L
        )

        assertFalse(session.isValidAt(currentTimeMillis = 10_001L))
    }

    @Test
    fun sessionForPackageADoesNotAuthorizePackageB() {
        val session = UsageSession(
            packageName = "com.example.a",
            allowedUntilMillis = 10_000L
        )

        assertFalse(
            session.authorizes(
                targetPackageName = "com.example.b",
                currentTimeMillis = 9_000L
            )
        )
    }

    @Test
    fun packageAAndPackageBCanHaveSimultaneousValidSessions() {
        val sessions = mapOf(
            PACKAGE_A to 10_000L,
            PACKAGE_B to 20_000L
        )

        assertTrue(
            hasValidSessionForPackage(
                activeUsageSessions = sessions,
                packageName = PACKAGE_A,
                currentTimeMillis = 9_000L
            )
        )
        assertTrue(
            hasValidSessionForPackage(
                activeUsageSessions = sessions,
                packageName = PACKAGE_B,
                currentTimeMillis = 9_000L
            )
        )
    }

    @Test
    fun creatingSessionForPackageBDoesNotChangePackageAAllowedUntil() {
        val sessions = withUsageSessionForPackage(
            activeUsageSessions = mapOf(PACKAGE_A to 10_000L),
            packageName = PACKAGE_B,
            allowedUntilMillis = 20_000L
        )

        assertEquals(10_000L, savedAllowedUntilForPackage(sessions, PACKAGE_A))
        assertEquals(20_000L, savedAllowedUntilForPackage(sessions, PACKAGE_B))
    }

    @Test
    fun expiringPackageBDoesNotExpirePackageA() {
        val sessions = mapOf(
            PACKAGE_A to 10_000L,
            PACKAGE_B to 2_000L
        )

        assertTrue(
            hasValidSessionForPackage(
                activeUsageSessions = sessions,
                packageName = PACKAGE_A,
                currentTimeMillis = 3_000L
            )
        )
        assertFalse(
            hasValidSessionForPackage(
                activeUsageSessions = sessions,
                packageName = PACKAGE_B,
                currentTimeMillis = 3_000L
            )
        )
    }

    @Test
    fun returningToPackageABeforeItsExpiryDoesNotNeedAnotherPrompt() {
        val sessions = mapOf(
            PACKAGE_A to 10_000L,
            PACKAGE_B to 2_000L
        )

        assertTrue(
            hasValidSessionForPackage(
                activeUsageSessions = sessions,
                packageName = PACKAGE_A,
                currentTimeMillis = 3_000L
            )
        )
    }

    @Test
    fun replacingPackageASessionOnlyChangesPackageA() {
        val sessions = withUsageSessionForPackage(
            activeUsageSessions = mapOf(
                PACKAGE_A to 10_000L,
                PACKAGE_B to 20_000L
            ),
            packageName = PACKAGE_A,
            allowedUntilMillis = 30_000L
        )

        assertEquals(30_000L, savedAllowedUntilForPackage(sessions, PACKAGE_A))
        assertEquals(20_000L, savedAllowedUntilForPackage(sessions, PACKAGE_B))
    }

    companion object {
        private const val PACKAGE_A = "com.example.a"
        private const val PACKAGE_B = "com.example.b"
    }
}
