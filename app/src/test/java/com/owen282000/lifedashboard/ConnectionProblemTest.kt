package com.owen282000.lifedashboard

import com.hivemq.client.mqtt.exceptions.ConnectionClosedException
import com.hivemq.client.mqtt.exceptions.ConnectionFailedException
import com.hivemq.client.mqtt.exceptions.MqttClientStateException
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException

/**
 * What the user is told when the broker (or a webhook) cannot be reached: one of a few plain
 * sentences, whatever HiveMQ or OkHttp wrapped around the socket's exception.
 */
class ConnectionProblemTest {

    private val unresolved = UnknownHostException("Unable to resolve host \"no-such-host.invalid\": No address associated with hostname")

    /** Netty's connect timeout, which is a ConnectException; matched by name, since netty is not on the compile path. */
    private class ConnectTimeoutException(message: String) : ConnectException(message)

    private fun connAck(code: Mqtt3ConnAckReturnCode) = Mqtt3ConnAckException(
        object : Mqtt3ConnAck {
            override fun getReturnCode() = code
            override fun isSessionPresent() = false
        },
        "CONNECT failed as CONNACK contained an Error Code: ${code.name}.",
        null
    )

    @Test
    fun aHostThatDoesNotResolveIsNotFoundAlsoWhenHiveMqWrapsIt() {
        assertEquals(ConnectionProblem.HOST_NOT_FOUND, ConnectionProblem.of(unresolved))
        assertEquals(ConnectionProblem.HOST_NOT_FOUND, ConnectionProblem.of(ConnectionFailedException(unresolved)))
        assertEquals(ConnectionProblem.HOST_NOT_FOUND, ConnectionProblem.of(IOException("wrapped twice", ConnectionFailedException(unresolved))))
    }

    @Test
    fun aPortNobodyListensOnIsARefusal() {
        assertEquals(ConnectionProblem.REFUSED, ConnectionProblem.of(ConnectException("Connection refused")))
        assertEquals(
            ConnectionProblem.REFUSED,
            ConnectionProblem.of(ConnectionFailedException(ConnectException("Connection refused: broker.lan/192.168.1.10:1883")))
        )
    }

    @Test
    fun aNetworkThatCannotRouteThereIsUnreachableNotARefusal() {
        assertEquals(ConnectionProblem.UNREACHABLE, ConnectionProblem.of(NoRouteToHostException("No route to host")))
        assertEquals(ConnectionProblem.UNREACHABLE, ConnectionProblem.of(ConnectException("Network is unreachable: /10.0.0.5:1883")))
        // OkHttp's own ConnectException around the socket's: the deeper one says what happened.
        assertEquals(
            ConnectionProblem.UNREACHABLE,
            ConnectionProblem.of(ConnectException("Failed to connect to /10.0.0.5:8123").apply { initCause(SocketException("connect failed: ENETUNREACH (Network is unreachable)")) })
        )
        // A LAN broker that is switched off, as Android's NIO connect under Netty reports it.
        assertEquals(
            ConnectionProblem.UNREACHABLE,
            ConnectionProblem.of(
                ConnectionFailedException(ConnectException("failed to connect to /192.168.1.10 (port 1883) after 10000ms: isConnected failed: EHOSTUNREACH (No route to host)"))
            )
        )
    }

    @Test
    fun everyKindOfTimeoutIsNoAnswer() {
        assertEquals(ConnectionProblem.NO_ANSWER, ConnectionProblem.of(MqttTimeoutException(MqttTimeouts.PUBLISH_DEADLINE_MILLIS)))
        assertEquals(ConnectionProblem.NO_ANSWER, ConnectionProblem.of(SocketTimeoutException("timeout")))
        assertEquals(ConnectionProblem.NO_ANSWER, ConnectionProblem.of(InterruptedIOException("timeout")))
        assertEquals(ConnectionProblem.NO_ANSWER, ConnectionProblem.of(ConnectionFailedException(ConnectTimeoutException("connection timed out: broker.lan/192.168.1.10:1883"))))
        assertEquals(ConnectionProblem.NO_ANSWER, ConnectionProblem.of(ConnectException("failed to connect after 10000ms: connect failed: ETIMEDOUT")))
        assertEquals(ConnectionProblem.NO_ANSWER, ConnectionProblem.of(ConnectionClosedException("Timeout while waiting for CONNACK")))
    }

