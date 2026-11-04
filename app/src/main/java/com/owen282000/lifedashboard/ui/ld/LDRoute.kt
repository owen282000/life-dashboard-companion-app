package com.owen282000.lifedashboard.ui.ld

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.R
import kotlinx.serialization.Serializable

/** The two sync sections. A page both have takes it as a parameter. */
@Serializable
enum class LDSection(val logType: LogType) {
    Health(LogType.HEALTH_CONNECT),
    ScreenTime(LogType.SCREEN_TIME)
}

/** The tabs, in the order of the bar. */
@Serializable
enum class LDTab(@param:StringRes val label: Int, val accent: LDAccent) {
    Health(R.string.main_tab_health, LDAccent.Green),
    ScreenTime(R.string.main_title_screen_time, LDAccent.Purple),
    Logs(R.string.main_tab_logs, LDAccent.Green);

    val icon: ImageVector
        get() = when (this) {
            Health -> LDIcons.heart
            ScreenTime -> LDIcons.phone
            Logs -> LDIcons.logs
        }

    val root: LDRoute
        get() = when (this) {
            Health -> LDRoute.Home
            ScreenTime -> LDRoute.ScreenTimeHome
            Logs -> LDRoute.LogsList
        }
}

/** How What to send groups the 34 types. LDRouteTest holds that it covers every type once. */
@Serializable
enum class LDTypeCategory(@param:StringRes val title: Int, val types: List<HealthDataType>) {
    Activity(
        R.string.ld_category_activity,
        listOf(
            HealthDataType.STEPS, HealthDataType.DISTANCE, HealthDataType.SPEED, HealthDataType.ACTIVE_CALORIES,
            HealthDataType.TOTAL_CALORIES, HealthDataType.EXERCISE
        )
    ),
    Body(
        R.string.ld_category_body,
        listOf(
            HealthDataType.WEIGHT, HealthDataType.HEIGHT, HealthDataType.BODY_FAT, HealthDataType.LEAN_BODY_MASS,
            HealthDataType.BONE_MASS, HealthDataType.BODY_WATER_MASS, HealthDataType.BASAL_METABOLIC_RATE
        )
    ),
    Heart(
        R.string.ld_category_heart,
        listOf(
            HealthDataType.HEART_RATE, HealthDataType.RESTING_HEART_RATE, HealthDataType.HEART_RATE_VARIABILITY,
            HealthDataType.BLOOD_PRESSURE, HealthDataType.OXYGEN_SATURATION, HealthDataType.RESPIRATORY_RATE,
            HealthDataType.BODY_TEMPERATURE, HealthDataType.SKIN_TEMPERATURE, HealthDataType.BASAL_BODY_TEMPERATURE,
            HealthDataType.BLOOD_GLUCOSE, HealthDataType.VO2_MAX
        )
    ),
    SleepMind(R.string.ld_category_sleep, listOf(HealthDataType.SLEEP, HealthDataType.MINDFULNESS)),
    FoodDrink(R.string.ld_category_food, listOf(HealthDataType.HYDRATION, HealthDataType.NUTRITION)),
    Cycle(
        R.string.ld_category_cycle,
        listOf(
            HealthDataType.MENSTRUATION_PERIOD, HealthDataType.MENSTRUATION_FLOW, HealthDataType.INTERMENSTRUAL_BLEEDING,
            HealthDataType.OVULATION_TEST, HealthDataType.CERVICAL_MUCUS, HealthDataType.SEXUAL_ACTIVITY
        )
    )
}

/** Which part of a backfill its page shows. */
@Serializable
enum class LDBackfillStage { Choose, Running }

/**
 * Every page. Serializable, so the stack survives rotation and process death: keep what is in here
 * small and free of secrets. `LDRouteTable` maps each to its screen, once.
 */
@Serializable
sealed interface LDRoute {
    // The roots of the tabs
    @Serializable data object Home : LDRoute

    @Serializable data object ScreenTimeHome : LDRoute

    @Serializable data object LogsList : LDRoute

    // Destinations, on both tabs
    @Serializable data class AddDestination(val section: LDSection = LDSection.Health) : LDRoute

    /** [destinationId] is the destination to edit; null is a new one, starting at [address]. */
    @Serializable data class WebhookDestination(
        val section: LDSection = LDSection.Health,
        val destinationId: String? = null,
        val address: String? = null
    ) : LDRoute

    @Serializable data class MqttDestination(val section: LDSection = LDSection.Health) : LDRoute

    /** What the one ping after Pair found. Reads LocalLDPairing, so a link's secret stays out of the stack. */
    @Serializable data object PairedResult : LDRoute

    // What to send
    @Serializable data object WhatToSend : LDRoute

    @Serializable data class TypeCategory(val category: LDTypeCategory) : LDRoute

    @Serializable data object DataResolution : LDRoute

    @Serializable data class Backfill(val stage: LDBackfillStage = LDBackfillStage.Choose) : LDRoute

    @Serializable data class PayloadPreview(val section: LDSection = LDSection.Health) : LDRoute

    // When to send, Receive
    @Serializable data object WhenToSend : LDRoute

    @Serializable data object Receive : LDRoute

    // More
    @Serializable data object More : LDRoute

    @Serializable data class Advanced(val section: LDSection = LDSection.Health) : LDRoute

    @Serializable data object ServerCertificates : LDRoute

    @Serializable data object NotificationSettings : LDRoute

    @Serializable data class ExportData(val section: LDSection = LDSection.Health) : LDRoute

    @Serializable data object BackupRestore : LDRoute

    // Logs. WebhookLog.id, so a large payload does not travel in the stack.
    @Serializable data class LogDetail(val logId: String) : LDRoute

    // Screen Time
    @Serializable data object ScreenTimeApps : LDRoute

    @Serializable data object ScreenTimeDay : LDRoute

    @Serializable data object ScreenTimeWhen : LDRoute

    @Serializable data object ScreenTimeMore : LDRoute

    // About
    @Serializable data object AboutHome : LDRoute

    @Serializable data object AboutHelp : LDRoute

    @Serializable data object AboutSupport : LDRoute

    @Serializable data object AboutPrivacy : LDRoute

    @Serializable data object AboutOpenSource : LDRoute

    @Serializable data object AboutNerd : LDRoute
}

/** The tab a route belongs on when it is opened from outside the app's own navigation. */
val LDRoute.homeTab: LDTab
    get() = when (this) {
        LDRoute.ScreenTimeHome, LDRoute.ScreenTimeApps, LDRoute.ScreenTimeDay, LDRoute.ScreenTimeWhen, LDRoute.ScreenTimeMore -> LDTab.ScreenTime
        LDRoute.LogsList, is LDRoute.LogDetail -> LDTab.Logs
        is LDRoute.AddDestination -> section.tab
        is LDRoute.WebhookDestination -> section.tab
        is LDRoute.MqttDestination -> section.tab
        is LDRoute.PayloadPreview -> section.tab
        is LDRoute.Advanced -> section.tab
        is LDRoute.ExportData -> section.tab
        else -> LDTab.Health
    }

private val LDSection.tab: LDTab get() = if (this == LDSection.ScreenTime) LDTab.ScreenTime else LDTab.Health
