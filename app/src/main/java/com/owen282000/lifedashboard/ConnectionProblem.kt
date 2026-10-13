package com.owen282000.lifedashboard

import androidx.annotation.StringRes
import com.hivemq.client.mqtt.exceptions.ConnectionClosedException
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException

/**
 * Why a connection to the broker (or a webhook) failed, in the few kinds a user can act on.
 * Each has a sentence in the user's language that says what failed and what to check; the
 * exception itself, class names and all, goes to the Logs tab only (see [detail]).
 */
enum class ConnectionProblem(@StringRes val messageRes: Int) {
    HOST_NOT_FOUND(R.string.connection_problem_host_not_found),
    UNREACHABLE(R.string.connection_problem_unreachable),
    REFUSED(R.string.connection_problem_refused),
    NO_ANSWER(R.string.connection_problem_no_answer),
    TLS(R.string.connection_problem_tls),
    LOGIN_REFUSED(R.string.connection_problem_login_refused),
    TURNED_DOWN(R.string.connection_problem_turned_down),
    CONNECTION_LOST(R.string.connection_problem_lost),
    OTHER(R.string.connection_problem_other);

    companion object {
        /** More than any real chain is long; it only stops a cause that points back at itself. */
        private const val MAX_CHAIN = 10

        /**
         * The kind of [error]. HiveMQ and OkHttp wrap what the socket threw, often more than
         * once, so the whole cause chain is read and the deepest link that says something wins:
         * a ConnectionFailedException around an UnknownHostException is a host that was not
         * found, a ConnectException around "Network is unreachable" an unreachable host.
         */
        fun of(error: Throwable): ConnectionProblem =
            chain(error).mapNotNull(::kindOf).lastOrNull() ?: OTHER

        /**
         * [error] as the sync line should show it: a connection problem in plain words from
         * [describe], with [error] kept as the cause; anything this does not recognise, such
         * as an HTTP status, stays as it was, since its own message already says what failed.
         */
        fun plain(error: Throwable, describe: (ConnectionProblem) -> String): Throwable =
            of(error).takeUnless { it == OTHER }?.let { Exception(describe(it), error) } ?: error

        /**
         * The exception as a bug report wants it, for the Logs tab: the outer exception with its
         * class, and the root cause when the outer one does not already quote it.
         */
        fun detail(error: Throwable): String {
            val outer = error.toString()
            val root = chain(error).last().toString()
            return if (root in outer) outer else "$outer, caused by $root"
        }

        private fun chain(error: Throwable): List<Throwable> {
            val links = mutableListOf<Throwable>()
            var link: Throwable? = error
            while (link != null && links.size < MAX_CHAIN && links.none { it === link }) {
                links += link
                link = link.cause
            }
            return links
        }

        /** ENETUNREACH and EHOSTUNREACH, in the words Android and Netty put them. */
        private fun isUnreachable(message: String): Boolean =
            "unreach" in message || "no route to host" in message

        /** What one link of the chain says on its own, or null when it says nothing a user can act on. */
        private fun kindOf(e: Throwable): ConnectionProblem? {
            val message = e.message.orEmpty().lowercase()
            return when (e) {
                is MqttTimeoutException -> NO_ANSWER
                is Mqtt3ConnAckException -> when (e.mqttMessage.returnCode) {
                    Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD, Mqtt3ConnAckReturnCode.NOT_AUTHORIZED -> LOGIN_REFUSED
                    else -> TURNED_DOWN
                }
                is UnknownHostException -> HOST_NOT_FOUND
                // SSLHandshakeException and its kin, also a handshake that timed out or a broker
                // that hung up mid-handshake (often TLS switched on for a plain port), and the
                // certificate checks below them.
                is SSLException, is GeneralSecurityException -> TLS
                // SocketTimeoutException is one, and so is OkHttp's call timeout.
                is InterruptedIOException -> NO_ANSWER
                is NoRouteToHostException -> UNREACHABLE
                // Netty's ConnectTimeoutException is a ConnectException, and so is a connect the
                // network could not route; only what is left is a port nobody listens on.
                // Android's NIO connect (Netty, so HiveMQ) reports a LAN host that is switched
                // off as a ConnectException with "EHOSTUNREACH (No route to host)" rather than as
                // a NoRouteToHostException, which is no refusal either.
                is ConnectException -> when {
                    e.javaClass.simpleName == "ConnectTimeoutException" || "timed out" in message || "etimedout" in message -> NO_ANSWER
                    isUnreachable(message) -> UNREACHABLE
                    else -> REFUSED
                }
                is SocketException -> if (isUnreachable(message)) UNREACHABLE else CONNECTION_LOST
                is EOFException -> CONNECTION_LOST
                // HiveMQ ends a connect that never got its CONNACK, and a connection whose pings
                // went unanswered, with "Timeout while waiting for ...".
                is ConnectionClosedException -> if (message.startsWith("timeout")) NO_ANSWER else CONNECTION_LOST
                else -> null
            }
        }
    }
}
