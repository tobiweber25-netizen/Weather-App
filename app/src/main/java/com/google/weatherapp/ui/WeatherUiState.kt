package com.google.weatherapp.ui

import com.google.weatherapp.data.model.WeatherDashboardData

sealed interface WeatherUiState {
    data object Loading : WeatherUiState
    data class Success(val data: WeatherDashboardData) : WeatherUiState
    data class Error(val message: String) : WeatherUiState
}
