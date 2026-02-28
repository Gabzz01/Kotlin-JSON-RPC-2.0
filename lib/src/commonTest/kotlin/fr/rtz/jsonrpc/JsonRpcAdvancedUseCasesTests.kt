package fr.rtz.jsonrpc

import fr.rtz.jsonrpc.utils.InMemoryJsonRpcTransportSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class JsonRpcAdvancedUseCasesTests {

    private fun makePeers(): Pair<JsonRpc, JsonRpc> {
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        return JsonRpc.of(InMemoryJsonRpcTransportSession(channel1, channel2)) to
                JsonRpc.of(InMemoryJsonRpcTransportSession(channel2, channel1))
    }

    /**
     * Validates that N concurrent outbound requests all arrive and are individually resolved.
     * Directly exercises the sendMutex: multiple coroutines call session.send() simultaneously.
     */
    @Test
    fun `Concurrent outbound requests are all resolved correctly`() = runTest {
        val N = 50
        val (peerA, peerB) = makePeers()

        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    val params = call.params
                    backgroundScope.launch { call.replyWithResult(params!!) }
                }
            }
        }

        // Each result goes into its own index — no synchronization needed
        val results = arrayOfNulls<Int>(N)
        val jobs = (0 until N).map { i ->
            backgroundScope.launch(Dispatchers.Default) {
                val result = peerA.request("echo", JsonPrimitive(i), 5.seconds)
                results[i] = (result.getOrThrow() as JsonPrimitive).int
            }
        }
        withContext(Dispatchers.Default) { jobs.joinAll() }

        for (i in 0 until N) {
            assertEquals(i, results[i], "Request $i received wrong result")
        }
    }

    /**
     * Validates that both peers can send concurrent requests to each other simultaneously,
     * exercising the sendMutex from both directions at once.
     */
    @Test
    fun `Bidirectional concurrent requests are all resolved correctly`() = runTest {
        val N = 30
        val (peerA, peerB) = makePeers()

        backgroundScope.launch {
            for (call in peerA.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    val params = call.params
                    backgroundScope.launch { call.replyWithResult(params!!) }
                }
            }
        }
        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    val params = call.params
                    backgroundScope.launch { call.replyWithResult(params!!) }
                }
            }
        }

        // peerA sends 0..(N-1), peerB sends N..(2N-1) so results are disjoint
        val aResults = arrayOfNulls<Int>(N)
        val bResults = arrayOfNulls<Int>(N)

        val aJobs = (0 until N).map { i ->
            backgroundScope.launch(Dispatchers.Default) {
                val result = peerA.request("echo", JsonPrimitive(i), 5.seconds)
                aResults[i] = (result.getOrThrow() as JsonPrimitive).int
            }
        }
        val bJobs = (0 until N).map { i ->
            backgroundScope.launch(Dispatchers.Default) {
                val result = peerB.request("echo", JsonPrimitive(i + N), 5.seconds)
                bResults[i] = (result.getOrThrow() as JsonPrimitive).int
            }
        }
        withContext(Dispatchers.Default) { (aJobs + bJobs).joinAll() }

        for (i in 0 until N) {
            assertEquals(i, aResults[i], "peerA request $i received wrong result")
            assertEquals(i + N, bResults[i], "peerB request $i received wrong result")
        }
    }

    /**
     * Validates that requests and notifications interleaved from multiple concurrent coroutines
     * all arrive correctly — exercises all three session.send() paths simultaneously.
     */
    @Test
    fun `Interleaved requests and notifications under concurrent load all arrive correctly`() = runTest {
        val N = 30
        val (peerA, peerB) = makePeers()

        val requestsHandled = arrayOfNulls<Int>(N)
        val notificationsReceived = arrayOfNulls<Int>(N)

        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                when (call) {
                    is JsonRpcCall.ExpectsResponse -> {
                        val value = (call.params as JsonPrimitive).int
                        requestsHandled[value] = value
                        call.replyWithResult(call.params!!)
                    }
                    is JsonRpcCall.Notify -> {
                        val value = (call.params as JsonPrimitive).int
                        notificationsReceived[value] = value
                    }
                }
            }
        }

        // Each job sends one notification AND one request concurrently
        val jobs = (0 until N).map { i ->
            backgroundScope.launch(Dispatchers.Default) {
                val notifJob = launch { peerA.notify("notif", JsonPrimitive(i)) }
                val result = peerA.request("req", JsonPrimitive(i), 5.seconds)
                notifJob.join()
                assertTrue(result.isSuccess, "Request $i failed: ${result.exceptionOrNull()}")
            }
        }
        withContext(Dispatchers.Default) { jobs.joinAll() }

        // Allow notifications to drain through the consumer
        withContext(Dispatchers.Default) { delay(100.milliseconds) }
        advanceUntilIdle()

        for (i in 0 until N) {
            assertEquals(i, requestsHandled[i], "Request $i was not handled")
            assertEquals(i, notificationsReceived[i], "Notification $i was not received")
        }
    }

    /**
     * Validates that a slow callsInbox consumer does not block response delivery.
     * Regression test for the deadlock the UNLIMITED incomingCallsBuffer prevents:
     * responses bypass callsInbox entirely (handled directly in Coroutine 2),
     * so backpressure on callsInbox must not stall the message-reading loop.
     */
    @Test
    fun `Slow callsInbox consumer does not deadlock response delivery`() = runTest {
        val (peerA, peerB) = makePeers()

        // peerA's consumer is intentionally slow
        backgroundScope.launch {
            for (call in peerA.callsInbox) {
                withContext(Dispatchers.Default) { delay(200.milliseconds) }
                if (call is JsonRpcCall.ExpectsResponse) {
                    call.replyWithResult(call.params!!)
                }
            }
        }
        // peerB echoes immediately
        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    call.replyWithResult(call.params!!)
                }
            }
        }

        // Flood peerA's callsInbox with notifications from peerB to trigger backpressure
        val floodJobs = (0 until 10).map {
            backgroundScope.launch(Dispatchers.Default) { peerB.notify("flood", JsonPrimitive(it)) }
        }
        withContext(Dispatchers.Default) {
            floodJobs.joinAll()
            delay(50.milliseconds) // let notifications start filling peerA's buffer
        }

        // Even with peerA's callsInbox heavily backpressured, peerA can still get a response
        // because responses go through handleResponse(), not callsInbox
        val result = withContext(Dispatchers.Default) {
            peerA.request("ping", JsonPrimitive("check"), 3.seconds)
        }
        assertEquals(JsonPrimitive("check"), result.getOrThrow())
    }

    /**
     * Validates that calling close() while requests are in-flight completes them
     * exceptionally with InternalError, and that close() itself does not hang.
     */
    @Test
    fun `Closing connection while requests are pending completes them with InternalError`() = runTest {
        val (peerA, peerB) = makePeers()

        // peerB never replies — keeps all requests pending indefinitely
        backgroundScope.launch {
            @Suppress("ControlFlowWithEmptyBody")
            for (call in peerB.callsInbox) { /* intentionally do nothing */ }
        }

        val results = arrayOfNulls<Result<*>>(5)
        val requestJobs = (0 until 5).map { i ->
            backgroundScope.launch(Dispatchers.Default) {
                results[i] = runCatching { peerA.request("pending", JsonPrimitive(i), 30.seconds).getOrThrow() }
            }
        }

        // Allow requests to reach peerB before closing
        withContext(Dispatchers.Default) { delay(100.milliseconds) }

        peerA.close()

        withContext(Dispatchers.Default) { requestJobs.joinAll() }

        for (i in 0 until 5) {
            val result = results[i]
            assertNotNull(result, "Result $i was never set")
            assertTrue(result.isFailure, "Request $i should have failed")
            assertTrue(
                result.exceptionOrNull() is JsonRpcException.InternalError,
                "Request $i expected InternalError, got ${result.exceptionOrNull()}"
            )
        }
    }

    /**
     * Validates that injecting malformed messages while requests are in-flight does not
     * prevent those requests from receiving their responses (message loop survives errors).
     */
    @Test
    fun `Parse errors injected mid-stream do not affect concurrent in-flight requests`() = runTest {
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        val peerA = JsonRpc.of(InMemoryJsonRpcTransportSession(channel1, channel2))
        val peerB = JsonRpc.of(InMemoryJsonRpcTransportSession(channel2, channel1))

        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    val params = call.params
                    backgroundScope.launch {
                        withContext(Dispatchers.Default) { delay(30.milliseconds) }
                        call.replyWithResult(params!!)
                    }
                }
            }
        }

        val N = 10
        val requestResults = arrayOfNulls<Boolean>(N)

        val requestJobs = (0 until N).map { i ->
            backgroundScope.launch(Dispatchers.Default) {
                val result = peerA.request("echo", JsonPrimitive(i), 5.seconds)
                requestResults[i] = result.isSuccess
            }
        }
        // Inject bad messages into peerA's inbox while requests are in-flight
        val badMessageJobs = (0 until 5).map { i ->
            backgroundScope.launch(Dispatchers.Default) {
                delay(10.milliseconds)
                channel1.send("not valid json {{{ $i")
            }
        }

        withContext(Dispatchers.Default) { (requestJobs + badMessageJobs).joinAll() }

        for (i in 0 until N) {
            assertTrue(requestResults[i] == true, "Request $i failed unexpectedly")
        }

        // The message loop is still alive — one more request succeeds after all the errors
        val postErrorResult = withContext(Dispatchers.Default) {
            peerA.request("echo", JsonPrimitive(999), 3.seconds)
        }
        assertEquals(JsonPrimitive(999), postErrorResult.getOrThrow())
    }

    /**
     * Validates that a timed-out request does not affect sibling concurrent requests,
     * which should still succeed normally.
     */
    @Test
    fun `Timed-out request does not affect sibling concurrent requests`() = runTest {
        val (peerA, peerB) = makePeers()

        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse && call.method == "echo") {
                    val params = call.params
                    backgroundScope.launch { call.replyWithResult(params!!) }
                }
                // "hang" method is intentionally never replied to
            }
        }

        var timeoutError: Throwable? = null
        val hangJob = backgroundScope.launch(Dispatchers.Default) {
            runCatching {
                peerA.request("hang", JsonPrimitive(0), 300.milliseconds).getOrThrow()
            }.onFailure { timeoutError = it }
        }

        val normalResults = arrayOfNulls<Int>(5)
        val normalJobs = (0 until 5).map { i ->
            backgroundScope.launch(Dispatchers.Default) {
                val result = peerA.request("echo", JsonPrimitive(i), 5.seconds)
                normalResults[i] = (result.getOrThrow() as JsonPrimitive).int
            }
        }

        withContext(Dispatchers.Default) { (listOf(hangJob) + normalJobs).joinAll() }

        assertNotNull(timeoutError)
        assertTrue(timeoutError is JsonRpcException.InternalError, "Expected InternalError for timeout")

        for (i in 0 until 5) {
            assertEquals(i, normalResults[i], "Normal request $i returned wrong result")
        }
    }

    /**
     * Validates that each concurrent request receives its own response with the correct payload,
     * detecting any ID-correlation bugs where responses could be routed to the wrong pending request.
     */
    @Test
    fun `Each concurrent request receives exactly its own response`() = runTest {
        val N = 100
        val (peerA, peerB) = makePeers()

        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    val params = call.params
                    backgroundScope.launch { call.replyWithResult(params!!) }
                }
            }
        }

        val receivedTags = arrayOfNulls<String>(N)
        val jobs = (0 until N).map { i ->
            backgroundScope.launch(Dispatchers.Default) {
                val result = peerA.request("echo", JsonPrimitive("req-$i"), 10.seconds)
                receivedTags[i] = (result.getOrThrow() as JsonPrimitive).content
            }
        }
        withContext(Dispatchers.Default) { jobs.joinAll() }

        for (i in 0 until N) {
            assertEquals("req-$i", receivedTags[i], "Request $i received the wrong response")
        }
    }

    /**
     * Validates that calling replyWithResult() twice for the same request silently suppresses
     * the duplicate and does not corrupt the transport for subsequent requests.
     */
    @Test
    fun `Duplicate reply is silently suppressed and transport remains usable`() = runTest {
        val (peerA, peerB) = makePeers()

        var requestCount = 0
        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    requestCount++
                    when (requestCount) {
                        1 -> {
                            // Reply twice — second call must be suppressed
                            call.replyWithResult(JsonPrimitive("first"))
                            call.replyWithResult(JsonPrimitive("second"))
                        }
                        else -> call.replyWithResult(JsonPrimitive("clean"))
                    }
                }
            }
        }

        val firstResult = withContext(Dispatchers.Default) {
            peerA.request("test", JsonPrimitive("payload"), 2.seconds)
        }
        assertEquals(JsonPrimitive("first"), firstResult.getOrThrow())

        // The duplicate reply must not have corrupted the transport
        val secondResult = withContext(Dispatchers.Default) {
            peerA.request("test2", JsonPrimitive("payload2"), 2.seconds)
        }
        assertEquals(JsonPrimitive("clean"), secondResult.getOrThrow())
    }

    /**
     * Validates that a high volume of concurrent notifications all arrive without loss,
     * and that a subsequent request still succeeds after the flood.
     */
    @Test
    fun `Notification flood does not corrupt transport or prevent subsequent requests`() = runTest {
        val N = 200
        val (peerA, peerB) = makePeers()

        val notificationsReceived = arrayOfNulls<Int>(N)
        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                when (call) {
                    is JsonRpcCall.Notify -> {
                        val value = (call.params as JsonPrimitive).int
                        notificationsReceived[value] = value
                    }
                    is JsonRpcCall.ExpectsResponse -> call.replyWithResult(JsonPrimitive("ok"))
                }
            }
        }

        val floodJobs = (0 until N).map { i ->
            backgroundScope.launch(Dispatchers.Default) { peerA.notify("flood", JsonPrimitive(i)) }
        }
        withContext(Dispatchers.Default) {
            floodJobs.joinAll()
            delay(200.milliseconds) // allow consumer to drain
        }
        advanceUntilIdle()

        for (i in 0 until N) {
            assertEquals(i, notificationsReceived[i], "Notification $i was not received")
        }

        // Transport must still be usable after the flood
        val result = withContext(Dispatchers.Default) {
            peerA.request("after-flood", JsonPrimitive("check"), 2.seconds)
        }
        assertEquals(JsonPrimitive("ok"), result.getOrThrow())
    }

    /**
     * Validates that a large JSON payload round-trips correctly through the full
     * serialization + transport + deserialization pipeline.
     */
    @Test
    fun `Large JSON payload round-trips without truncation or corruption`() = runTest {
        val (peerA, peerB) = makePeers()

        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    call.replyWithResult(call.params!!)
                }
            }
        }

        val largePayload = buildJsonObject {
            repeat(1_000) { i ->
                put("key-$i", "value-$i-" + "x".repeat(100))
            }
        }

        val result = withContext(Dispatchers.Default) {
            peerA.request("large", largePayload, 5.seconds)
        }
        assertEquals(largePayload, result.getOrThrow())
    }
}
