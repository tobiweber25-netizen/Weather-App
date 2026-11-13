package com.google.weatherapp.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WeatherNotificationSchedulerTest {

    @Test
    fun testComputeNextTriggerMillis_targetInFutureToday() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 6)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Target: 07:30 today
        val trigger = WeatherNotificationScheduler.computeNextTriggerMillis(
            targetHour = 7,
            targetMinute = 30,
            now = now
        )

        val triggerCal = Calendar.getInstance().apply { timeInMillis = trigger }
        assertEquals(now.get(Calendar.DAY_OF_YEAR), triggerCal.get(Calendar.DAY_OF_YEAR))
        assertEquals(7, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, triggerCal.get(Calendar.MINUTE))
        assertEquals(0, triggerCal.get(Calendar.SECOND))
    }

    @Test
    fun testComputeNextTriggerMillis_targetInPastToday_rollsToTomorrow() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 15)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Target: 07:30 (already passed today)
        val trigger = WeatherNotificationScheduler.computeNextTriggerMillis(
            targetHour = 7,
            targetMinute = 30,
            now = now
        )

        val triggerCal = Calendar.getInstance().apply { timeInMillis = trigger }
        val expectedDay = (now.get(Calendar.DAY_OF_YEAR) + 1)
        assertEquals(expectedDay, triggerCal.get(Calendar.DAY_OF_YEAR))
        assertEquals(7, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, triggerCal.get(Calendar.MINUTE))
    }

    @Test
    fun testComputeNextTriggerMillis_targetExactMatch_rollsToTomorrow() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 20)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Target: 20:00 (fires now, so next should be tomorrow 20:00)
        val trigger = WeatherNotificationScheduler.computeNextTriggerMillis(
            targetHour = 20,
            targetMinute = 0,
            now = now
        )

        val triggerCal = Calendar.getInstance().apply { timeInMillis = trigger }
        val expectedDay = (now.get(Calendar.DAY_OF_YEAR) + 1)
        assertEquals(expectedDay, triggerCal.get(Calendar.DAY_OF_YEAR))
        assertEquals(20, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, triggerCal.get(Calendar.MINUTE))
    }

    @Test
    fun testComputeNextTriggerMillis_eveningOutlookScheduledInAfternoon() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Target: 20:00 today
        val trigger = WeatherNotificationScheduler.computeNextTriggerMillis(
            targetHour = 20,
            targetMinute = 0,
            now = now
        )

        val triggerCal = Calendar.getInstance().apply { timeInMillis = trigger }
        assertEquals(now.get(Calendar.DAY_OF_YEAR), triggerCal.get(Calendar.DAY_OF_YEAR))
        assertEquals(20, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, triggerCal.get(Calendar.MINUTE))
        assertTrue(trigger > now.timeInMillis)
    }

    @Test
    fun testIsSevereAlert_logicRules() {
        val lowAlert = com.google.weatherapp.data.model.SevereAlertInfo(
            riskLevel = com.google.weatherapp.data.model.SeverityRiskLevel.LOW,
            thunderstormProbability = 10,
            title = "Clear",
            subtitle = "No storms",
            isVisible = true,
            peakHourLabel = null
        )
        org.junit.Assert.assertFalse(WeatherNotificationScheduler.isSevereAlert(lowAlert))

        val invisibleAlert = lowAlert.copy(
            riskLevel = com.google.weatherapp.data.model.SeverityRiskLevel.HIGH,
            isVisible = false
        )
        org.junit.Assert.assertFalse(WeatherNotificationScheduler.isSevereAlert(invisibleAlert))

        val highAlert = lowAlert.copy(
            riskLevel = com.google.weatherapp.data.model.SeverityRiskLevel.HIGH,
            isVisible = true
        )
        assertTrue(WeatherNotificationScheduler.isSevereAlert(highAlert))

        val severeAlert = lowAlert.copy(
            riskLevel = com.google.weatherapp.data.model.SeverityRiskLevel.SEVERE,
            isVisible = true
        )
        assertTrue(WeatherNotificationScheduler.isSevereAlert(severeAlert))

        val stormProbAlert = lowAlert.copy(
            riskLevel = com.google.weatherapp.data.model.SeverityRiskLevel.MODERATE,
            thunderstormProbability = 65,
            isVisible = true
        )
        assertTrue(WeatherNotificationScheduler.isSevereAlert(stormProbAlert))

        val mildStormAlert = lowAlert.copy(
            riskLevel = com.google.weatherapp.data.model.SeverityRiskLevel.MODERATE,
            thunderstormProbability = 40,
            isVisible = true
        )
        org.junit.Assert.assertFalse(WeatherNotificationScheduler.isSevereAlert(mildStormAlert))
    }

    @Test
    fun testGetTodayDateString_formatting() {
        // 2026-10-30 12:00:00 UTC = epoch 1793361600000 ms
        val zone = java.time.ZoneOffset.UTC
        val epochMs = java.time.LocalDate.of(2026, 10, 30)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

        val dateStr = NotificationPreferences.getTodayDateString(epochMs, zone)
        assertEquals("2026-10-30", dateStr)
    }

    @Test
    fun testComputeNextTriggerMillis_targetSlightlyInFutureEarlyWakeup_rollsToTomorrow() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 29)
            set(Calendar.SECOND, 58) // 2 seconds before 07:30:00
            set(Calendar.MILLISECOND, 0)
        }

        // Target: 07:30 (alarm fired 2 seconds early)
        val trigger = WeatherNotificationScheduler.computeNextTriggerMillis(
            targetHour = 7,
            targetMinute = 30,
            now = now
        )

        val triggerCal = Calendar.getInstance().apply { timeInMillis = trigger }
        val expectedDay = (now.get(Calendar.DAY_OF_YEAR) + 1)
        assertEquals(expectedDay, triggerCal.get(Calendar.DAY_OF_YEAR))
        assertEquals(7, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, triggerCal.get(Calendar.MINUTE))
    }

    @Test
    fun testWorkManagerConstantsAndWorkerTypes() {
        assertEquals("weather_work_morning", WeatherNotificationScheduler.UNIQUE_WORK_MORNING)
        assertEquals("weather_work_evening", WeatherNotificationScheduler.UNIQUE_WORK_EVENING)
        assertEquals("weather_work_periodic", WeatherNotificationScheduler.UNIQUE_WORK_PERIODIC)

        assertEquals("TYPE_MORNING", WeatherNotificationWorker.TYPE_MORNING)
        assertEquals("TYPE_EVENING", WeatherNotificationWorker.TYPE_EVENING)
        assertEquals("TYPE_PERIODIC_CHECK", WeatherNotificationWorker.TYPE_PERIODIC_CHECK)
        assertEquals("TYPE_BACKGROUND_SYNC", WeatherNotificationWorker.TYPE_BACKGROUND_SYNC)
    }

    @Test
    fun testMorningCatchupWindowCalculation() {
        val morningHour = 7
        val morningMinute = 30

        // 7:15 AM is before scheduled time -> false (must NOT fire early)
        org.junit.Assert.assertFalse(
            WeatherNotificationScheduler.isWithinMorningCatchupWindow(
                nowHour = 7, nowMinute = 15, morningHour = morningHour, morningMinute = morningMinute
            )
        )

        // 7:30 AM is exactly at scheduled time -> true
        assertTrue(
            WeatherNotificationScheduler.isWithinMorningCatchupWindow(
                nowHour = 7, nowMinute = 30, morningHour = morningHour, morningMinute = morningMinute
            )
        )

        // 8:15 AM is after scheduled time within window -> true
        assertTrue(
            WeatherNotificationScheduler.isWithinMorningCatchupWindow(
                nowHour = 8, nowMinute = 15, morningHour = morningHour, morningMinute = morningMinute
            )
        )

        // 11:29 AM is near end of window -> true
        assertTrue(
            WeatherNotificationScheduler.isWithinMorningCatchupWindow(
                nowHour = 11, nowMinute = 29, morningHour = morningHour, morningMinute = morningMinute
            )
        )

        // 11:30 AM is past end of window -> false
        org.junit.Assert.assertFalse(
            WeatherNotificationScheduler.isWithinMorningCatchupWindow(
                nowHour = 11, nowMinute = 30, morningHour = morningHour, morningMinute = morningMinute
            )
        )
    }

    @Test
    fun testEveningCatchupWindowCalculation() {
        val eveningHour = 20
        val eveningMinute = 0

        // 19:45 is before scheduled time -> false (must NOT fire early)
        org.junit.Assert.assertFalse(
            WeatherNotificationScheduler.isWithinEveningCatchupWindow(
                nowHour = 19, nowMinute = 45, eveningHour = eveningHour, eveningMinute = eveningMinute
            )
        )

        // 20:00 is exactly scheduled time -> true
        assertTrue(
            WeatherNotificationScheduler.isWithinEveningCatchupWindow(
                nowHour = 20, nowMinute = 0, eveningHour = eveningHour, eveningMinute = eveningMinute
            )
        )

        // 22:30 is within evening window -> true
        assertTrue(
            WeatherNotificationScheduler.isWithinEveningCatchupWindow(
                nowHour = 22, nowMinute = 30, eveningHour = eveningHour, eveningMinute = eveningMinute
            )
        )

        // 23:59 is end of evening window -> true
        assertTrue(
            WeatherNotificationScheduler.isWithinEveningCatchupWindow(
                nowHour = 23, nowMinute = 59, eveningHour = eveningHour, eveningMinute = eveningMinute
            )
        )
    }
}
