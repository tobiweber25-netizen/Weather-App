package com.google.weatherapp.update

import com.google.gson.annotations.SerializedName

data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("name") val name: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("published_at") val publishedAt: String?,
    @SerializedName("assets") val assets: List<GitHubReleaseAsset> = emptyList()
)

data class GitHubReleaseAsset(
    @SerializedName("name") val name: String,
    @SerializedName("size") val size: Long,
    @SerializedName("browser_download_url") val downloadUrl: String,
    @SerializedName("content_type") val contentType: String?
)

data class OtaMetadata(
    @SerializedName("version_name") val versionName: String,
    @SerializedName("version_code") val versionCode: Int = 0,
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("release_title") val releaseTitle: String? = null,
    @SerializedName("release_notes") val releaseNotes: String? = null,
    @SerializedName("apk_name") val apkName: String = "weather-app-release.apk",
    @SerializedName("apk_size") val apkSize: Long = 0L,
    @SerializedName("download_url") val downloadUrl: String
)

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpdateAvailable(
        val currentVersion: String,
        val newVersion: String,
        val releaseTitle: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val assetSize: Long
    ) : UpdateCheckState
    data class UpToDate(val currentVersion: String) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : UpdateDownloadState
    data class ReadyToInstall(val apkPath: String) : UpdateDownloadState
    data class Error(val message: String) : UpdateDownloadState
}
