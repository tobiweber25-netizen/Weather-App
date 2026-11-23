package com.google.weatherapp.ui

import com.google.weatherapp.data.model.SavedLocation
import com.google.weatherapp.data.model.WeatherDashboardData

sealed interface WeatherUiState {
    data object Loading : WeatherUiState
    data class Success(val data: WeatherDashboardData) : WeatherUiState
    data class Error(val message: String) : WeatherUiState
}

sealed interface LocationSearchState {
    data object Idle : LocationSearchState
    data object Searching : LocationSearchState
    data class Results(val locations: List<SavedLocation>) : LocationSearchState
    data class Empty(val query: String) : LocationSearchState
    data class Error(val message: String) : LocationSearchState
}
