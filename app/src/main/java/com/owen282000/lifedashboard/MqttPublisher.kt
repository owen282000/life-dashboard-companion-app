package com.owen282000.lifedashboard

import android.content.Context
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

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
 * Publishes the latest synced values to the user's MQTT broker with Home Assistant MQTT
 * Discovery, so sensors appear in Home Assistant automatically without any server-side setup.
 * Connect-publish-disconnect per sync; states and discovery configs are published retained so
 * Home Assistant keeps the last values across restarts. Failures never block the webhook sync;
 * the outcome is stored for display in the MQTT settings section. Health Connect and screen
 * time share the broker settings and the Home Assistant device (issue #52).
 */
class MqttPublisher(private val context: Context) {

    suspend fun publishHealthData(healthData: HealthData): Result<Int> =
        publish(MqttSupport.sensorsFrom(healthData), MqttSection.HEALTH)

    suspend fun publishScreenTime(days: List<ScreenTimeData>): Result<Int> =
        publish(MqttSupport.sensorsFromScreenTime(days), MqttSection.SCREEN_TIME)

    private suspend fun publish(sensors: List<MqttSensor>, section: MqttSection): Result<Int> {
        val preferencesManager = PreferencesManager(context)
        return publish(sensors, preferencesManager.resolvedMqttSettings(section)) {
            preferencesManager.setLastMqttStatus(section, it)
        }
    }

    private suspend fun publish(
        sensors: List<MqttSensor>,
        settings: MqttSettings,
        setStatus: (String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (!settings.enabled || settings.host.isBlank()) {
            return@withContext Result.success(0)
        }
        if (sensors.isEmpty()) return@withContext Result.success(0)

        try {
            val clientBuilder = MqttClient.builder()
                .useMqttVersion3()
                .identifier("lifedashboard-" + UUID.randomUUID().toString().take(8))
                .serverHost(settings.host)
                .serverPort(settings.port)
                .let { if (settings.useTls) it.sslWithDefaultConfig() else it }
            val client = clientBuilder.buildBlocking()

            val connect = client.connectWith().cleanSession(true)
            if (!settings.username.isNullOrBlank()) {
                connect.simpleAuth()
                    .username(settings.username)
                    .password((settings.password ?: "").toByteArray(Charsets.UTF_8))
                    .applySimpleAuth()
            }
            connect.send()

            try {
                val appVersion = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
                } catch (e: Exception) {
                    "unknown"
                }
                for (sensor in sensors) {
                    client.publishWith()
                        .topic(MqttSupport.discoveryTopic(MqttSupport.DEFAULT_DISCOVERY_PREFIX, sensor.key))
                        .payload(MqttSupport.discoveryConfigJson(sensor, settings.baseTopic, appVersion).toByteArray(Charsets.UTF_8))
                        .qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
                    client.publishWith()
                        .topic(MqttSupport.stateTopic(settings.baseTopic, sensor.key))
                        .payload(sensor.state.toByteArray(Charsets.UTF_8))
                        .qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
                    client.publishWith()
                        .topic(MqttSupport.attributesTopic(settings.baseTopic, sensor.key))
                        .payload(MqttSupport.attributesJson(sensor).toByteArray(Charsets.UTF_8))
                        .qos(MqttQos.AT_LEAST_ONCE).retain(true).send()
                }
            } finally {
                client.disconnect()
            }
            setStatus("OK: ${sensors.size} sensors published at ${Instant.now()}")
            Result.success(sensors.size)
        } catch (e: Exception) {
            setStatus("Error: ${e.message ?: e.javaClass.simpleName}")
            Result.failure(e)
        }
    }
}
