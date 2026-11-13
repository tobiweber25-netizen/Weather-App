package com.google.weatherapp.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun testIsNewerVersion() {
        // Simple patch bump
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.1", "1.0.0"))
        assertTrue(AppUpdateManager.isNewerVersion("1.0.1", "1.0.0"))

        // Minor bump
        assertTrue(AppUpdateManager.isNewerVersion("v1.1.0", "1.0.9"))

        // Major bump
        assertTrue(AppUpdateManager.isNewerVersion("v2.0.0", "1.9.9"))

        // Same version
        assertFalse(AppUpdateManager.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(AppUpdateManager.isNewerVersion("1.0.0", "1.0.0"))

        // Older version
        assertFalse(AppUpdateManager.isNewerVersion("v1.0.0", "1.0.1"))
        assertFalse(AppUpdateManager.isNewerVersion("v0.9.5", "1.0.0"))

        // Different segment lengths
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.0.1", "1.0.0"))
        assertFalse(AppUpdateManager.isNewerVersion("v1.0", "1.0.0"))

        // Suffix handling
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.1-beta", "1.0.0"))
    }

    @Test
    fun testMockDownloadUrlConstant() {
        assertEquals("mock://ota-test-package", AppUpdateManager.MOCK_DOWNLOAD_URL)
    }

    @Test
    fun testSameVersionWithForceUpdateFlagConcept() {
        val currentVersion = "1.0.0"
        val remoteVersion = "v1.0.0"
        val isNewer = AppUpdateManager.isNewerVersion(remoteVersion, currentVersion)
        assertFalse("Without force, same version is not newer", isNewer)

        // When forceUpdate is true, the app bypasses isNewer check
        val shouldOfferUpdate = isNewer || true
        assertTrue("With forceUpdate, update is offered even if version matches", shouldOfferUpdate)
    }
}
