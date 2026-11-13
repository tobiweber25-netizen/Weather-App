package com.google.weatherapp

import android.app.Application
import com.google.weatherapp.notification.WeatherNotificationHelper
import com.google.weatherapp.notification.WeatherNotificationScheduler

class WeatherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        WeatherNotificationHelper.createNotificationChannels(this)
        WeatherNotificationScheduler.ensureScheduled(this)
    }
}

