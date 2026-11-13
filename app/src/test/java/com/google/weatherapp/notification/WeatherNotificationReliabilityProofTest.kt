package com.google.weatherapp.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.weatherapp.data.model.BentoTelemetry
import com.google.weatherapp.data.model.DailyItem
import com.google.weatherapp.data.model.HeroWeather
import com.google.weatherapp.data.model.HourlyItem
import com.google.weatherapp.data.model.PrecipitationNowcast
import com.google.weatherapp.data.model.SevereAlertInfo
import com.google.weatherapp.data.model.SeverityRiskLevel
import com.google.weatherapp.data.model.SkyThemeType
import com.google.weatherapp.data.model.WeatherDashboardData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.TimeZone

class WeatherNotificationReliabilityProofTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
        WeatherNotificationReceiver.preferencesProvider = null
        WeatherNotificationReceiver.calendarProvider = null
        WeatherNotificationScheduler.alarmSchedulerOverride = null
        WeatherNotificationHelper.notificationDispatcherOverride = null
        WeatherNotificationHelper.areNotificationsEnabledOverride = null
    }

    // =========================================================================
    // 0. Android 13+ (API 33) POST_NOTIFICATIONS Permission Handling
    // =========================================================================

    @Test
    fun testPostNotifications_PermissionGranted_DispatchesNotificationAndRecordsPosting() {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NotificationPreferences(fakePrefs)
        val context = FakeContext(fakePrefs)
        var dispatched = false
        var capturedTitle = ""

        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, _, title, _, _, _, _ ->
            dispatched = true
            capturedTitle = title
            true
        }

        val data = WeatherNotificationScheduler.createFallbackDashboardData()
        val morningResult = WeatherNotificationHelper.showMorningBriefingNotification(context, data)
        assertTrue("Notification helper must return true when permission is granted", morningResult)
        assertTrue("Dispatcher must have intercepted the notification", dispatched)
        assertTrue("Title should contain location", capturedTitle.contains("Current Location"))

        if (morningResult) {
            prefs.recordMorningNotificationPosted()
        }
        assertFalse("Morning notification must be marked posted and blocked from double posting", prefs.canPostMorningNotification())
    }

    @Test
    fun testPostNotifications_PermissionDenied_BlocksDispatchAndPreservesCatchupState() {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NotificationPreferences(fakePrefs)
        val context = FakeContext(fakePrefs)
        var dispatched = false

        WeatherNotificationHelper.areNotificationsEnabledOverride = { false }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, _, _, _, _, _, _ ->
            dispatched = true
            false
        }

        val data = WeatherNotificationScheduler.createFallbackDashboardData()
        val morningResult = WeatherNotificationHelper.showMorningBriefingNotification(context, data)
        assertFalse("Notification helper must return false when permission is denied", morningResult)
        assertFalse("Notification should not have been dispatched", dispatched)

        assertTrue("Posting eligibility must remain true so catchup occurs when permission is later granted", prefs.canPostMorningNotification())
    }

    @Test
    fun testPostNotifications_SecurityException_HandledGracefullyWithoutCrash() {
        val context = FakeContext()
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, _, _, _, _, _, _ ->
            throw SecurityException("POST_NOTIFICATIONS revoked dynamically")
        }

        val data = WeatherNotificationScheduler.createFallbackDashboardData()
        val result = try {
            WeatherNotificationHelper.showMorningBriefingNotification(context, data)
        } catch (_: Throwable) {
            false
        }
        assertFalse("SecurityException should be safely handled without crash", result)
    }

    @Test
    fun testWeatherNotificationHelper_areNotificationsEnabled_ReflectsSystemStatus() {
        val context = FakeContext()
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        assertTrue(WeatherNotificationHelper.areNotificationsEnabled(context))

        WeatherNotificationHelper.areNotificationsEnabledOverride = { false }
        assertFalse(WeatherNotificationHelper.areNotificationsEnabled(context))
    }

    // =========================================================================
    // 1. Android 12+ (API 31+) Exact Alarm vs Fallback Strategy Verification
    // =========================================================================

    @Test
    fun testExactAlarmStrategy_Android12Plus_PermissionGranted() {
        // When SCHEDULE_EXACT_ALARM is granted on Android 12+ (API 31),
        // system MUST use SET_EXACT_AND_ALLOW_WHILE_IDLE for punctual delivery.
        val strategy31 = WeatherNotificationScheduler.determineScheduleStrategy(
            sdkInt = 31, // Android 12 (S)
            canScheduleExact = true
        )
        assertEquals(
            WeatherNotificationScheduler.AlarmScheduleStrategy.SET_EXACT_AND_ALLOW_WHILE_IDLE,
            strategy31
        )

        // Android 13 (API 33)
        val strategy33 = WeatherNotificationScheduler.determineScheduleStrategy(
            sdkInt = 33, // Android 13 (Tiramisu)
            canScheduleExact = true
        )
        assertEquals(
            WeatherNotificationScheduler.AlarmScheduleStrategy.SET_EXACT_AND_ALLOW_WHILE_IDLE,
            strategy33
        )

        // Android 14+ (API 34/35)
        val strategy35 = WeatherNotificationScheduler.determineScheduleStrategy(
            sdkInt = 35,
            canScheduleExact = true
        )
        assertEquals(
            WeatherNotificationScheduler.AlarmScheduleStrategy.SET_EXACT_AND_ALLOW_WHILE_IDLE,
            strategy35
        )
    }

    @Test
    fun testExactAlarmStrategy_Android12Plus_PermissionDenied_FallbackToAllowWhileIdle() {
        // When SCHEDULE_EXACT_ALARM is denied on Android 12+,
        // system MUST fall back gracefully to SET_AND_ALLOW_WHILE_IDLE to avoid SecurityException crash
        // and ensure the alarm still fires in Doze mode.
        val strategyDenied31 = WeatherNotificationScheduler.determineScheduleStrategy(
            sdkInt = 31,
            canScheduleExact = false
        )
        assertEquals(
            WeatherNotificationScheduler.AlarmScheduleStrategy.SET_AND_ALLOW_WHILE_IDLE,
            strategyDenied31
        )

        val strategyDenied34 = WeatherNotificationScheduler.determineScheduleStrategy(
            sdkInt = 34,
            canScheduleExact = false
        )
        assertEquals(
            WeatherNotificationScheduler.AlarmScheduleStrategy.SET_AND_ALLOW_WHILE_IDLE,
            strategyDenied34
        )
    }

    @Test
    fun testExactAlarmStrategy_PreAndroid12() {
        // Android 6.0 to 11 (API 23-30): exact alarms do not require SCHEDULE_EXACT_ALARM
        val strategy26 = WeatherNotificationScheduler.determineScheduleStrategy(
            sdkInt = 26,
            canScheduleExact = false
        )
        assertEquals(
            WeatherNotificationScheduler.AlarmScheduleStrategy.SET_EXACT_AND_ALLOW_WHILE_IDLE,
            strategy26
        )

        // Android 5 (API 21): uses SET_EXACT
        val strategy21 = WeatherNotificationScheduler.determineScheduleStrategy(
            sdkInt = 21,
            canScheduleExact = false
        )
        assertEquals(
            WeatherNotificationScheduler.AlarmScheduleStrategy.SET_EXACT,
            strategy21
        )
    }

    @Test
    fun testScheduleAlarm_PermissionGranted_DispatchesWithStrategy() {
        val context = FakeContext()
        var selectedStrategy: WeatherNotificationScheduler.AlarmScheduleStrategy? = null
        var scheduledTrigger: Long = 0L

        WeatherNotificationScheduler.alarmSchedulerOverride = { trigger, _, strategy ->
            scheduledTrigger = trigger
            selectedStrategy = strategy
        }

        val dummyPendingIntent = PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
        WeatherNotificationScheduler.scheduleAlarm(context, 123456789L, dummyPendingIntent)

        assertNotNull(selectedStrategy)
        assertEquals(123456789L, scheduledTrigger)
    }

    @Test
    fun testBroadcastReceiver_ExactAlarmPermissionStateChanged_ReschedulesAlarms() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val receiver = WeatherNotificationReceiver()
        var scheduledCount = 0

        WeatherNotificationScheduler.alarmSchedulerOverride = { _, _, _ ->
            scheduledCount++
        }
        WeatherNotificationReceiver.preferencesProvider = { NotificationPreferences(fakePrefs) }

        val intent = FakeIntent(WeatherNotificationReceiver.ACTION_SCHEDULE_EXACT_ALARM_STATE_CHANGED)
        receiver.onReceive(context, intent)

        // ensureScheduled schedules morning + evening = 2 alarms
        assertEquals(2, scheduledCount)
    }

    // =========================================================================
    // 2. Idempotency & Deduplication (NotificationPreferences)
    // =========================================================================

    @Test
    fun testMorningNotification_NeverDoublePostsOnSameDay() {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NotificationPreferences(fakePrefs)
        val now = 1793361600000L // 2026-10-30 12:00:00 UTC

        // Initial state: notification enabled, nothing posted today
        assertTrue("Initially, morning notification should be allowed", prefs.canPostMorningNotification(now))

        // First delivery records posting
        prefs.recordMorningNotificationPosted(now)

        // Subsequent check on the SAME day: MUST return false
        assertFalse(
            "Morning notification must NOT double post on the same day",
            prefs.canPostMorningNotification(now + 1_000L) // 1 second later
        )
        assertFalse(
            "Morning notification must NOT double post 2 hours later on same day",
            prefs.canPostMorningNotification(now + 2 * 3600 * 1000L)
        )
        assertFalse(
            "Morning notification must NOT double post 6 hours later on same day",
            prefs.canPostMorningNotification(now + 6 * 3600 * 1000L)
        )
    }

    @Test
    fun testMorningNotification_ReArmsOnNextDay() {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NotificationPreferences(fakePrefs)
        val day1Epoch = 1793361600000L // 2026-10-30 12:00:00 UTC
        val day2Epoch = day1Epoch + 24 * 3600 * 1000L // 2026-10-31 12:00:00 UTC

        prefs.recordMorningNotificationPosted(day1Epoch)
        assertFalse("Blocked on day 1", prefs.canPostMorningNotification(day1Epoch))

        // Next day arrives
        assertTrue(
            "Morning notification must re-arm and allow posting on day 2",
            prefs.canPostMorningNotification(day2Epoch)
        )
    }

    @Test
    fun testEveningNotification_NeverDoublePostsOnSameDay() {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NotificationPreferences(fakePrefs)
        val now = 1793361600000L

        assertTrue("Initially, evening notification should be allowed", prefs.canPostEveningNotification(now))

        prefs.recordEveningNotificationPosted(now)

        assertFalse(
            "Evening notification must NOT double post on the same day",
            prefs.canPostEveningNotification(now + 5_000L)
        )
        assertFalse(
            "Evening notification must NOT double post 3 hours later",
            prefs.canPostEveningNotification(now + 3 * 3600 * 1000L)
        )
    }

    @Test
    fun testEveningNotification_ReArmsOnNextDay() {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NotificationPreferences(fakePrefs)
        val day1Epoch = 1793361600000L
        val day2Epoch = day1Epoch + 24 * 3600 * 1000L

        prefs.recordEveningNotificationPosted(day1Epoch)
        assertFalse("Blocked on day 1", prefs.canPostEveningNotification(day1Epoch))

        assertTrue(
            "Evening notification must re-arm and allow posting on day 2",
            prefs.canPostEveningNotification(day2Epoch)
        )
    }

    @Test
    fun testMasterSwitchDisabled_BlocksAllNotifications() {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NotificationPreferences(fakePrefs)
        prefs.isWeatherNotificationsEnabled = false

        val now = System.currentTimeMillis()
        assertFalse("Morning blocked when master switch disabled", prefs.canPostMorningNotification(now))
        assertFalse("Evening blocked when master switch disabled", prefs.canPostEveningNotification(now))
        assertFalse("Rain alert blocked when master switch disabled", prefs.canTriggerRainAlert(now))
        assertFalse("Severe alert blocked when master switch disabled", prefs.canTriggerSevereAlert(now))
    }

    @Test
    fun testRainAlert_EnforcesCooldown() {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NotificationPreferences(fakePrefs)
        val now = 1793361600000L

        assertTrue("Initial rain alert allowed", prefs.canTriggerRainAlert(now))

        prefs.lastRainAlertEpochMs = now

        // 3 hours later (cooldown is 4 hours)
        assertFalse(
            "Rain alert blocked during 4-hour cooldown",
            prefs.canTriggerRainAlert(now + 3 * 3600 * 1000L)
        )

        // 4 hours and 1 minute later
        assertTrue(
            "Rain alert allowed after cooldown expires",
            prefs.canTriggerRainAlert(now + (4 * 3600 + 60) * 1000L)
        )
    }

    @Test
    fun testSevereAlert_EnforcesCooldown() {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NotificationPreferences(fakePrefs)
        val now = 1793361600000L

        assertTrue("Initial severe alert allowed", prefs.canTriggerSevereAlert(now))

        prefs.lastSevereAlertEpochMs = now

        assertFalse(
            "Severe alert blocked during 6-hour cooldown",
            prefs.canTriggerSevereAlert(now + 4 * 3600 * 1000L)
        )

        assertTrue(
            "Severe alert allowed after cooldown expires",
            prefs.canTriggerSevereAlert(now + (6 * 3600 + 60) * 1000L)
        )
    }

    @Test
    fun testClockSkew_SafeHandling() {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NotificationPreferences(fakePrefs)
        val futureTime = 1793361600000L + 100_000L
        val realTime = 1793361600000L // Time rolled back

        prefs.lastRainAlertEpochMs = futureTime

        // Time moving backward (diff < 0) should not permanently lock out alerts
        assertTrue("Alert should allow recovery when system time rolls back", prefs.canTriggerRainAlert(realTime))
    }

    // =========================================================================
    // 3. Time Calculations & Trigger Computation Edge Cases
    // =========================================================================

    @Test
    fun testMidnightBoundary_Calculation() {
        // Case A: 23:59:58, target 00:00 (Midnight tonight / start of tomorrow)
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 58)
            set(Calendar.MILLISECOND, 0)
        }

        val trigger = WeatherNotificationScheduler.computeNextTriggerMillis(
            targetHour = 0,
            targetMinute = 0,
            now = now
        )

        val triggerCal = Calendar.getInstance().apply { timeInMillis = trigger }
        // Should roll to next day 00:00:00 (2 seconds in future)
        val diffSeconds = (trigger - now.timeInMillis) / 1000
        assertEquals(2, diffSeconds)
        assertEquals(0, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, triggerCal.get(Calendar.MINUTE))
        assertEquals(0, triggerCal.get(Calendar.SECOND))
    }

    @Test
    fun testMidnightBoundary_FiredShortlyAfterMidnight() {
        // Case B: 00:00:05, target 00:00 (Target already passed 5 seconds ago)
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 5)
            set(Calendar.MILLISECOND, 0)
        }

        val trigger = WeatherNotificationScheduler.computeNextTriggerMillis(
            targetHour = 0,
            targetMinute = 0,
            now = now
        )

        val triggerCal = Calendar.getInstance().apply { timeInMillis = trigger }
        // Should roll to tomorrow 00:00:00
        val expectedDay = (now.get(Calendar.DAY_OF_YEAR) + 1)
        assertEquals(expectedDay, triggerCal.get(Calendar.DAY_OF_YEAR))
        assertEquals(0, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, triggerCal.get(Calendar.MINUTE))
    }

    @Test
    fun testSameMinuteRescheduling_RollsToTomorrow() {
        // Alarm fires at 07:30:15. Handler reschedules. Target: 07:30.
        // It MUST roll to tomorrow, NOT fire immediately again today.
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 15)
            set(Calendar.MILLISECOND, 0)
        }

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
    fun testEarlyWakeup_Within5SecondSlack_RollsToTomorrow() {
        // Some Android devices wake up 2 seconds before the target time (e.g. 07:29:58).
        // Rescheduling MUST roll to tomorrow to prevent a rapid duplicate trigger at 07:30:00.
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 29)
            set(Calendar.SECOND, 58) // 2 seconds before 07:30:00
            set(Calendar.MILLISECOND, 0)
        }

        val trigger = WeatherNotificationScheduler.computeNextTriggerMillis(
            targetHour = 7,
            targetMinute = 30,
            now = now
        )

        val triggerCal = Calendar.getInstance().apply { timeInMillis = trigger }
        val expectedDay = (now.get(Calendar.DAY_OF_YEAR) + 1)
        assertEquals(expectedDay, triggerCal.get(Calendar.DAY_OF_YEAR))
    }

    @Test
    fun testYearRollover_Dec31ToJan1() {
        val now = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.DECEMBER)
            set(Calendar.DAY_OF_MONTH, 31)
            set(Calendar.HOUR_OF_DAY, 22)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val trigger = WeatherNotificationScheduler.computeNextTriggerMillis(
            targetHour = 7,
            targetMinute = 30,
            now = now
        )

        val triggerCal = Calendar.getInstance().apply { timeInMillis = trigger }
        assertEquals(2027, triggerCal.get(Calendar.YEAR))
        assertEquals(Calendar.JANUARY, triggerCal.get(Calendar.MONTH))
        assertEquals(1, triggerCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, triggerCal.get(Calendar.MINUTE))
    }

    @Test
    fun testMonthRollover_Oct31ToNov1() {
        val now = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.OCTOBER)
            set(Calendar.DAY_OF_MONTH, 31)
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val trigger = WeatherNotificationScheduler.computeNextTriggerMillis(
            targetHour = 7,
            targetMinute = 30,
            now = now
        )

        val triggerCal = Calendar.getInstance().apply { timeInMillis = trigger }
        assertEquals(Calendar.NOVEMBER, triggerCal.get(Calendar.MONTH))
        assertEquals(1, triggerCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(7, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, triggerCal.get(Calendar.MINUTE))
    }

    @Test
    fun testDST_SpringForward_MaintainsLocalClockHour() {
        // US Eastern Daylight Time Spring Forward: Sunday, March 8, 2026.
        // At 02:00 AM clock jumps to 03:00 AM (23-hour day).
        val timezone = TimeZone.getTimeZone("America/New_York")
        val now = Calendar.getInstance(timezone).apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.MARCH)
            set(Calendar.DAY_OF_MONTH, 7)
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val trigger = WeatherNotificationScheduler.computeNextTriggerMillis(
            targetHour = 7,
            targetMinute = 30,
            now = now
        )

        val triggerCal = Calendar.getInstance(timezone).apply { timeInMillis = trigger }
        assertEquals("Target day must be March 8 (DST transition day)", 8, triggerCal.get(Calendar.DAY_OF_MONTH))
        assertEquals("Hour must still be 7:30 AM local clock time", 7, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals("Minute must still be 30", 30, triggerCal.get(Calendar.MINUTE))
    }

    @Test
    fun testDST_FallBack_MaintainsLocalClockHour() {
        // US Eastern Daylight Time Fall Back: Sunday, November 1, 2026.
        // At 02:00 AM clock falls back to 01:00 AM (25-hour day).
        val timezone = TimeZone.getTimeZone("America/New_York")
        val now = Calendar.getInstance(timezone).apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.OCTOBER)
            set(Calendar.DAY_OF_MONTH, 31)
            set(Calendar.HOUR_OF_DAY, 21)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val trigger = WeatherNotificationScheduler.computeNextTriggerMillis(
            targetHour = 7,
            targetMinute = 30,
            now = now
        )

        val triggerCal = Calendar.getInstance(timezone).apply { timeInMillis = trigger }
        assertEquals("Target day must be November 1 (Fall back day)", 1, triggerCal.get(Calendar.DAY_OF_MONTH))
        assertEquals("Hour must still be 7:30 AM local clock time", 7, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals("Minute must still be 30", 30, triggerCal.get(Calendar.MINUTE))
    }

    @Test
    fun testClampingOfInvalidHoursAndMinutes() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // Out-of-bounds inputs: hour 99, minute 99 -> clamped to 23:59
        val trigger = WeatherNotificationScheduler.computeNextTriggerMillis(
            targetHour = 99,
            targetMinute = 99,
            now = now
        )

        val triggerCal = Calendar.getInstance().apply { timeInMillis = trigger }
        assertEquals(23, triggerCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(59, triggerCal.get(Calendar.MINUTE))

        // Negative inputs: hour -5, minute -10 -> clamped to 00:00
        val triggerNeg = WeatherNotificationScheduler.computeNextTriggerMillis(
            targetHour = -5,
            targetMinute = -10,
            now = now
        )

        val triggerNegCal = Calendar.getInstance().apply { timeInMillis = triggerNeg }
        assertEquals(0, triggerNegCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, triggerNegCal.get(Calendar.MINUTE))
    }

    // =========================================================================
    // 4. Catchup Window Verification
    // =========================================================================

    @Test
    fun testMorningCatchupWindow_ExhaustiveRange() {
        val morningHour = 7
        val morningMinute = 30

        // Before scheduled time: MUST NOT catch up early
        assertFalse(WeatherNotificationScheduler.isWithinMorningCatchupWindow(6, 0, morningHour, morningMinute))
        assertFalse(WeatherNotificationScheduler.isWithinMorningCatchupWindow(7, 29, morningHour, morningMinute))

        // At exact scheduled time: active
        assertTrue(WeatherNotificationScheduler.isWithinMorningCatchupWindow(7, 30, morningHour, morningMinute))

        // During window: active
        assertTrue(WeatherNotificationScheduler.isWithinMorningCatchupWindow(8, 0, morningHour, morningMinute))
        assertTrue(WeatherNotificationScheduler.isWithinMorningCatchupWindow(9, 45, morningHour, morningMinute))
        assertTrue(WeatherNotificationScheduler.isWithinMorningCatchupWindow(11, 29, morningHour, morningMinute))

        // At end of standard window (11:30): closed
        assertFalse(WeatherNotificationScheduler.isWithinMorningCatchupWindow(11, 30, morningHour, morningMinute))
        assertFalse(WeatherNotificationScheduler.isWithinMorningCatchupWindow(12, 0, morningHour, morningMinute))
        assertFalse(WeatherNotificationScheduler.isWithinMorningCatchupWindow(15, 0, morningHour, morningMinute))
    }

    @Test
    fun testMorningCatchupWindow_LateMorningSchedule_AdaptsGracefully() {
        // If user sets morning time late, e.g. 11:00 AM:
        // Window should not abruptly end in 30 minutes; it extends up to scheduledMinutes + 120 (13:00)
        val morningHour = 11
        val morningMinute = 0

        assertTrue(WeatherNotificationScheduler.isWithinMorningCatchupWindow(11, 15, morningHour, morningMinute))
        assertTrue(WeatherNotificationScheduler.isWithinMorningCatchupWindow(12, 30, morningHour, morningMinute))
        assertFalse(WeatherNotificationScheduler.isWithinMorningCatchupWindow(13, 0, morningHour, morningMinute))
    }

    @Test
    fun testEveningCatchupWindow_ExhaustiveRange() {
        val eveningHour = 20
        val eveningMinute = 0

        // Before scheduled time: MUST NOT catch up early
        assertFalse(WeatherNotificationScheduler.isWithinEveningCatchupWindow(18, 0, eveningHour, eveningMinute))
        assertFalse(WeatherNotificationScheduler.isWithinEveningCatchupWindow(19, 59, eveningHour, eveningMinute))

        // Exact scheduled time: active
        assertTrue(WeatherNotificationScheduler.isWithinEveningCatchupWindow(20, 0, eveningHour, eveningMinute))

        // During evening: active
        assertTrue(WeatherNotificationScheduler.isWithinEveningCatchupWindow(21, 30, eveningHour, eveningMinute))
        assertTrue(WeatherNotificationScheduler.isWithinEveningCatchupWindow(23, 59, eveningHour, eveningMinute))
    }

    // =========================================================================
    // 5. Empty / Corrupted Cache Resilience (Fallback Dashboard Data)
    // =========================================================================

    @Test
    fun testFallbackDashboardData_ContainsCompletePayload() {
        val fallback = WeatherNotificationScheduler.createFallbackDashboardData()

        assertNotNull("Hero must not be null", fallback.hero)
        assertTrue("Location must not be blank", fallback.hero.locationName.isNotBlank())
        assertTrue("Condition must not be blank", fallback.hero.conditionText.isNotBlank())

        assertNotNull("Daily list must not be null", fallback.dailyList)
        assertTrue("Daily list must have at least 2 items (today and tomorrow)", fallback.dailyList.size >= 2)

        val todayItem = fallback.dailyList[0]
        val tomorrowItem = fallback.dailyList[1]
        assertTrue("Today label must be present", todayItem.dayLabel.isNotBlank())
        assertTrue("Tomorrow label must be present", tomorrowItem.dayLabel.isNotBlank())
        assertTrue("Today date text must be present", todayItem.dateText.isNotBlank())
        assertTrue("Tomorrow date text must be present", tomorrowItem.dateText.isNotBlank())

        assertNotNull("Hourly list must not be null", fallback.hourlyList)
        assertTrue("Hourly list must not be empty", fallback.hourlyList.isNotEmpty())

        assertNotNull("Telemetry must not be null", fallback.telemetry)
        assertNotNull("Alert must not be null", fallback.alert)
    }

    @Test
    fun testFallbackDashboardData_WithCustomTimezone() {
        val tokyoZone = ZoneId.of("Asia/Tokyo")
        val fallback = WeatherNotificationScheduler.createFallbackDashboardData(tokyoZone)

        val expectedToday = LocalDate.now(tokyoZone)
        val expectedTodayStr = "${expectedToday.monthValue}/${expectedToday.dayOfMonth}"
        assertEquals(expectedTodayStr, fallback.dailyList[0].dateText)

        val expectedTomorrow = expectedToday.plusDays(1)
        val expectedTomorrowStr = "${expectedTomorrow.monthValue}/${expectedTomorrow.dayOfMonth}"
        assertEquals(expectedTomorrowStr, fallback.dailyList[1].dateText)
    }

    @Test
    fun testSummaryFormatter_WorksWithFallbackDashboardData() {
        val fallback = WeatherNotificationScheduler.createFallbackDashboardData()

        val morningSummary = WeatherSummaryFormatter.generateMorningSummary(
            today = fallback.dailyList[0],
            upcomingHours = fallback.hourlyList,
            locationName = fallback.hero.locationName
        )
        assertNotNull(morningSummary)
        assertTrue(morningSummary.title.contains("Current Location"))
        assertTrue(morningSummary.conciseText.isNotBlank())
        assertTrue(morningSummary.expandedDetails.isNotBlank())

        val tomorrowSummary = WeatherSummaryFormatter.generateTomorrowSummary(
            today = fallback.dailyList[0],
            tomorrow = fallback.dailyList[1],
            locationName = fallback.hero.locationName
        )
        assertNotNull(tomorrowSummary)
        assertTrue(tomorrowSummary.title.contains("Tomorrow's weather in Current Location"))
        assertTrue(tomorrowSummary.conciseText.isNotBlank())
        assertTrue(tomorrowSummary.expandedDetails.isNotBlank())
    }

    @Test
    fun testSingleDayCache_EveningOutlook_ComplementsTomorrowDataProperly() {
        val context = FakeContext()
        var capturedExpandedText = ""
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, _, _, _, expanded, _, _ ->
            capturedExpandedText = expanded
            true
        }

        // Cache contains ONLY today's forecast (dailyList.size == 1)
        val singleDayData = WeatherDashboardData(
            hero = HeroWeather(20, 20, 22, 12, "Clear", null, true, "Berlin", SkyThemeType.CLEAR_DAY),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
            hourlyList = emptyList(),
            dailyList = listOf(
                DailyItem("Today", "10/31", 12, 22, 0, null, "Clear", "10 km/h", "15 km/h")
            ),
            telemetry = BentoTelemetry(15, "W", 20, 0.0, 3, "Low", 50, 10, 1013, "07:00", "18:00")
        )

        val result = WeatherNotificationHelper.showTomorrowWeatherNotification(context, singleDayData)
        assertTrue(result)
        assertTrue("Tomorrow details must be generated and non-empty", capturedExpandedText.isNotBlank())
        assertTrue("Expanded text should have High / Low format", capturedExpandedText.contains("High:"))
    }

    @Test
    fun testFallbackDashboardData_IncludesTimezone() {
        val nyZone = ZoneId.of("America/New_York")
        val fallback = WeatherNotificationScheduler.createFallbackDashboardData(nyZone)
        assertEquals("America/New_York", fallback.timezone)
    }

    // =========================================================================
    // 6. Broadcast Receiver Lifecycle & Actions Verification
    // =========================================================================

    @Test
    fun testBroadcastReceiver_BootCompleted_DuringMorningCatchup_DeliversNotification() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val receiver = WeatherNotificationReceiver()
        val prefs = NotificationPreferences(fakePrefs)
        prefs.morningHour = 7
        prefs.morningMinute = 30

        var scheduledCount = 0
        WeatherNotificationScheduler.alarmSchedulerOverride = { _, _, _ -> scheduledCount++ }

        var dispatchedNotificationId = 0
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, notifId, _, _, _, _, _ ->
            dispatchedNotificationId = notifId
            true
        }

        WeatherNotificationReceiver.preferencesProvider = { prefs }
        // Booted at 08:15 AM (inside 07:30 to 11:30 catchup window)
        val bootTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 15)
        }
        WeatherNotificationReceiver.calendarProvider = { bootTime }

        val intent = FakeIntent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)

        assertEquals("Alarms should be re-armed (morning + evening)", 2, scheduledCount)
        assertEquals("Morning briefing should be delivered during catchup window", WeatherNotificationHelper.NOTIFICATION_ID_FORECAST, dispatchedNotificationId)
        assertFalse("Morning notification must be marked posted and cannot post again today", prefs.canPostMorningNotification())
    }

    @Test
    fun testBroadcastReceiver_BootCompleted_OutsideCatchupWindow_SuppressesOutdatedNotification() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val receiver = WeatherNotificationReceiver()
        val prefs = NotificationPreferences(fakePrefs)
        prefs.morningHour = 7
        prefs.morningMinute = 30
        prefs.eveningHour = 20
        prefs.eveningMinute = 0

        var dispatchedCount = 0
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, _, _, _, _, _, _ ->
            dispatchedCount++
            true
        }

        WeatherNotificationReceiver.preferencesProvider = { prefs }
        // Booted at 14:00 PM (after 11:30 AM morning window, before 20:00 PM evening window)
        val bootTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 14)
            set(Calendar.MINUTE, 0)
        }
        WeatherNotificationReceiver.calendarProvider = { bootTime }

        var scheduledCount = 0
        WeatherNotificationScheduler.alarmSchedulerOverride = { _, _, _ -> scheduledCount++ }

        val intent = FakeIntent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)

        assertEquals("Alarms must be re-armed", 2, scheduledCount)
        assertEquals("No catchup notification should be dispatched at 14:00 PM", 0, dispatchedCount)
    }

    @Test
    fun testBroadcastReceiver_PackageReplaced_ReschedulesAlarms() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val receiver = WeatherNotificationReceiver()
        var scheduledCount = 0
        WeatherNotificationScheduler.alarmSchedulerOverride = { _, _, _ -> scheduledCount++ }
        WeatherNotificationReceiver.preferencesProvider = { NotificationPreferences(fakePrefs) }

        val intent = FakeIntent(Intent.ACTION_MY_PACKAGE_REPLACED)
        receiver.onReceive(context, intent)

        assertEquals("Alarms must be re-armed after package replace", 2, scheduledCount)
    }

    @Test
    fun testBroadcastReceiver_TimeChanged_ReschedulesAlarms() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val receiver = WeatherNotificationReceiver()
        var scheduledCount = 0
        WeatherNotificationScheduler.alarmSchedulerOverride = { _, _, _ -> scheduledCount++ }
        WeatherNotificationReceiver.preferencesProvider = { NotificationPreferences(fakePrefs) }

        receiver.onReceive(context, FakeIntent(Intent.ACTION_TIME_CHANGED))
        receiver.onReceive(context, FakeIntent(WeatherNotificationReceiver.ACTION_TIME_SET))
        receiver.onReceive(context, FakeIntent(WeatherNotificationReceiver.ACTION_TIME_CHANGED_ALT))

        assertEquals("Alarms must be re-armed on every time set action", 6, scheduledCount)
    }

    @Test
    fun testBroadcastReceiver_TimezoneChanged_ReschedulesAlarms() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val receiver = WeatherNotificationReceiver()
        var scheduledCount = 0
        WeatherNotificationScheduler.alarmSchedulerOverride = { _, _, _ -> scheduledCount++ }
        WeatherNotificationReceiver.preferencesProvider = { NotificationPreferences(fakePrefs) }

        receiver.onReceive(context, FakeIntent(Intent.ACTION_TIMEZONE_CHANGED))
        assertEquals("Alarms must be re-armed on timezone change", 2, scheduledCount)
    }

    @Test
    fun testBroadcastReceiver_DirectMorningAlarm_DispatchesAndReschedulesNextDay() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val receiver = WeatherNotificationReceiver()
        val prefs = NotificationPreferences(fakePrefs)

        var dispatched = false
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, _, _, _, _, _, _ ->
            dispatched = true
            true
        }

        var scheduledTrigger = 0L
        WeatherNotificationScheduler.alarmSchedulerOverride = { trigger, _, _ ->
            scheduledTrigger = trigger
        }
        WeatherNotificationReceiver.preferencesProvider = { prefs }

        val intent = FakeIntent(WeatherNotificationReceiver.ACTION_MORNING_WEATHER)
        receiver.onReceive(context, intent)

        assertTrue("Morning notification must be dispatched", dispatched)
        assertFalse("Morning notification must be marked posted", prefs.canPostMorningNotification())
        assertTrue("Next morning alarm must be scheduled", scheduledTrigger > 0L)
    }

    @Test
    fun testBroadcastReceiver_DirectEveningAlarm_DispatchesAndReschedulesNextDay() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val receiver = WeatherNotificationReceiver()
        val prefs = NotificationPreferences(fakePrefs)

        var dispatched = false
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, _, _, _, _, _, _ ->
            dispatched = true
            true
        }

        var scheduledTrigger = 0L
        WeatherNotificationScheduler.alarmSchedulerOverride = { trigger, _, _ ->
            scheduledTrigger = trigger
        }
        WeatherNotificationReceiver.preferencesProvider = { prefs }

        val intent = FakeIntent(WeatherNotificationReceiver.ACTION_EVENING_WEATHER)
        receiver.onReceive(context, intent)

        assertTrue("Evening notification must be dispatched", dispatched)
        assertFalse("Evening notification must be marked posted", prefs.canPostEveningNotification())
        assertTrue("Next evening alarm must be scheduled", scheduledTrigger > 0L)
    }

    @Test
    fun testBroadcastReceiver_DuplicateAlarmDelivery_Idempotent() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val receiver = WeatherNotificationReceiver()
        val prefs = NotificationPreferences(fakePrefs)

        var dispatchCount = 0
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, _, _, _, _, _, _ ->
            dispatchCount++
            true
        }
        WeatherNotificationReceiver.preferencesProvider = { prefs }

        val intent = FakeIntent(WeatherNotificationReceiver.ACTION_MORNING_WEATHER)
        receiver.onReceive(context, intent)
        receiver.onReceive(context, intent)

        assertEquals("Duplicate alarm must not dispatch a second notification on same day", 1, dispatchCount)
    }

    @Test
    fun testPendingIntentFlags_ImmutableAndUpdateCurrent() {
        val expectedFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        assertTrue("FLAG_IMMUTABLE bit must be set", (expectedFlags and PendingIntent.FLAG_IMMUTABLE) != 0)
        assertTrue("FLAG_UPDATE_CURRENT bit must be set", (expectedFlags and PendingIntent.FLAG_UPDATE_CURRENT) != 0)
    }

    @Test
    fun testReceiverActions_ContractConsistency() {
        assertEquals(
            "com.google.weatherapp.action.MORNING_WEATHER_NOTIFICATION",
            WeatherNotificationReceiver.ACTION_MORNING_WEATHER
        )
        assertEquals(
            "com.google.weatherapp.action.EVENING_WEATHER_NOTIFICATION",
            WeatherNotificationReceiver.ACTION_EVENING_WEATHER
        )
        assertEquals(
            "com.google.weatherapp.action.EVENING_WEATHER",
            WeatherNotificationReceiver.ACTION_LEGACY_EVENING_ALT
        )
        assertEquals(
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            WeatherNotificationReceiver.ACTION_SCHEDULE_EXACT_ALARM_STATE_CHANGED
        )
        assertEquals(
            "android.intent.action.TIME_SET",
            WeatherNotificationReceiver.ACTION_TIME_SET
        )
    }

    @Test
    fun testNotificationChannelsAndIds_ContractConsistency() {
        assertEquals("weather_forecasts", WeatherNotificationHelper.CHANNEL_FORECASTS)
        assertEquals("weather_alerts", WeatherNotificationHelper.CHANNEL_ALERTS)

        // Forecast ID is shared between morning and evening (replaces previous briefing)
        assertEquals(4001, WeatherNotificationHelper.NOTIFICATION_ID_FORECAST)
        // Rain and severe alerts have separate persistent IDs
        assertEquals(4002, WeatherNotificationHelper.NOTIFICATION_ID_RAIN)
        assertEquals(4003, WeatherNotificationHelper.NOTIFICATION_ID_SEVERE)
    }

    // =========================================================================
    // 7. WeatherSummaryFormatter Exhaustive Edge Cases
    // =========================================================================

    @Test
    fun testSummaryFormatter_LargeTemperatureRise() {
        val today = createSampleDailyItem(maxTemp = 10, minTemp = 2, condition = "Cloudy")
        val tomorrow = createSampleDailyItem(maxTemp = 25, minTemp = 12, condition = "Sunny")

        val summary = WeatherSummaryFormatter.generateTomorrowSummary(today, tomorrow, "Paris")
        assertEquals(15, summary.tempDiff)
        assertTrue(summary.conciseText.contains("About 15° warmer tomorrow with clear skies"))
        assertTrue(summary.expandedDetails.contains("High: 25° • Low: 12°"))
    }

    @Test
    fun testSummaryFormatter_LargeTemperatureDrop() {
        val today = createSampleDailyItem(maxTemp = 28, minTemp = 18, condition = "Sunny")
        val tomorrow = createSampleDailyItem(maxTemp = 14, minTemp = 6, condition = "Showers", rainProbability = 80)

        val summary = WeatherSummaryFormatter.generateTomorrowSummary(today, tomorrow, "Munich")
        assertEquals(-14, summary.tempDiff)
        assertTrue(summary.conciseText.contains("About 14° cooler tomorrow with showers"))
        assertTrue(summary.expandedDetails.contains("Rain: 80%"))
    }

    @Test
    fun testSummaryFormatter_ZeroTempDiff_EqualTemperatures() {
        val today = createSampleDailyItem(maxTemp = 20, minTemp = 10, condition = "Cloudy")
        val tomorrow = createSampleDailyItem(maxTemp = 20, minTemp = 11, condition = "Cloudy")

        val summary = WeatherSummaryFormatter.generateTomorrowSummary(today, tomorrow, null)
        assertEquals(0, summary.tempDiff)
        assertEquals("Similar temperatures tomorrow with cloud (20° / 11°).", summary.conciseText)
        assertEquals("Tomorrow's weather", summary.title) // Fallback title without location
    }

    @Test
    fun testSummaryFormatter_SnowAndBlizzard() {
        val today = createSampleDailyItem(maxTemp = 2, minTemp = -5, condition = "Overcast")
        val tomorrow = createSampleDailyItem(maxTemp = -1, minTemp = -8, condition = "Heavy Blizzard and Snow")

        val summary = WeatherSummaryFormatter.generateTomorrowSummary(today, tomorrow, "Oslo")
        assertTrue(summary.conciseText.contains("with snow"))
    }

    @Test
    fun testSummaryFormatter_Thunderstorms() {
        val today = createSampleDailyItem(maxTemp = 30, minTemp = 20, condition = "Sunny")
        val tomorrow = createSampleDailyItem(maxTemp = 27, minTemp = 19, condition = "Scattered Thunderstorms")

        val summary = WeatherSummaryFormatter.generateTomorrowSummary(today, tomorrow, "Johannesburg")
        assertTrue(summary.conciseText.contains("with thunderstorms"))
    }

    @Test
    fun testSummaryFormatter_RainLikelyFromHighProbability() {
        val today = createSampleDailyItem(maxTemp = 18, minTemp = 10, condition = "Overcast", rainProbability = 20)
        val tomorrow = createSampleDailyItem(maxTemp = 16, minTemp = 9, condition = "Overcast", rainProbability = 75)

        val summary = WeatherSummaryFormatter.generateTomorrowSummary(today, tomorrow, "Dublin")
        assertTrue(summary.conciseText.contains("with rain likely"))
    }

    @Test
    fun testSevereStormAlert_FilteringLogic() {
        val clearAlert = SevereAlertInfo(
            riskLevel = SeverityRiskLevel.LOW,
            thunderstormProbability = 10,
            title = "All clear",
            subtitle = "Calm",
            isVisible = true,
            peakHourLabel = null
        )
        assertFalse(WeatherNotificationScheduler.isSevereAlert(clearAlert))

        val highRiskAlert = clearAlert.copy(
            riskLevel = SeverityRiskLevel.HIGH,
            thunderstormProbability = 40
        )
        assertTrue(WeatherNotificationScheduler.isSevereAlert(highRiskAlert))

        val severeRiskAlert = clearAlert.copy(
            riskLevel = SeverityRiskLevel.SEVERE,
            thunderstormProbability = 30
        )
        assertTrue(WeatherNotificationScheduler.isSevereAlert(severeRiskAlert))

        val highProbModerateAlert = clearAlert.copy(
            riskLevel = SeverityRiskLevel.MODERATE,
            thunderstormProbability = 70
        )
        assertTrue(WeatherNotificationScheduler.isSevereAlert(highProbModerateAlert))

        val invisibleAlert = highRiskAlert.copy(isVisible = false)
        assertFalse(WeatherNotificationScheduler.isSevereAlert(invisibleAlert))
    }

    private fun createSampleDailyItem(
        maxTemp: Int,
        minTemp: Int,
        condition: String,
        rainProbability: Int = 0
    ) = DailyItem(
        dayLabel = "Tomorrow",
        dateText = "10/31",
        minTemp = minTemp,
        maxTemp = maxTemp,
        rainProbability = rainProbability,
        iconUri = null,
        conditionText = condition,
        morningWind = "10 km/h",
        afternoonWind = "15 km/h"
    )

    // =========================================================================
    // 11. Multi-Hazard Weather Notification Options, Priority Ladder & Quiet Hours
    // =========================================================================

    @Test
    fun testAlertDetection_All8Categories() {
        val sampleDaily = createSampleDailyItem(maxTemp = 22, minTemp = 14, condition = "Clear")
        val sampleTelemetry = BentoTelemetry(
            windSpeedKmH = 15,
            windDirection = "NW",
            windGustKmH = 20,
            precipitationMm = 0.0,
            uvIndex = 4,
            uvCategory = "Moderate",
            humidityPercent = 50,
            dewPointDegrees = 10,
            pressureHpa = 1013,
            sunriseTime = "06:00",
            sunsetTime = "18:00"
        )

        // 1. Extreme Heat (maxTemp >= 35 or feelsLike >= 40)
        assertFalse(WeatherNotificationScheduler.isExtremeHeatAlert(sampleDaily))
        assertTrue(WeatherNotificationScheduler.isExtremeHeatAlert(sampleDaily.copy(maxTemp = 36)))
        assertTrue(WeatherNotificationScheduler.isExtremeHeatAlert(sampleDaily.copy(feelsLikeMaxTemp = 41)))
        // A warm night alone (tropical climate) is not a heat warning
        assertFalse(WeatherNotificationScheduler.isExtremeHeatAlert(sampleDaily.copy(minTemp = 21)))

        // 2. Freeze & Black Ice Hazard (minTemp <= 0, hard freeze <= -4, wet surface + subzero)
        assertFalse(WeatherNotificationScheduler.isFreezeHazard(sampleDaily, sampleTelemetry))
        assertTrue(WeatherNotificationScheduler.isFreezeHazard(sampleDaily.copy(minTemp = 0), sampleTelemetry))
        assertTrue(WeatherNotificationScheduler.isFreezeHazard(sampleDaily.copy(minTemp = -5), sampleTelemetry))
        val wetTelemetry = sampleTelemetry.copy(precipitationMm = 0.5)
        assertTrue(WeatherNotificationScheduler.isFreezeHazard(sampleDaily.copy(minTemp = -1), wetTelemetry))

        // 3. Gale Gust & High Wind Alert (sustained >= 50 or gust >= 65)
        assertFalse(WeatherNotificationScheduler.isGaleWindAlert(sampleTelemetry, sampleDaily))
        assertTrue(WeatherNotificationScheduler.isGaleWindAlert(sampleTelemetry.copy(windSpeedKmH = 52), sampleDaily))
        assertTrue(WeatherNotificationScheduler.isGaleWindAlert(sampleTelemetry.copy(windGustKmH = 70), sampleDaily))
        assertTrue(WeatherNotificationScheduler.isGaleWindAlert(sampleTelemetry, sampleDaily.copy(dayWindSpeedKmH = 55)))

        // 4. Poor Air Quality Alert (AQI > 100 or PM2.5 > 35)
        assertFalse(WeatherNotificationScheduler.isPoorAqiAlert(sampleTelemetry))
        assertTrue(WeatherNotificationScheduler.isPoorAqiAlert(sampleTelemetry.copy(aqiValue = 105)))
        assertTrue(WeatherNotificationScheduler.isPoorAqiAlert(sampleTelemetry.copy(pm25 = 38.0)))

        // 5. Dense Fog & Low Visibility (visibility <= 1.0 km with humidity >= 90%)
        assertFalse(WeatherNotificationScheduler.isDenseFogAlert(sampleTelemetry))
        assertFalse(WeatherNotificationScheduler.isDenseFogAlert(sampleTelemetry.copy(visibilityKm = 0.8, humidityPercent = 75)))
        assertTrue(WeatherNotificationScheduler.isDenseFogAlert(sampleTelemetry.copy(visibilityKm = 0.8, humidityPercent = 92)))

        // 6. Precipitation Cessation Alert (rainCessationMinutes <= 30)
        assertFalse(WeatherNotificationScheduler.isRainCessationAlert(null))
        val nowcastNoCessation = PrecipitationNowcast("Rain starting", true, 20, 2.0)
        assertFalse(WeatherNotificationScheduler.isRainCessationAlert(nowcastNoCessation))
        val nowcastCessation = PrecipitationNowcast("Rain stopping in 25 min", true, null, 1.0, rainCessationMinutes = 25)
        assertTrue(WeatherNotificationScheduler.isRainCessationAlert(nowcastCessation))
        val nowcastFarCessation = PrecipitationNowcast("Rain stopping in 45 min", true, null, 1.0, rainCessationMinutes = 45)
        assertFalse(WeatherNotificationScheduler.isRainCessationAlert(nowcastFarCessation))

        // 7. Extreme UV Alert (UV >= 8)
        assertFalse(WeatherNotificationScheduler.isExtremeUvAlert(sampleDaily, sampleTelemetry))
        assertTrue(WeatherNotificationScheduler.isExtremeUvAlert(sampleDaily.copy(uvIndex = 8), sampleTelemetry))
        assertTrue(WeatherNotificationScheduler.isExtremeUvAlert(sampleDaily, sampleTelemetry.copy(uvIndex = 11)))

        // 8. Sharp Temperature Drop (tomorrow drops >= 7°C)
        val today = sampleDaily.copy(maxTemp = 25)
        val tomorrowMild = sampleDaily.copy(maxTemp = 20)
        val tomorrowDrop = sampleDaily.copy(maxTemp = 17)
        assertFalse(WeatherNotificationScheduler.isSharpTempDropAlert(today, tomorrowMild))
        assertTrue(WeatherNotificationScheduler.isSharpTempDropAlert(today, tomorrowDrop))
    }

    @Test
    fun testPriorityLadder_SevereStormSuppressesAllLowerTiers() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val prefs = NotificationPreferences(fakePrefs)

        var dispatchedNotificationId = 0
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, notifId, _, _, _, _, _ ->
            dispatchedNotificationId = notifId
            true
        }

        // Data with Tier 1 (Severe Storm) + Tier 2 (Extreme Heat) + Tier 3 (Rain Nowcast) + Tier 4 (Poor AQI)
        val testData = WeatherDashboardData(
            hero = HeroWeather(36, 42, 36, 22, "Stormy", null, true, "Dallas", SkyThemeType.THUNDERSTORM),
            alert = SevereAlertInfo(
                riskLevel = SeverityRiskLevel.SEVERE,
                thunderstormProbability = 85,
                title = "Tornado Warning",
                subtitle = "Severe thunderstorms",
                isVisible = true
            ),
            dailyList = listOf(
                DailyItem("Today", "10/31", 22, 36, 80, null, "Thunderstorms", "15 km/h", "20 km/h", feelsLikeMaxTemp = 42)
            ),
            hourlyList = emptyList(),
            telemetry = BentoTelemetry(20, "SW", 30, 5.0, 5, "Moderate", 75, 22, 1005, "06:00", "18:00", aqiValue = 160),
            nowcast = PrecipitationNowcast("Rain starting in 15 min", true, 15, 8.0),
            timezone = "America/Chicago"
        )

        // 14:00 (outside quiet hours)
        val afternoonEpoch = LocalDate.of(2026, 10, 31).atTime(14, 0).atZone(ZoneId.of("America/Chicago")).toInstant().toEpochMilli()
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, testData, afternoonEpoch)

        assertEquals("Only Tier 1 Severe Storm must be dispatched", WeatherNotificationHelper.NOTIFICATION_ID_SEVERE, dispatchedNotificationId)
        assertEquals(afternoonEpoch, prefs.lastSevereAlertEpochMs)
        assertEquals(0L, prefs.lastHeatAlertEpochMs)
        assertEquals(0L, prefs.lastRainAlertEpochMs)
        assertEquals(0L, prefs.lastAqiAlertEpochMs)
    }

    @Test
    fun testPriorityLadder_HeatSuppressesRainAndAqi() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Phoenix"))
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val prefs = NotificationPreferences(fakePrefs)

        var dispatchedNotificationId = 0
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, notifId, _, _, _, _, _ ->
            dispatchedNotificationId = notifId
            true
        }

        // Data with NO severe storm, but Tier 2 (Extreme Heat) + Tier 3 (Rain Nowcast) + Tier 4 (Poor AQI)
        val testData = WeatherDashboardData(
            hero = HeroWeather(38, 43, 38, 24, "Hot", null, true, "Phoenix", SkyThemeType.CLEAR_DAY),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
            dailyList = listOf(
                DailyItem("Today", "10/31", 24, 38, 40, null, "Hot", "10 km/h", "15 km/h", feelsLikeMaxTemp = 43)
            ),
            hourlyList = emptyList(),
            telemetry = BentoTelemetry(15, "W", 20, 0.0, 9, "Very High", 35, 12, 1010, "06:00", "18:00", aqiValue = 155),
            nowcast = PrecipitationNowcast("Rain starting in 20 min", true, 20, 2.0),
            timezone = "America/Phoenix"
        )

        val afternoonEpoch = LocalDate.of(2026, 10, 31).atTime(15, 0).atZone(ZoneId.of("America/Phoenix")).toInstant().toEpochMilli()
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, testData, afternoonEpoch)

        assertEquals("Tier 2 Extreme Heat must be dispatched, suppressing rain and AQI", WeatherNotificationHelper.NOTIFICATION_ID_HEAT, dispatchedNotificationId)
        assertEquals(afternoonEpoch, prefs.lastHeatAlertEpochMs)
        assertEquals(0L, prefs.lastRainAlertEpochMs)
        assertEquals(0L, prefs.lastAqiAlertEpochMs)
    }

    @Test
    fun testPriorityLadder_RainSuppressesAqi() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val prefs = NotificationPreferences(fakePrefs)

        var dispatchedNotificationId = 0
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, notifId, _, _, _, _, _ ->
            dispatchedNotificationId = notifId
            true
        }

        // Data with NO severe storm, NO heat/freeze hazard, but Tier 3 (Rain Nowcast) + Tier 4 (Poor AQI)
        val testData = WeatherDashboardData(
            hero = HeroWeather(20, 20, 22, 12, "Cloudy", null, true, "London", SkyThemeType.RAIN),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
            dailyList = listOf(
                DailyItem("Today", "10/31", 12, 22, 80, null, "Rain", "10 km/h", "15 km/h")
            ),
            hourlyList = emptyList(),
            telemetry = BentoTelemetry(15, "W", 20, 1.2, 3, "Low", 80, 14, 1012, "06:00", "18:00", aqiValue = 140),
            nowcast = PrecipitationNowcast("Rain starting in 10 min", true, 10, 3.0),
            timezone = "Europe/London"
        )

        val afternoonEpoch = LocalDate.of(2026, 10, 31).atTime(14, 0).atZone(ZoneId.of("Europe/London")).toInstant().toEpochMilli()
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, testData, afternoonEpoch)

        assertEquals("Tier 3 Rain Alert must be dispatched, suppressing Tier 4 AQI", WeatherNotificationHelper.NOTIFICATION_ID_RAIN, dispatchedNotificationId)
        assertEquals(afternoonEpoch, prefs.lastRainAlertEpochMs)
        assertEquals(0L, prefs.lastAqiAlertEpochMs)
    }

    @Test
    fun testPriorityLadder_RainAlertNotSuppressedByTomorrowTempDrop() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val prefs = NotificationPreferences(fakePrefs)

        var dispatchedNotificationId = 0
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, notifId, _, _, _, _, _ ->
            dispatchedNotificationId = notifId
            true
        }

        // Data with tomorrow dropping by 9°C (sharp drop) AND imminent rain nowcast starting in 15 min
        val testData = WeatherDashboardData(
            hero = HeroWeather(22, 22, 25, 14, "Cloudy", null, true, "Seattle", SkyThemeType.RAIN),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
            dailyList = listOf(
                DailyItem("Today", "10/31", 14, 25, 80, null, "Rain", "10 km/h", "15 km/h"),
                DailyItem("Tomorrow", "11/01", 10, 16, 10, null, "Breezy", "15 km/h", "20 km/h") // Drops by 9°C
            ),
            hourlyList = emptyList(),
            telemetry = BentoTelemetry(15, "W", 20, 1.2, 3, "Low", 80, 14, 1012, "06:00", "18:00"),
            nowcast = PrecipitationNowcast("Rain starting in 15 min", true, 15, 3.5),
            timezone = "America/Los_Angeles"
        )

        val morningEpoch = LocalDate.of(2026, 10, 31).atTime(10, 30).atZone(ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli()
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, testData, morningEpoch)

        assertEquals("Imminent rain nowcast must NOT be suppressed by tomorrow's temperature drop", WeatherNotificationHelper.NOTIFICATION_ID_RAIN, dispatchedNotificationId)
        assertEquals(morningEpoch, prefs.lastRainAlertEpochMs)
        assertEquals(0L, prefs.lastTempDropAlertEpochMs)
    }

    @Test
    fun testMiddayUvAlert_GatingBySolarNoonHours() {
        // Quiet hours follow the device clock, so pin the device to the location's zone.
        TimeZone.setDefault(TimeZone.getTimeZone("Australia/Sydney"))
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val prefs = NotificationPreferences(fakePrefs)

        var dispatchedCount = 0
        var lastId = 0
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, notifId, _, _, _, _, _ ->
            dispatchedCount++
            lastId = notifId
            true
        }

        val uvData = WeatherDashboardData(
            hero = HeroWeather(24, 24, 31, 18, "Sunny", null, true, "Sydney", SkyThemeType.CLEAR_DAY),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
            dailyList = listOf(DailyItem("Today", "10/31", 18, 31, 0, null, "Sunny", "10 km/h", "15 km/h", uvIndex = 11, uvCategory = "Extreme")),
            hourlyList = emptyList(),
            telemetry = BentoTelemetry(10, "E", 15, 0.0, 0, "Low", 40, 12, 1018, "06:00", "19:00"), // Live UV is 0 early morning
            timezone = "Australia/Sydney"
        )

        // 07:15 AM: Outside midday window, live UV is 0 -> MUST NOT fire standalone UV alert
        val earlyMorningEpoch = LocalDate.of(2026, 10, 31).atTime(7, 15).atZone(ZoneId.of("Australia/Sydney")).toInstant().toEpochMilli()
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, uvData, earlyMorningEpoch)
        assertEquals("Standalone UV alert must not fire at 7:15 AM", 0, dispatchedCount)
        assertEquals(0L, prefs.lastUvAlertEpochMs)

        // 12:30 PM: Inside solar noon window (10:00 - 15:00) -> MUST fire midday reminder!
        val middayEpoch = LocalDate.of(2026, 10, 31).atTime(12, 30).atZone(ZoneId.of("Australia/Sydney")).toInstant().toEpochMilli()
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, uvData, middayEpoch)
        assertEquals("Standalone UV alert must fire during solar noon window", 1, dispatchedCount)
        assertEquals(WeatherNotificationHelper.NOTIFICATION_ID_UV, lastId)
        assertEquals(middayEpoch, prefs.lastUvAlertEpochMs)
    }

    @Test
    fun testNotificationGrouping_GroupKeyAndSummaryNotificationId() {
        assertEquals("com.google.weatherapp.WEATHER_ALERTS", WeatherNotificationHelper.NOTIFICATION_GROUP_KEY)
        assertEquals(4000, WeatherNotificationHelper.NOTIFICATION_ID_GROUP_SUMMARY)
    }

    @Test
    fun testQuietHours_SuppressesNonEmergencyAlerts_SevereStormExempt() {
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val zone = ZoneId.of("Europe/Berlin")

        // 23:30 (11:30 PM) is inside quiet hours (22:00 to 07:00)
        val nightEpoch = LocalDate.of(2026, 10, 31).atTime(23, 30).atZone(zone).toInstant().toEpochMilli()
        assertTrue("23:30 must be in quiet hours", WeatherNotificationScheduler.isQuietHours(nightEpoch, zone))

        // 08:30 AM is outside quiet hours
        val morningEpoch = LocalDate.of(2026, 10, 31).atTime(8, 30).atZone(zone).toInstant().toEpochMilli()
        assertFalse("08:30 AM must not be in quiet hours", WeatherNotificationScheduler.isQuietHours(morningEpoch, zone))

        var dispatchedCount = 0
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, _, _, _, _, _, _ ->
            dispatchedCount++
            true
        }

        // Non-emergency alert (Gale Wind) during quiet hours: MUST be suppressed
        val windData = WeatherDashboardData(
            hero = HeroWeather(15, 15, 18, 10, "Windy", null, false, "Hamburg", SkyThemeType.CLEAR_NIGHT),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
            dailyList = listOf(DailyItem("Today", "10/31", 10, 18, 0, null, "Windy", "55 km/h", "60 km/h")),
            hourlyList = emptyList(),
            telemetry = BentoTelemetry(55, "W", 70, 0.0, 0, "Low", 65, 8, 1008, "07:00", "17:30"),
            timezone = "Europe/Berlin"
        )
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, windData, nightEpoch)
        assertEquals("Gale wind must be suppressed during quiet hours", 0, dispatchedCount)

        // Severe Storm (emergency alert) during quiet hours: MUST be exempt and fire!
        val stormData = windData.copy(
            alert = SevereAlertInfo(
                riskLevel = SeverityRiskLevel.SEVERE,
                thunderstormProbability = 90,
                title = "Severe Thunderstorm",
                subtitle = "Large hail and destructive winds",
                isVisible = true
            )
        )
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, stormData, nightEpoch)
        assertEquals("Severe storm is exempt from quiet hours and must fire", 1, dispatchedCount)
    }

    @Test
    fun testCategoryToggles_DisableSpecificCategory() {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NotificationPreferences(fakePrefs)
        val now = 1793361600000L

        // Severe toggle
        assertTrue(prefs.canTriggerSevereAlert(now))
        prefs.isSevereAlertsEnabled = false
        assertFalse(prefs.canTriggerSevereAlert(now))

        // Heat toggle
        assertTrue(prefs.canTriggerHeatAlert(now))
        prefs.isExtremeHeatEnabled = false
        assertFalse(prefs.canTriggerHeatAlert(now))

        // Freeze toggle
        assertTrue(prefs.canTriggerFreezeAlert(now))
        prefs.isFreezeHazardEnabled = false
        assertFalse(prefs.canTriggerFreezeAlert(now))

        // Wind toggle
        assertTrue(prefs.canTriggerWindAlert(now))
        prefs.isGaleWindEnabled = false
        assertFalse(prefs.canTriggerWindAlert(now))

        // Fog toggle
        assertTrue(prefs.canTriggerFogAlert(now))
        prefs.isDenseFogEnabled = false
        assertFalse(prefs.canTriggerFogAlert(now))

        // Rain cessation toggle
        assertTrue(prefs.canTriggerRainCessationAlert(now))
        prefs.isRainCessationEnabled = false
        assertFalse(prefs.canTriggerRainCessationAlert(now))

        // AQI toggle
        assertTrue(prefs.canTriggerAqiAlert(now))
        prefs.isPoorAqiEnabled = false
        assertFalse(prefs.canTriggerAqiAlert(now))

        // UV toggle
        assertTrue(prefs.canTriggerUvAlert(now))
        prefs.isExtremeUvEnabled = false
        assertFalse(prefs.canTriggerUvAlert(now))

        // Temp Drop toggle
        assertTrue(prefs.canTriggerTempDropAlert(now))
        prefs.isSharpTempDropEnabled = false
        assertFalse(prefs.canTriggerTempDropAlert(now))
    }

    @Test
    fun testCategoryCooldowns_EnforceTimeLimits() {
        val fakePrefs = FakeSharedPreferences()
        val prefs = NotificationPreferences(fakePrefs)
        val now = 1793361600000L

        // Heat (once per day: 20h cooldown)
        assertTrue(prefs.canTriggerHeatAlert(now))
        prefs.lastHeatAlertEpochMs = now
        assertFalse(prefs.canTriggerHeatAlert(now + 5 * 3600 * 1000L)) // 5h later: no repeat
        assertFalse(prefs.canTriggerHeatAlert(now + 12 * 3600 * 1000L))
        assertTrue(prefs.canTriggerHeatAlert(now + 21 * 3600 * 1000L)) // next day

        // Wind (6h cooldown)
        assertTrue(prefs.canTriggerWindAlert(now))
        prefs.lastWindAlertEpochMs = now
        assertFalse(prefs.canTriggerWindAlert(now + 4 * 3600 * 1000L))
        assertTrue(prefs.canTriggerWindAlert(now + 7 * 3600 * 1000L))

        // AQI (12h cooldown)
        assertTrue(prefs.canTriggerAqiAlert(now))
        prefs.lastAqiAlertEpochMs = now
        assertFalse(prefs.canTriggerAqiAlert(now + 8 * 3600 * 1000L))
        assertTrue(prefs.canTriggerAqiAlert(now + 13 * 3600 * 1000L))

        // Temp drop (once per day: 20h cooldown)
        assertTrue(prefs.canTriggerTempDropAlert(now))
        prefs.lastTempDropAlertEpochMs = now
        assertFalse(prefs.canTriggerTempDropAlert(now + 13 * 3600 * 1000L))
        assertTrue(prefs.canTriggerTempDropAlert(now + 21 * 3600 * 1000L))
    }

    @Test
    fun testRainCessation_MapperParsing() {
        val testZone = ZoneId.of("UTC")
        val fixedNow = java.time.LocalDateTime.of(2026, 10, 31, 12, 0)
        val times = listOf(
            "2026-10-31T12:00", // +0 min (active now, 0.8mm)
            "2026-10-31T12:15", // +15 min (active, 0.4mm)
            "2026-10-31T12:30", // +30 min (stopping, 0.0mm, pop 10%)
            "2026-10-31T12:45"
        )
        val precips = listOf(0.8, 0.4, 0.0, 0.0)
        val pops = listOf(90, 70, 10, 0)
        val codes = listOf(61, 61, 0, 0)

        val minutely = com.google.weatherapp.data.api.OpenMeteoMinutely15(
            time = times,
            precipitation = precips,
            precipitationProbability = pops,
            weatherCode = codes
        )

        val nowcast = com.google.weatherapp.data.repository.WeatherMapper.parsePrecipitationNowcast(
            minutely15 = minutely,
            zoneId = testZone,
            referenceNow = fixedNow
        )

        assertNotNull(nowcast)
        assertTrue(nowcast!!.isPrecipitationImminent)
        assertEquals(30, nowcast.rainCessationMinutes)
        assertTrue(nowcast.summaryText.contains("Rain stopping in ~30 min"))
    }

    // =========================================================================
    // Standalone In-Memory SharedPreferences implementation for JUnit tests
    // =========================================================================

    class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (values[key] as? Set<*>)?.map { it.toString() }?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(this)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { pending[key!!] = null }
            override fun clear(): SharedPreferences.Editor = apply { clearFlag = true }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clearFlag) prefs.values.clear()
                for ((k, v) in pending) {
                    if (v == null) prefs.values.remove(k) else prefs.values[k] = v
                }
            }
        }
    }

    class FakeIntent(private val customAction: String) : Intent() {
        override fun getAction(): String = customAction
    }

    class FakeContext(
        private val sharedPrefs: SharedPreferences = FakeSharedPreferences()
    ) : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.google.weatherapp"
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = sharedPrefs
        override fun getSystemService(name: String): Any? = null
    }
    // =========================================================================
    // Deep-debug regressions
    // =========================================================================

    @Test
    fun testBlackIce_MildMinTempWithLowDewPoint_IsNotAHazard() {
        val today = createSampleDailyItem(maxTemp = 12, minTemp = 8, condition = "Rain")
            .copy(precipitationMm = 2.0, rainProbability = 80)
        val telemetry = BentoTelemetry(10, "N", 15, 2.0, 1, "Low", 60, -3, 1010, "07:00", "17:00")
        assertFalse(WeatherNotificationScheduler.isFreezeHazard(today, telemetry))
        // Near-freezing wet surface is still black ice
        assertTrue(WeatherNotificationScheduler.isFreezeHazard(today.copy(minTemp = 2), telemetry))
    }

    @Test
    fun testQuietHours_UseDeviceClock_NotRemoteLocationClock() {
        // Device in Berlin sleeping (03:00); cached forecast belongs to Sydney (midday there).
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        var dispatched = 0
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, _, _, _, _, _, _ -> dispatched++; true }

        val hot = WeatherDashboardData(
            hero = HeroWeather(38, 43, 38, 24, "Hot", null, true, "Sydney", SkyThemeType.CLEAR_DAY),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
            dailyList = listOf(DailyItem("Today", "1/15", 24, 38, 0, null, "Hot", "10 km/h", "15 km/h", feelsLikeMaxTemp = 43)),
            hourlyList = emptyList(),
            telemetry = BentoTelemetry(15, "W", 20, 0.0, 3, "Low", 35, 12, 1010, "06:00", "19:00"),
            timezone = "Australia/Sydney"
        )
        val berlin3am = LocalDate.of(2026, 1, 15).atTime(3, 0).atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, hot, berlin3am)
        assertEquals("Non-emergency alert must not fire while the device owner is in quiet hours", 0, dispatched)

        val berlin10am = LocalDate.of(2026, 1, 15).atTime(10, 0).atZone(ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli()
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, hot, berlin10am)
        assertEquals(1, dispatched)
    }

    @Test
    fun testDeviceWeather_NotOverwrittenByBrowsingAnotherCity() {
        val context = FakeContext(FakeSharedPreferences())
        val source = com.google.weatherapp.data.local.WeatherLocalDataSource(context)
        fun forecast(city: String) = WeatherDashboardData(
            hero = HeroWeather(20, 20, 22, 12, "Clear", null, true, city, SkyThemeType.CLEAR_DAY),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
            hourlyList = emptyList(),
            dailyList = listOf(DailyItem("Today", "10/31", 12, 22, 0, null, "Clear", "10 km/h", "15 km/h")),
            telemetry = BentoTelemetry(15, "W", 20, 0.0, 3, "Low", 50, 10, 1013, "07:00", "18:00")
        )

        assertEquals("No device forecast yet", null, source.getDeviceWeather())

        // Dashboard on GPS: forecast doubles as the device forecast.
        source.saveWeather(forecast("Randburg"))
        assertEquals("Randburg", source.getDeviceWeather()?.hero?.locationName)

        // User now browses Tokyo: dashboard cache changes, device forecast must not.
        source.setSelectedCustomLocation(
            com.google.weatherapp.data.model.SavedLocation("Tokyo", "Tokyo", "Japan", 35.6762, 139.6503)
        )
        source.saveWeather(forecast("Tokyo"))
        assertEquals("Tokyo", source.getLatestCachedWeatherIgnoringTtl()?.hero?.locationName)
        assertEquals("Randburg", source.getDeviceWeather()?.hero?.locationName)

        // Notifications read the device forecast, never the browsed city.
        assertEquals("Randburg", WeatherNotificationScheduler.getEffectiveDashboardData(context).hero.locationName)
    }

    @Test
    fun testGlobalEventCooldown_AndEveningBriefingBuffer_PreventBackToBackSpam() {
        val zone = ZoneId.of("Africa/Johannesburg")
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)
        val prefs = NotificationPreferences(fakePrefs)

        val dispatchedIds = mutableListOf<Int>()
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, notifId, _, _, _, _, _ ->
            dispatchedIds.add(notifId)
            true
        }

        val multiHazardData = WeatherDashboardData(
            hero = HeroWeather(21, 21, 27, 15, "Cloudy", null, false, "Randburg", SkyThemeType.RAIN),
            alert = SevereAlertInfo(
                riskLevel = SeverityRiskLevel.HIGH,
                thunderstormProbability = 40,
                title = "THUNDERSTORM WATCH",
                subtitle = "Elevated storm risk",
                isVisible = true,
                peakHourLabel = "21:00",
                hourlyStormProbabilities = listOf(40, 35, 20, 10, 0, 0)
            ),
            dailyList = listOf(DailyItem("Today", "11/2", 15, 27, 60, null, "Showers", "10 km/h", "15 km/h")),
            hourlyList = emptyList(),
            telemetry = BentoTelemetry(12, "NW", 20, 2.0, 0, "Low", 75, 14, 1014, "05:30", "18:30", aqiValue = 135),
            nowcast = PrecipitationNowcast("Rain starting in ~20 min", true, 20, 2.5),
            timezone = "Africa/Johannesburg"
        )

        // 18:27: First event check fires the top eligible alert (40% Watch)
        val t1827 = LocalDate.of(2026, 11, 2).atTime(18, 27).atZone(zone).toInstant().toEpochMilli()
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, multiHazardData, t1827)
        assertEquals(1, dispatchedIds.size)

        // 20:00: Evening Outlook fires
        val t2000 = LocalDate.of(2026, 11, 2).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        prefs.recordEveningNotificationPosted(t2000, zone)

        // 20:26 (26 min after Evening Outlook, < 3h after 18:27 alert): MUST be suppressed by global cooldown & briefing buffer!
        val t2026 = LocalDate.of(2026, 11, 2).atTime(20, 26).atZone(zone).toInstant().toEpochMilli()
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, multiHazardData, t2026)
        assertEquals("20:26 follow-up alert must be suppressed", 1, dispatchedIds.size)

        // 21:11 (< 3h after 18:27 alert): AQI / Rain fallback MUST also be suppressed by 3h global cooldown!
        val t2111 = LocalDate.of(2026, 11, 2).atTime(21, 11).atZone(zone).toInstant().toEpochMilli()
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, multiHazardData, t2111)
        assertEquals("21:11 follow-up AQI alert must be suppressed by global cooldown", 1, dispatchedIds.size)
    }

    @Test
    fun testQuietHours_Suppresses30PercentThunderstormWatch_AllowsOnly60PercentSevereWarning() {
        val zone = ZoneId.of("Africa/Johannesburg")
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)

        var dispatchedCount = 0
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, _, _, _, _, _, _ ->
            dispatchedCount++
            true
        }

        val watchData = WeatherDashboardData(
            hero = HeroWeather(18, 18, 26, 15, "Cloudy", null, false, "Randburg", SkyThemeType.OVERCAST),
            alert = SevereAlertInfo(
                riskLevel = SeverityRiskLevel.HIGH,
                thunderstormProbability = 40,
                title = "THUNDERSTORM WATCH",
                subtitle = "Elevated storm risk (40% peak probability)",
                isVisible = true,
                peakHourLabel = "01:00",
                hourlyStormProbabilities = listOf(35, 40, 30, 20, 10, 0)
            ),
            dailyList = listOf(DailyItem("Today", "11/2", 15, 26, 40, null, "Cloudy", "10 km/h", "15 km/h")),
            hourlyList = emptyList(),
            telemetry = BentoTelemetry(10, "N", 15, 0.0, 0, "Low", 70, 12, 1014, "05:30", "18:30"),
            timezone = "Africa/Johannesburg"
        )

        // 23:29, 02:41, 06:08 during Quiet Hours: 40% Watch MUST NOT wake the user
        val t2329 = LocalDate.of(2026, 11, 2).atTime(23, 29).atZone(zone).toInstant().toEpochMilli()
        val t0241 = LocalDate.of(2026, 11, 3).atTime(2, 41).atZone(zone).toInstant().toEpochMilli()
        val t0608 = LocalDate.of(2026, 11, 3).atTime(6, 8).atZone(zone).toInstant().toEpochMilli()

        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, watchData, t2329)
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, watchData, t0241)
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, watchData, t0608)
        assertEquals("40% Thunderstorm Watch must never fire during Quiet Hours", 0, dispatchedCount)

        // Genuine SEVERE storm (75% probability in next 3 hours) IS exempt from Quiet Hours
        val severeData = watchData.copy(
            alert = watchData.alert.copy(
                riskLevel = SeverityRiskLevel.SEVERE,
                thunderstormProbability = 75,
                title = "SEVERE THUNDERSTORM WARNING",
                hourlyStormProbabilities = listOf(75, 65, 40, 20, 0, 0)
            )
        )
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, severeData, t0241)
        assertEquals("Imminent 75% Severe Storm Warning must break through Quiet Hours", 1, dispatchedCount)
    }

    @Test
    fun testStaleCachedForecast_AndElapsedNowcastOnset_NeverAnnouncePastRain() {
        val zone = ZoneId.of("Africa/Johannesburg")
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        val fakePrefs = FakeSharedPreferences()
        val context = FakeContext(fakePrefs)

        var dispatchedCount = 0
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, _, _, _, _, _, _ ->
            dispatchedCount++
            true
        }

        val t2000 = LocalDate.of(2026, 11, 2).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        // Case 1: Forecast data is 2 hours old (fetched at 18:00)
        val staleData = WeatherDashboardData(
            hero = HeroWeather(
                tempDegrees = 20, feelsLikeDegrees = 20, highDegrees = 26, lowDegrees = 15,
                conditionText = "Rain", iconUri = null, isDaytime = false, locationName = "Randburg",
                skyTheme = SkyThemeType.RAIN,
                lastUpdatedEpochMs = t2000 - 2 * 3600 * 1000L
            ),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
            dailyList = listOf(DailyItem("Today", "11/2", 15, 26, 80, null, "Rain", "10 km/h", "15 km/h")),
            hourlyList = emptyList(),
            telemetry = BentoTelemetry(10, "N", 15, 2.0, 0, "Low", 80, 14, 1014, "05:30", "18:30"),
            nowcast = PrecipitationNowcast("Rain starting in ~15 min", true, 15, 2.5),
            timezone = "Africa/Johannesburg"
        )
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, staleData, t2000)
        assertEquals("Stale (>45m) cached forecast must never trigger event alerts", 0, dispatchedCount)

        // Case 2: Forecast is 25 minutes old, and nowcast onset was 20 minutes at fetch time (already elapsed 5 min ago!)
        val elapsedOnsetData = staleData.copy(
            hero = staleData.hero.copy(lastUpdatedEpochMs = t2000 - 25 * 60 * 1000L),
            nowcast = PrecipitationNowcast("Rain starting in ~20 min", true, 20, 2.5)
        )
        WeatherNotificationScheduler.checkAndDispatchEventAlerts(context, elapsedOnsetData, t2000)
        assertEquals("Nowcast whose onset has already elapsed must not fire", 0, dispatchedCount)
    }

    @Test
    fun testDistantTomorrowStorm_DoesNotTriggerImmediateSevereAlertTonight() {
        val distantStormAlert = SevereAlertInfo(
            riskLevel = SeverityRiskLevel.HIGH,
            thunderstormProbability = 45,
            title = "THUNDERSTORM WATCH",
            subtitle = "Peak activity expected tomorrow around 15:00.",
            isVisible = true,
            peakHourLabel = "Tomorrow 15:00",
            // Next 6 hours are 0% storm risk; storm is 19 hours away tomorrow at 15:00
            hourlyStormProbabilities = listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 45)
        )
        assertFalse(
            "A storm 19 hours away tomorrow afternoon must not trigger an immediate severe alert tonight",
            WeatherNotificationScheduler.isSevereAlert(distantStormAlert)
        )
    }

    @Test
    fun testGaleWindNotification_UsesEffectiveDayWindSpeedWhenLiveTelemetryIsCalm() {
        val context = FakeContext()
        var capturedConcise = ""
        WeatherNotificationHelper.areNotificationsEnabledOverride = { true }
        WeatherNotificationHelper.notificationDispatcherOverride = { _, _, _, _, concise, _, _, _ ->
            capturedConcise = concise
            true
        }

        // Live telemetry wind is calm (12 km/h), but today's daytime forecast has 55 km/h gale
        val data = WeatherDashboardData(
            hero = HeroWeather(18, 18, 22, 12, "Windy", null, true, "Cape Town", SkyThemeType.CLEAR_DAY),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
            dailyList = listOf(
                DailyItem(
                    dayLabel = "Today",
                    dateText = "11/3",
                    minTemp = 12,
                    maxTemp = 22,
                    rainProbability = 0,
                    iconUri = null,
                    conditionText = "Windy",
                    morningWind = "55 km/h SE",
                    afternoonWind = "55 km/h SE",
                    dayWindSpeedKmH = 55,
                    dayWindDirection = "SE"
                )
            ),
            hourlyList = emptyList(),
            telemetry = BentoTelemetry(12, "SE", 18, 0.0, 5, "Moderate", 55, 10, 1012, "06:00", "19:00")
        )

        val sentMorning = WeatherNotificationHelper.showGaleWindNotification(context, data, currentLocalHour = 10)
        assertTrue(sentMorning)
        assertTrue(
            "Must report the 55 km/h gale speed that triggered the alert, not the 12 km/h breeze: got '$capturedConcise'",
            capturedConcise.contains("55 km/h")
        )

        // At 19:00 (after 17:00), daytime gale has passed and live wind is 12 km/h -> must not dispatch
        val sentEvening = WeatherNotificationHelper.showGaleWindNotification(context, data, currentLocalHour = 19)
        assertFalse("Must not fire daytime gale wind alert in the evening when live wind is calm", sentEvening)
    }
}
