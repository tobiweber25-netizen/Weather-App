package com.google.weatherapp.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * OpenStreetMap Photon geocoding & reverse-geocoding client (free, global, no API key).
 * Supplements Open-Meteo city search with suburbs, neighborhoods, landmarks, postal codes,
 * and "Suburb, City" queries worldwide, and backs up Android's built-in Geocoder.
 */
interface PhotonGeocodingService {
    @GET("api/")
    suspend fun searchLocations(
        @Query("q") query: String,
        @Query("limit") limit: Int = 10,
        @Query("lang") lang: String = "en"
    ): PhotonResponse

    @GET("reverse")
    suspend fun reverseGeocode(
        @Query("lat") latitude: Double,
        @Query("lon") longitude: Double,
        @Query("lang") lang: String = "en"
    ): PhotonResponse
}

data class PhotonResponse(
    @SerializedName("features") val features: List<PhotonFeature>? = null
)

data class PhotonFeature(
    @SerializedName("geometry") val geometry: PhotonGeometry? = null,
    @SerializedName("properties") val properties: PhotonProperties? = null
)

data class PhotonGeometry(
    // GeoJSON order: [longitude, latitude]
    @SerializedName("coordinates") val coordinates: List<Double>? = null
)

data class PhotonProperties(
    @SerializedName("name") val name: String? = null,
    @SerializedName("city") val city: String? = null,
    @SerializedName("district") val district: String? = null,
    @SerializedName("locality") val locality: String? = null,
    @SerializedName("county") val county: String? = null,
    @SerializedName("state") val state: String? = null,
    @SerializedName("country") val country: String? = null,
    @SerializedName("countrycode") val countryCode: String? = null,
    @SerializedName("postcode") val postcode: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("osm_key") val osmKey: String? = null,
    @SerializedName("osm_value") val osmValue: String? = null
)
