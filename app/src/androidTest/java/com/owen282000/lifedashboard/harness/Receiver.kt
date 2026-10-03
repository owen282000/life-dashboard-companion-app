package com.owen282000.lifedashboard.harness

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.rules.ExternalResource
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The webhook receiver: a MockWebServer in the test process on 127.0.0.1. The instrumentation
 * runs in the app's process, so the app posts to it through its own network security config
 * (cleartext allowed at platform level, the app's own opt-in set by [TestSetup]).
 *
 * Answers per path: [route] sets a handler for one path, anything else gets a plain 200.
 * Every request is kept with the time it arrived and what it was answered, so tests assert on
 * what really went over the wire. A dynamic port, so a socket left behind by an earlier run
 * never blocks the next one.
 */
class Receiver : ExternalResource() {

    class Exchange(val request: RecordedRequest, val receivedAtMs: Long, val responseCode: Int) {
        val path: String get() = request.url.encodedPath
        val body: ByteArray get() = request.body?.toByteArray() ?: ByteArray(0)
        val text: String get() = body.toString(Charsets.UTF_8)
        fun header(name: String): String? = request.headers[name]
    }

    private val server = MockWebServer()
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val log = CopyOnWriteArrayList<Exchange>()

    val exchanges: List<Exchange> get() = log.toList()

    fun url(path: String): String = "http://127.0.0.1:${server.port}$path"

    fun route(path: String, handler: (RecordedRequest) -> MockResponse) {
        routes[path] = handler
    }

    /** Answers [path] with [codes] in turn, and with the last one after that. */
    fun respond(path: String, vararg codes: Int) {
        val queue = java.util.concurrent.atomic.AtomicInteger()
        route(path) { MockResponse(code = codes[minOf(queue.getAndIncrement(), codes.size - 1)]) }
    }

    /**
     * Takes requests on [path] and never answers them: the headers are read, the response
     * never starts, so the app waits until its own read timeout.
     */
    fun stall(path: String) = route(path) { MockResponse.Builder().onResponseStart(SocketEffect.Stall).build() }

    /** Answers the first [answered] requests on [path] with 200, and stalls every one after them, see [stall]. */
    fun answerThenStall(path: String, answered: Int) {
        val seen = java.util.concurrent.atomic.AtomicInteger()
        route(path) {
            if (seen.getAndIncrement() < answered) MockResponse(code = 200)
            else MockResponse.Builder().onResponseStart(SocketEffect.Stall).build()
        }
    }

    /** Waits until at least [count] requests have arrived. */
    fun awaitRequests(count: Int, timeoutMs: Long = 20_000) {
        Await.until("$count request(s) at the receiver", timeoutMs) { log.size >= count }
    }

    fun to(path: String): List<Exchange> = exchanges.filter { it.path == path }

    /** Everything received since [mark], a count taken earlier from [exchanges]. */
    fun since(mark: Int): List<Exchange> = exchanges.drop(mark)

    override fun before() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val handler = routes[request.url.encodedPath]
                val response = handler?.invoke(request) ?: MockResponse(code = 200)
                log += Exchange(request, System.currentTimeMillis(), response.code)
                return response
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    override fun after() {
        server.close()
    }
}