    @Test
    fun certificateAndHandshakeFailuresAreTls() {
        assertEquals(ConnectionProblem.TLS, ConnectionProblem.of(SSLHandshakeException("Connection closed while SSL/TLS handshake was in progress")))
        val untrusted = SSLHandshakeException("java.security.cert.CertPathValidatorException: Trust anchor for certification path not found.")
            .apply { initCause(CertificateException(CertPathValidatorException("Trust anchor for certification path not found."))) }
        assertEquals(ConnectionProblem.TLS, ConnectionProblem.of(ConnectionFailedException(untrusted)))
    }

    @Test
    fun aRejectedLoginIsToldApartFromOtherConnAckCodes() {
        assertEquals(ConnectionProblem.LOGIN_REFUSED, ConnectionProblem.of(connAck(Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD)))
        assertEquals(ConnectionProblem.LOGIN_REFUSED, ConnectionProblem.of(connAck(Mqtt3ConnAckReturnCode.NOT_AUTHORIZED)))
        assertEquals(ConnectionProblem.TURNED_DOWN, ConnectionProblem.of(connAck(Mqtt3ConnAckReturnCode.SERVER_UNAVAILABLE)))
        assertEquals(ConnectionProblem.TURNED_DOWN, ConnectionProblem.of(connAck(Mqtt3ConnAckReturnCode.IDENTIFIER_REJECTED)))
    }

    @Test
    fun aConnectionThatWentAwayIsLost() {
        assertEquals(ConnectionProblem.CONNECTION_LOST, ConnectionProblem.of(ConnectionClosedException("Server closed connection without DISCONNECT.")))
        assertEquals(ConnectionProblem.CONNECTION_LOST, ConnectionProblem.of(SocketException("Connection reset")))
        assertEquals(ConnectionProblem.CONNECTION_LOST, ConnectionProblem.of(EOFException()))
    }

    @Test
    fun anythingElseIsTheGenericSentence() {
        assertEquals(ConnectionProblem.OTHER, ConnectionProblem.of(IllegalArgumentException("Topic [a/#/b] must not contain multi level wildcard")))
        assertEquals(ConnectionProblem.OTHER, ConnectionProblem.of(MqttClientStateException("MQTT client is not connected.")))
        assertEquals(ConnectionProblem.OTHER, ConnectionProblem.of(IOException("HTTP 500: Internal Server Error")))
    }

    @Test
    fun aCauseThatPointsBackAtItselfEnds() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        assertEquals(ConnectionProblem.OTHER, ConnectionProblem.of(first))
    }

    @Test
    fun theDetailKeepsTheExceptionForTheLogsTab() {
        // HiveMQ already quotes its cause, so the cause is not repeated.
        assertEquals(
            "com.hivemq.client.mqtt.exceptions.ConnectionFailedException: java.net.UnknownHostException: " +
                "Unable to resolve host \"no-such-host.invalid\": No address associated with hostname",
            ConnectionProblem.detail(ConnectionFailedException(unresolved))
        )
        assertEquals(
            "java.io.IOException: wrapped, caused by java.net.ConnectException: Connection refused",
            ConnectionProblem.detail(IOException("wrapped", ConnectException("Connection refused")))
        )
    }

    @Test
    fun theWebhookPathKeepsWhatItDoesNotRecognise() {
        val status = IOException("HTTP 401: Unauthorized")
        assertSame(status, ConnectionProblem.plain(status) { "Webhook: ${it.name}" })
        val plain = ConnectionProblem.plain(unresolved) { "Webhook: ${it.name}" }
        assertEquals("Webhook: HOST_NOT_FOUND", plain.message)
        assertSame(unresolved, plain.cause)
    }
}
