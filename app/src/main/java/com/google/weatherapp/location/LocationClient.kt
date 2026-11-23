package com.google.weatherapp.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.weatherapp.data.api.ApiClient
import com.google.weatherapp.data.api.PhotonGeocodingService
import com.google.weatherapp.data.repository.LocationSearchEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs

class LocationClient(
    private val context: Context,
    private val photonService: PhotonGeocodingService = ApiClient.photonGeocodingService
) {

    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val prefs = context.getSharedPreferences("weather_location_cache", Context.MODE_PRIVATE)

    fun getCachedCoordinates(): Coordinates? {
        val lat = prefs.getString("last_lat", null)?.toDoubleOrNull()
        val lon = prefs.getString("last_lon", null)?.toDoubleOrNull()
        val alt = prefs.getString("last_altitude", null)?.toDoubleOrNull()
        val name = prefs.getString("last_location_name", null)
        return if (lat != null && lon != null) {
            Coordinates(lat, lon, name, alt)
        } else {
            null
        }
    }

    private fun saveCoordinates(coords: Coordinates) {
        val prevLat = prefs.getString("last_lat", null)?.toDoubleOrNull()
        val prevLon = prefs.getString("last_lon", null)?.toDoubleOrNull()
        val movedSignificantly = prevLat == null || prevLon == null ||
            abs(coords.latitude - prevLat) > 0.02 || abs(coords.longitude - prevLon) > 0.02

        prefs.edit()
            .putString("last_lat", coords.latitude.toString())
            .putString("last_lon", coords.longitude.toString())
            .apply {
                if (coords.altitude != null) {
                    putString("last_altitude", coords.altitude.toString())
                } else if (movedSignificantly) {
                    remove("last_altitude")
                }
                if (!coords.locationName.isNullOrBlank()) {
                    putString("last_location_name", coords.locationName)
                } else if (movedSignificantly) {
                    remove("last_location_name")
                }
            }
            .apply()
    }

    fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    @SuppressLint("MissingPermission")
    suspend fun fetchLocation(forceFresh: Boolean = false): Coordinates? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) {
            return@withContext getCachedCoordinates()
        }

        try {
            val now = System.currentTimeMillis()
            val maxAgeMs = 15 * 60 * 1000L

            val lastLocation = withTimeoutOrNull(3000L) {
                fusedClient.lastLocation.await()
            }
            if (!forceFresh && lastLocation != null && (now - lastLocation.time) in 0..maxAgeMs) {
                val name = resolveLocationName(lastLocation.latitude, lastLocation.longitude)
                val alt = if (lastLocation.hasAltitude()) lastLocation.altitude else null
                val coords = Coordinates(lastLocation.latitude, lastLocation.longitude, name, alt)
                saveCoordinates(coords)
                return@withContext coords
            }

            val cts = CancellationTokenSource()
            val currentLocation = withTimeoutOrNull(5000L) {
                try {
                    fusedClient.getCurrentLocation(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        cts.token
                    ).await()
                } finally {
                    cts.cancel()
                }
            }

            val resolvedLocation = currentLocation ?: lastLocation
            if (resolvedLocation != null) {
                val name = resolveLocationName(resolvedLocation.latitude, resolvedLocation.longitude)
                val alt = if (resolvedLocation.hasAltitude()) resolvedLocation.altitude else null
                val coords = Coordinates(resolvedLocation.latitude, resolvedLocation.longitude, name, alt)
                saveCoordinates(coords)
                coords
            } else {
                getCachedCoordinates()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            getCachedCoordinates()
        }
    }

    private suspend fun resolveLocationName(lat: Double, lon: Double): String? {
        val androidName = resolveViaAndroidGeocoder(lat, lon)
        if (!androidName.isNullOrBlank()) return androidName

        // Global backup when Android Geocoder is unavailable, times out, or lacks coverage
        return resolveViaPhotonReverseGeocode(lat, lon)
    }

    private suspend fun resolveViaAndroidGeocoder(lat: Double, lon: Double): String? {
        return try {
            if (!Geocoder.isPresent()) return null
            val geocoder = Geocoder(context, Locale.getDefault())
            val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                withTimeoutOrNull(2500L) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<android.location.Address>) {
                                if (cont.isActive) cont.resume(addresses.firstOrNull())
                            }
                            override fun onError(errorMessage: String?) {
                                if (cont.isActive) cont.resume(null)
                            }
                        })
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
            }

            if (address != null) {
                val subLocality = address.subLocality?.trim()
                val locality = (address.locality ?: address.subAdminArea ?: address.adminArea)?.trim()
                when {
                    !subLocality.isNullOrBlank() && !locality.isNullOrBlank() && !subLocality.equals(locality, ignoreCase = true) ->
                        "$subLocality, $locality"
                    !subLocality.isNullOrBlank() -> subLocality
                    !locality.isNullOrBlank() -> locality
                    else -> null
                }
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun resolveViaPhotonReverseGeocode(lat: Double, lon: Double): String? {
        return try {
            withTimeoutOrNull(3500L) {
                val response = photonService.reverseGeocode(latitude = lat, longitude = lon)
                val firstProps = response.features?.firstOrNull()?.properties
                LocationSearchEngine.formatReverseGeocodeName(firstProps)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

data class Coordinates(
    val latitude: Double,
    val longitude: Double,
    val locationName: String? = null,
    val altitude: Double? = null
)
