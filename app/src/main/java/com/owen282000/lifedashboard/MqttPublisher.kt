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

/**
 * Publishes the latest synced values to the user's MQTT broker with Home Assistant MQTT
 * Discovery, so sensors appear in Home Assistant automatically without any server-side setup.
 * Connect-publish-disconnect per sync; states and discovery configs are published retained so
 * Home Assistant keeps the last values across restarts. Failures never block the webhook sync;
 * the outcome is stored for display in the MQTT settings section.
 */
class MqttPublisher(private val context: Context) {

    suspend fun publishHealthData(healthData: HealthData): Result<Int> = withContext(Dispatchers.IO) {
        val preferencesManager = PreferencesManager(context)
        val settings = preferencesManager.getMqttSettings()
        if (!settings.enabled || settings.host.isBlank()) {
            return@withContext Result.success(0)
        }

        try {
            val sensors = MqttSupport.sensorsFrom(healthData)
            if (sensors.isEmpty()) return@withContext Result.success(0)

            val clientBuilder = MqttClient.builder()
                .useMqttVersion3()
                .identifier("lifedashboard-" + UUID.randomUUID().toString().take(8))
                .serverHost(settings.host)
                .serverPort(settings.port)
            if (settings.useTls) {
                clientBuilder.sslWithDefaultConfig()
            }
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
            preferencesManager.setLastMqttStatus("OK: ${sensors.size} sensors published at ${Instant.now()}")
            Result.success(sensors.size)
        } catch (e: Exception) {
            preferencesManager.setLastMqttStatus("Error: ${e.message ?: e.javaClass.simpleName}")
            Result.failure(e)
        }
    }
}
