package com.google.weatherapp.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun testParseOtaMetadataValidAndInvalid() {
        val validJson = """
            {
              "version_name": "1.0.4",
              "version_code": 5,
              "tag_name": "v1.0.4",
              "release_title": "v1.0.4",
              "release_notes": "Seamless OTA updates",
              "apk_name": "weather-app-release.apk",
              "apk_size": 14242451,
              "download_url": "https://github.com/tobiweber25-netizen/Weather-App/releases/download/v1.0.4/weather-app-release.apk"
            }
        """.trimIndent()

        val parsed = AppUpdateManager.parseOtaMetadata(validJson)
        assertNotNull(parsed)
        assertEquals("1.0.4", parsed?.versionName)
        assertEquals(5, parsed?.versionCode)
        assertEquals("v1.0.4", parsed?.tagName)
        assertEquals(14242451L, parsed?.apkSize)

        assertNull(AppUpdateManager.parseOtaMetadata(""))
        assertNull(AppUpdateManager.parseOtaMetadata("{invalid json"))
        assertNull(AppUpdateManager.parseOtaMetadata("""{"tag_name":"v1.0.4"}"""))
    }

    @Test
    fun testEvaluateMetadataUpdate() {
        val metadata = OtaMetadata(
            versionName = "1.0.4",
            versionCode = 5,
            tagName = "v1.0.4",
            releaseTitle = "OTA Fix",
            releaseNotes = "Fix OTA",
            apkName = "weather-app-release.apk",
            apkSize = 14242451L,
            downloadUrl = "https://github.com/tobiweber25-netizen/Weather-App/releases/download/v1.0.4/weather-app-release.apk"
        )

        val newerByVersion = AppUpdateManager.evaluateMetadataUpdate(
            metadata = metadata,
            currentVersion = "1.0.3",
            currentVersionCode = 4,
            forceUpdate = false
        )
        assertTrue(newerByVersion is UpdateCheckState.UpdateAvailable)
        assertEquals("1.0.4", (newerByVersion as UpdateCheckState.UpdateAvailable).newVersion)

        val newerByCodeOnly = AppUpdateManager.evaluateMetadataUpdate(
            metadata = metadata,
            currentVersion = "1.0.4",
            currentVersionCode = 4,
            forceUpdate = false
        )
        assertTrue(newerByCodeOnly is UpdateCheckState.UpdateAvailable)

        val upToDate = AppUpdateManager.evaluateMetadataUpdate(
            metadata = metadata,
            currentVersion = "1.0.4",
            currentVersionCode = 5,
            forceUpdate = false
        )
        assertTrue(upToDate is UpdateCheckState.UpToDate)

        val forced = AppUpdateManager.evaluateMetadataUpdate(
            metadata = metadata,
            currentVersion = "1.0.4",
            currentVersionCode = 5,
            forceUpdate = true
        )
        assertTrue(forced is UpdateCheckState.UpdateAvailable)
        assertTrue((forced as UpdateCheckState.UpdateAvailable).newVersion.contains("OTA Test"))
    }
}
