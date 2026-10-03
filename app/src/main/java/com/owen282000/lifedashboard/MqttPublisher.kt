package com.owen282000.lifedashboard

import android.content.Context
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** MQTT broker configuration; username and password are stored encrypted. */
data class MqttSettings(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val username: String?,
    val password: String?,
    val baseTopic: String
)

/** Connection details of one broker; username and password are stored encrypted. */
data class MqttBroker(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val username: String?,
    val password: String?
)

/**
 * MQTT settings of one section (issue #52). Each section has its own switch and base topic and
 * uses the shared broker connection by default; switching [useSharedBroker] off makes it
 * connect to [ownBroker] instead. Either section can be set up first, the other joins later.
 */
data class MqttSectionSettings(
    val enabled: Boolean,
    val useSharedBroker: Boolean,
    val ownBroker: MqttBroker,
    val baseTopic: String
)

/** The two publishers, with their preference keys. HEALTH keeps the original key names. */
enum class MqttSection(val prefix: String, val enabledKey: String, val baseTopicKey: String, val statusKey: String) {
    HEALTH("health_mqtt_", "mqtt_enabled", "mqtt_base_topic", "mqtt_last_status"),
    SCREEN_TIME("screentime_mqtt_", "screentime_mqtt_enabled", "screentime_mqtt_base_topic", "screentime_mqtt_last_status")
}

/**
 * How long the broker gets. The socket and the MQTT handshake each have their own limit, every
 * acknowledgement after that has [STEP_MILLIS], and the whole exchange [PUBLISH_DEADLINE_MILLIS],
 * which leaves room for a few hundred messages over a slow mobile link but not for a broker that
 * has stopped answering.
 */
object MqttTimeouts {
    const val SOCKET_CONNECT_MILLIS = 10_000L
    const val MQTT_CONNECT_MILLIS = 10_000L

    /** The connect as a whole; HiveMQ's own two limits above should end it first. */
    const val CONNECT_DEADLINE_MILLIS = SOCKET_CONNECT_MILLIS + MQTT_CONNECT_MILLIS + 5_000L
    const val STEP_MILLIS = 10_000L
    const val PUBLISH_DEADLINE_MILLIS = 120_000L
}

/** The broker did not answer in time; the message names the limit, for the MQTT status line. */
class MqttTimeoutException(val millis: Long) : IOException("No answer within ${millis / 1000} s")

/**
 * Awaits a HiveMQ future for at most [millis]. Cancelling the caller cancels the wait and the
 * future with it; running out of time throws [MqttTimeoutException], a plain failure.
 */
internal suspend fun CompletableFuture<*>.within(millis: Long) {
    // The Unit, not the future's value: a disconnect completes with null.
    withTimeoutOrNull(millis) { await(); Unit } ?: throw MqttTimeoutException(millis)
}

/**
 * Publishes the latest synced values to the user's MQTT broker with Home Assistant MQTT
 * Discovery, so sensors appear in Home Assistant automatically without any server-side setup.
 * Connect-publish-disconnect per sync; states and discovery configs are published retained so
 * Home Assistant keeps the last values across restarts. Failures never block the webhook sync;
 * the outcome is stored for display in the MQTT settings section. Without a webhook the broker
 * is the only destination, and its failure is the sync's (see [MqttSupport.syncFailure]).
 * Health Connect and screen time share the broker settings and the Home Assistant device
 * (issue #52).
 */
class MqttPublisher(private val context: Context) {

    companion object {
        /** The client for one publish, with the connect limits of [MqttTimeouts]. Builds, never connects. */
        internal fun clientFor(settings: MqttSettings): Mqtt3AsyncClient =
            MqttClient.builder()
                .useMqttVersion3()
                .identifier("lifedashboard-" + UUID.randomUUID().toString().take(8))
                .transportConfig()
                .serverHost(settings.host)
                .serverPort(settings.port)
                .let { if (settings.useTls) it.sslWithDefaultConfig() else it }
                .socketConnectTimeout(MqttTimeouts.SOCKET_CONNECT_MILLIS, TimeUnit.MILLISECONDS)
                .mqttConnectTimeout(MqttTimeouts.MQTT_CONNECT_MILLIS, TimeUnit.MILLISECONDS)
                .applyTransportConfig()
                .buildAsync()
    }

    suspend fun publishHealthData(healthData: HealthData, dailyTotals: List<DailyTotals> = emptyList()): Result<Int> =
        publish(MqttSupport.sensorsFrom(healthData, dailyTotals), MqttSection.HEALTH)

    suspend fun publishScreenTime(days: List<ScreenTimeData>): Result<Int> =
        publish(MqttSupport.sensorsFromScreenTime(days), MqttSection.SCREEN_TIME)

