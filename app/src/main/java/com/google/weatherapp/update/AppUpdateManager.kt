package com.google.weatherapp.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.weatherapp.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class AppUpdateManager(private val context: Context) {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    suspend fun checkForUpdates(
        owner: String = BuildConfig.GITHUB_REPO_OWNER,
        repo: String = BuildConfig.GITHUB_REPO_NAME,
        forceUpdate: Boolean = false
    ): UpdateCheckState = withContext(Dispatchers.IO) {
        val currentVersion = BuildConfig.VERSION_NAME
        if (owner.isBlank() || repo.isBlank()) {
            return@withContext if (forceUpdate) {
                createMockUpdateState(currentVersion)
            } else {
                UpdateCheckState.Error("GitHub repository owner or name not configured.")
            }
        }

        // Tier 1: CDN-served ota-metadata.json (zero GitHub API rate limits, zero tokens)
        checkViaMetadataAsset(owner, repo, currentVersion, forceUpdate)?.let {
            return@withContext it
        }

        // Tier 2: Unauthenticated GitHub Releases REST API fallback
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", USER_AGENT)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 401 || response.code == 403 || response.code == 429) {
                        return@withContext checkViaWebRedirect(owner, repo, currentVersion, forceUpdate)
                    }
                    if (forceUpdate) {
                        return@withContext createMockUpdateState(currentVersion)
                    }
                    return@withContext if (response.code == 404) {
                        UpdateCheckState.UpToDate(currentVersion)
                    } else {
                        UpdateCheckState.Error("GitHub API returned error ${response.code}: ${response.message}")
                    }
                }

                val bodyString = response.body?.string().orEmpty()
                if (bodyString.isBlank()) {
                    if (forceUpdate) {
                        return@withContext createMockUpdateState(currentVersion)
                    }
                    return@withContext UpdateCheckState.Error("Empty response from GitHub Releases.")
                }

                val release = gson.fromJson(bodyString, GitHubRelease::class.java)
                val tagName = release?.tagName?.takeIf { it.isNotBlank() }
                if (release == null || tagName == null) {
                    if (forceUpdate) {
                        return@withContext createMockUpdateState(currentVersion)
                    }
                    return@withContext UpdateCheckState.Error("Malformed release payload from GitHub.")
                }
                val isNewer = isNewerVersion(tagName, currentVersion)

                if (!isNewer && !forceUpdate) {
                    return@withContext UpdateCheckState.UpToDate(currentVersion)
                }

                val assets: List<GitHubReleaseAsset> = release.assets ?: emptyList()
                val apkAsset = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                if (apkAsset == null) {
                    if (forceUpdate) {
                        return@withContext createMockUpdateState(currentVersion)
                    }
                    return@withContext UpdateCheckState.Error("Release $tagName found, but no .apk asset attached.")
                }

                buildUpdateAvailable(
                    currentVersion = currentVersion,
                    remoteVersion = tagName.removePrefix("v").removePrefix("V"),
                    rawTitle = release.name ?: tagName,
                    rawNotes = release.body.orEmpty(),
                    downloadUrl = apkAsset.downloadUrl,
                    assetSize = apkAsset.size,
                    assetName = apkAsset.name,
                    tagName = tagName,
                    isNewer = isNewer
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (forceUpdate) {
                createMockUpdateState(currentVersion)
            } else {
                UpdateCheckState.Error(e.message ?: "Failed to check for updates.")
            }
        }
    }

    private fun checkViaMetadataAsset(
        owner: String,
        repo: String,
        currentVersion: String,
        forceUpdate: Boolean
    ): UpdateCheckState? {
        val metadataUrl = "https://github.com/$owner/$repo/releases/latest/download/$OTA_METADATA_ASSET"
        val request = Request.Builder()
            .url(metadataUrl)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyString = response.body?.string().orEmpty()
                val metadata = parseOtaMetadata(bodyString, gson) ?: return null
                evaluateMetadataUpdate(
                    metadata = metadata,
                    currentVersion = currentVersion,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    forceUpdate = forceUpdate
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun downloadApk(
        downloadUrl: String,
        onProgress: (Float, Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        if (downloadUrl == MOCK_DOWNLOAD_URL) {
            return@withContext simulateApkDownload(onProgress)
        }
        val updatesDir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
        val destinationFile = File(updatesDir, "update.apk")
        val call = httpClient.newCall(
            Request.Builder()
                .url(downloadUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", USER_AGENT)
                .build()
        )
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed to download APK: HTTP ${response.code}"))
                }

                val body = response.body ?: return@withContext Result.failure(Exception("Response body is empty"))
                val totalBytes = body.contentLength()

                if (destinationFile.exists()) destinationFile.delete()

                body.byteStream().use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastEmitMs = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastEmitMs >= 80L || (totalBytes > 0 && totalRead >= totalBytes)) {
                                lastEmitMs = nowMs
                                val progress = if (totalBytes > 0) totalRead.toFloat() / totalBytes else 0f
                                onProgress(progress, totalRead, totalBytes)
                            }
                        }
                        outputStream.flush()
                        val finalProgress = if (totalBytes > 0) (totalRead.toFloat() / totalBytes).coerceIn(0f, 1f) else 1f
                        onProgress(finalProgress, totalRead, totalBytes)
                    }
                }

                Result.success(destinationFile)
            }
        } catch (e: CancellationException) {
            call.cancel()
            if (destinationFile.exists()) destinationFile.delete()
            throw e
        } catch (e: Exception) {
            if (destinationFile.exists()) destinationFile.delete()
            Result.failure(e)
        }
    }

    fun canInstallUnknownApps(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openUnknownAppInstallSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private suspend fun checkViaWebRedirect(
        owner: String,
        repo: String,
        currentVersion: String,
        forceUpdate: Boolean
    ): UpdateCheckState = withContext(Dispatchers.IO) {
        try {
            val webUrl = "https://github.com/$owner/$repo/releases/latest"
            val request = Request.Builder()
                .url(webUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val effectiveUrl = response.request.url.toString()

                if (!effectiveUrl.contains("/releases/tag/")) {
                    return@withContext if (forceUpdate) {
                        createMockUpdateState(currentVersion)
                    } else {
                        UpdateCheckState.Error("API rate limit hit and unable to resolve release via web.")
                    }
                }

                val tagName = effectiveUrl.substringAfterLast("/releases/tag/")
                    .substringBefore("?")
                    .substringBefore("#")
                    .trim()
                val isNewer = isNewerVersion(tagName, currentVersion)

                if (!isNewer && !forceUpdate) {
                    return@withContext UpdateCheckState.UpToDate(currentVersion)
                }

                val cleanNewVersion = tagName.removePrefix("v").removePrefix("V")
                val downloadUrl = "https://github.com/$owner/$repo/releases/download/$tagName/weather-app-release.apk"

                buildUpdateAvailable(
                    currentVersion = currentVersion,
                    remoteVersion = cleanNewVersion,
                    rawTitle = "Weather App $tagName",
                    rawNotes = if (isNewer) "New release $tagName is available on GitHub." else "",
                    downloadUrl = downloadUrl,
                    assetSize = 0L,
                    assetName = "weather-app-release.apk",
                    tagName = tagName,
                    isNewer = isNewer
                )
            }
        } catch (e: Exception) {
            if (forceUpdate) {
                createMockUpdateState(currentVersion)
            } else {
                UpdateCheckState.Error("API rate limit exceeded and web fallback failed: ${e.message}")
            }
        }
    }

    fun installApk(apkFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    internal fun createMockUpdateState(currentVersion: String): UpdateCheckState.UpdateAvailable {
        val baseApk = File(context.applicationInfo.sourceDir)
        val apkSize = if (baseApk.exists() && baseApk.length() > 0) baseApk.length() else 14_242_447L
        return UpdateCheckState.UpdateAvailable(
            currentVersion = currentVersion,
            newVersion = "$currentVersion (OTA Test)",
            releaseTitle = "Almanac OTA Test Build",
            releaseNotes = "Easter Egg OTA Simulation Mode.\n\n• Simulated APK download with live progress\n• Validates download UX, progress indicator, and PackageInstaller handoff.",
            downloadUrl = MOCK_DOWNLOAD_URL,
            assetSize = apkSize
        )
    }

    private suspend fun simulateApkDownload(
        onProgress: (Float, Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updatesDir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
            val destinationFile = File(updatesDir, "update.apk")
            if (destinationFile.exists()) destinationFile.delete()

            val baseApk = File(context.applicationInfo.sourceDir)
            if (baseApk.exists() && baseApk.length() > 0) {
                val totalBytes = baseApk.length()
                baseApk.inputStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            val progress = if (totalBytes > 0) totalRead.toFloat() / totalBytes else 0f
                            onProgress(progress, totalRead, totalBytes)
                            kotlinx.coroutines.delay(5)
                        }
                        output.flush()
                    }
                }
            } else {
                val simulatedTotal = 14_242_447L
                val steps = 25
                for (i in 1..steps) {
                    kotlinx.coroutines.delay(40)
                    val progress = i.toFloat() / steps
                    val read = (progress * simulatedTotal).toLong()
                    onProgress(progress, read, simulatedTotal)
                }
                destinationFile.writeBytes("OTA Test Fallback APK Content".toByteArray())
            }
            Result.success(destinationFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        const val MOCK_DOWNLOAD_URL = "mock://ota-test-package"
        const val OTA_METADATA_ASSET = "ota-metadata.json"
        private const val USER_AGENT = "WeatherApp-Android"

        internal fun parseOtaMetadata(json: String, gson: Gson = Gson()): OtaMetadata? {
            if (json.isBlank()) return null
            return try {
                val parsed = gson.fromJson(json, OtaMetadata::class.java)
                if (parsed != null && parsed.tagName.isNotBlank() && parsed.downloadUrl.isNotBlank()) parsed else null
            } catch (_: Exception) {
                null
            }
        }

        internal fun evaluateMetadataUpdate(
            metadata: OtaMetadata,
            currentVersion: String,
            currentVersionCode: Int,
            forceUpdate: Boolean = false
        ): UpdateCheckState {
            val isNewer = (metadata.versionCode > currentVersionCode) ||
                isNewerVersion(metadata.tagName, currentVersion)
            if (!isNewer && !forceUpdate) {
                return UpdateCheckState.UpToDate(currentVersion)
            }
            val remoteVersion = (metadata.versionName as String?).orEmpty().ifBlank {
                metadata.tagName.removePrefix("v").removePrefix("V")
            }
            return buildUpdateAvailable(
                currentVersion = currentVersion,
                remoteVersion = remoteVersion,
                rawTitle = metadata.releaseTitle?.takeIf { it.isNotBlank() } ?: metadata.tagName,
                rawNotes = metadata.releaseNotes.orEmpty(),
                downloadUrl = metadata.downloadUrl,
                assetSize = metadata.apkSize,
                assetName = (metadata.apkName as String?).orEmpty().ifBlank { "weather-app-release.apk" },
                tagName = metadata.tagName,
                isNewer = isNewer
            )
        }

        internal fun buildUpdateAvailable(
            currentVersion: String,
            remoteVersion: String,
            rawTitle: String,
            rawNotes: String,
            downloadUrl: String,
            assetSize: Long,
            assetName: String,
            tagName: String,
            isNewer: Boolean
        ): UpdateCheckState.UpdateAvailable {
            val targetVersion = if (isNewer) remoteVersion else "$remoteVersion (OTA Test)"
            val title = if (isNewer) rawTitle else "$rawTitle [OTA Test]"
            val notes = if (isNewer) {
                rawNotes
            } else {
                rawNotes.ifBlank {
                    "Easter Egg OTA Test Mode.\n\n• Target release: $tagName\n• Asset: $assetName\n• Verifies OTA download pipeline, progress tracking, and package installer handoff."
                }
            }
            return UpdateCheckState.UpdateAvailable(
                currentVersion = currentVersion,
                newVersion = targetVersion,
                releaseTitle = title,
                releaseNotes = notes,
                downloadUrl = downloadUrl,
                assetSize = assetSize
            )
        }

        internal fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
            val cleanRemote = remoteTag.trim().removePrefix("v").removePrefix("V")
            val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")

            val remoteParts = cleanRemote.split(".").mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }
            val currentParts = cleanCurrent.split(".").mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            return false
        }
    }
}