    private suspend fun publish(fresh: List<MqttSensor>, section: MqttSection): Result<Int> {
        val preferencesManager = context.appPreferences()
        val settings = preferencesManager.resolvedMqttSettings(section)
        // Publish everything the app has ever mapped for this section, not only the types that
        // had new records this run, so a new broker or Home Assistant gets the whole device.
        val cached = preferencesManager.getMqttSensorCache(section)
            ?.let { cache ->
                // Not runCatching: that would also catch a cancellation, were one ever to reach here.
                try {
                    Json.decodeFromString<List<MqttSensor>>(cache)
                } catch (e: IllegalArgumentException) { // SerializationException is one
                    null
                }
            }
            ?: emptyList()
        val sensors = MqttSupport.mergeSensors(cached, fresh)
        if (sensors.isNotEmpty()) preferencesManager.setMqttSensorCache(section, Json.encodeToString(sensors))
        val phoneName = preferencesManager.getPhoneName()
        val currentSlug = MqttSupport.phoneSlug(phoneName)
        // A renamed phone would leave its old device on the broker with frozen values (the
        // topics are retained), so the sensors under the previous slug are cleared on the
        // first publish after the rename, and the slug is recorded once that publish succeeded.
        val clearFirst = MqttSupport.topicsToClearOnRename(
            settings.baseTopic,
            MqttSupport.DEFAULT_DISCOVERY_PREFIX,
            sensors.map { it.key } + MqttSupport.RETIRED_SENSOR_KEYS,
            previousSlug = preferencesManager.getMqttPublishedSlug(section),
            currentSlug = currentSlug
        )
        val result = publish(sensors, settings, phoneName, clearFirst) { preferencesManager.setLastMqttStatus(section, it) }
        if (result.isSuccess && (result.getOrNull() ?: 0) > 0) preferencesManager.setMqttPublishedSlug(section, currentSlug)
        // The Logs tab lists MQTT publishes next to webhook deliveries, so a failing broker
        // shows up in the same place as a failing endpoint.
        if (settings.enabled && settings.host.isNotBlank() && sensors.isNotEmpty()) {
            preferencesManager.addWebhookLog(
                WebhookLog(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    url = "mqtt://${settings.host}:${settings.port}/${settings.baseTopic}",
                    statusCode = null,
                    success = result.isSuccess,
                    errorMessage = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
                    dataType = "mqtt",
                    recordCount = sensors.size,
                    rawPayload = null,
                    logType = if (section == MqttSection.HEALTH) LogType.HEALTH_CONNECT.name else LogType.SCREEN_TIME.name,
                    destination = LogDestination.MQTT.name
                )
            )
        }
        return result
    }

    private suspend fun publish(
        sensors: List<MqttSensor>,
        settings: MqttSettings,
        /** The phone's name, which puts its slug in every topic and id; null keeps the topics as they were. */
        phoneName: String?,
        /** Retained topics to empty before publishing: the old device after a rename. */
        clearFirst: List<String>,
        setStatus: (String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!settings.enabled || settings.host.isBlank()) {
            return@withContext Result.success(0)
        }
        if (sensors.isEmpty()) return@withContext Result.success(0)
        val slug = MqttSupport.phoneSlug(phoneName)

        try {
            val appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                "unknown"
            }
            // Retire sensors that older versions published under other keys, so Home
            // Assistant does not keep a stale "Steps (latest record)" next to "Steps Today",
            // and take the previous device off the broker after a rename. See
            // MqttSupport.topicsFor for the order of the three topics.
            val retired = MqttSupport.topicsFor(settings.baseTopic, MqttSupport.DEFAULT_DISCOVERY_PREFIX, MqttSupport.RETIRED_SENSOR_KEYS, slug)
            val client = clientFor(settings)
            var connecting: CompletableFuture<*>? = null
            var disconnected = false
            try {
                // One deadline over the whole exchange and one per acknowledgement: a broker
                // that stops answering is noticed within a step, and a slow one cannot hold the
                // worker past the deadline. The blocking client had neither, so a broker that
                // took the connection and never answered held the sync, and every sync after it.
                // Each message still waits for its acknowledgement before the next one goes,
                // which keeps the order MqttSupport.topicsFor relies on.
                withTimeoutOrNull(MqttTimeouts.PUBLISH_DEADLINE_MILLIS) {
                    val connect = client.connectWith().cleanSession(true)
                    if (!settings.username.isNullOrBlank()) {
                        connect.simpleAuth()
                            .username(settings.username)
                            .password((settings.password ?: "").toByteArray(Charsets.UTF_8))
                            .applySimpleAuth()
                    }
                    connect.send().also { connecting = it }.within(MqttTimeouts.CONNECT_DEADLINE_MILLIS)

                    for (topic in clearFirst + retired) {
                        client.publishWith().topic(topic).payload(ByteArray(0)).qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
                            .within(MqttTimeouts.STEP_MILLIS)
                    }
                    for (sensor in sensors) {
                        client.publishWith()
                            .topic(MqttSupport.discoveryTopic(MqttSupport.DEFAULT_DISCOVERY_PREFIX, sensor.key, slug))
                            .payload(MqttSupport.discoveryConfigJson(sensor, settings.baseTopic, appVersion, phoneName).toByteArray(Charsets.UTF_8))
                            .qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
                            .within(MqttTimeouts.STEP_MILLIS)
                        client.publishWith()
                            .topic(MqttSupport.stateTopic(settings.baseTopic, sensor.key, slug))
                            .payload(sensor.state.toByteArray(Charsets.UTF_8))
                            .qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
                            .within(MqttTimeouts.STEP_MILLIS)
                        client.publishWith()
                            .topic(MqttSupport.attributesTopic(settings.baseTopic, sensor.key, slug))
                            .payload(MqttSupport.attributesJson(sensor).toByteArray(Charsets.UTF_8))
                            .qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
                            .within(MqttTimeouts.STEP_MILLIS)
                    }
                    client.disconnect().within(MqttTimeouts.STEP_MILLIS)
                    disconnected = true
                } ?: throw MqttTimeoutException(MqttTimeouts.PUBLISH_DEADLINE_MILLIS)
            } finally {
                // A timeout, a failure or a cancelled sync arrives here still connected, or still
                // connecting. Not waited for: a stopped worker must not wait on the broker it
                // gave up on. A connect that completes after all is closed the moment it does.
                if (!disconnected) connecting?.whenComplete { _, error -> if (error == null) client.disconnect() }
            }
            setStatus("OK: ${sensors.size} sensors published at ${Instant.now()}")
            Result.success(sensors.size)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            setStatus("Error: ${e.message ?: e.javaClass.simpleName}")
            Result.failure(e)
        }
    }
}
