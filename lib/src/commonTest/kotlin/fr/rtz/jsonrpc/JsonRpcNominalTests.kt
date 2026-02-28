package fr.rtz.jsonrpc

import fr.rtz.jsonrpc.utils.InMemoryJsonRpcTransportSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class JsonRpcNominalTests {

    @Test
    fun `Simple JSON RPC Request test`() = runTest {
        // Given
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        val peerATransport = InMemoryJsonRpcTransportSession(inboxChannel = channel1, outboxChannel = channel2)
        val peerBTransport = InMemoryJsonRpcTransportSession(inboxChannel = channel2, outboxChannel = channel1)

        val peerA = JsonRpc.of(peerATransport)
        val peerB = JsonRpc.of(peerBTransport)

        // When
        val accumulator = mutableListOf<JsonRpcCall>()
        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                accumulator += call
                if (call is JsonRpcCall.ExpectsResponse) {
                    call.replyWithResult(JsonPrimitive("OK"))
                }
            }
        }
        val result = withContext(Dispatchers.Default) {
            peerA.request("hello", JsonPrimitive("world"), 2.seconds)
        }

        // Then
        val call = accumulator.firstOrNull()
        assertNotNull(call)
        assertEquals("hello", call.method)
        assertEquals(JsonPrimitive("world"), call.params)
        assertEquals(JsonPrimitive("OK"), result.getOrThrow())
    }

    @Test
    fun `Simple JSON RPC Notify test`() = runTest {
        // Given
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        val peerATransport = InMemoryJsonRpcTransportSession(inboxChannel = channel1, outboxChannel = channel2)
        val peerBTransport = InMemoryJsonRpcTransportSession(inboxChannel = channel2, outboxChannel = channel1)

        val peerA = JsonRpc.of(peerATransport)
        val peerB = JsonRpc.of(peerBTransport)

        // When
        val accumulator = mutableListOf<JsonRpcCall>()
        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                accumulator += call
            }
        }
        peerA.notify("hello", JsonPrimitive("world"))

        withContext(Dispatchers.Default) {
            // Wait some non-virtual time to accumulate the notification
            delay(100.milliseconds)
        }
        // Then
        val call = accumulator.firstOrNull()
        assertNotNull(call)
        assertEquals("hello", call.method)
        assertEquals(JsonPrimitive("world"), call.params)
    }

    @Test
    fun `Concurrent requests are resolved in completion order`() = runTest {
        // Given
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        val peerATransport = InMemoryJsonRpcTransportSession(inboxChannel = channel1, outboxChannel = channel2)
        val peerBTransport = InMemoryJsonRpcTransportSession(inboxChannel = channel2, outboxChannel = channel1)

        val peerA = JsonRpc.of(peerATransport)
        val peerB = JsonRpc.of(peerBTransport)

        // peerB handles "sleep" requests: delays for the given ms, then echoes the value back
        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    val ms = (call.params as JsonPrimitive).int
                    // Each call is handled concurrently so delays don't block each other
                    backgroundScope.launch {
                        withContext(Dispatchers.Default) { delay(ms.milliseconds) }
                        call.replyWithResult(call.params!!)
                    }
                }
            }
        }

        // When — issue 3 requests concurrently with 300, 200, 100 ms delays
        val responseOrder = mutableListOf<Int>()
        val jobs = listOf(300, 200, 100).map { ms ->
            backgroundScope.launch(Dispatchers.Default) {
                val result = peerA.request("sleep", JsonPrimitive(ms), 5.seconds)
                responseOrder += (result.getOrThrow() as JsonPrimitive).int
            }
        }
        withContext(Dispatchers.Default) { jobs.joinAll() }

        // Then — responses arrive in order of completion: fastest (100 ms) first
        assertEquals(listOf(100, 200, 300), responseOrder)
    }

    @Test
    fun `Simple JSON RPC back and forth test`() = runTest {
        // Given
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        val peerATransport = InMemoryJsonRpcTransportSession(inboxChannel = channel1, outboxChannel = channel2)
        val peerBTransport = InMemoryJsonRpcTransportSession(inboxChannel = channel2, outboxChannel = channel1)

        val peerA = JsonRpc.of(peerATransport)
        val peerB = JsonRpc.of(peerBTransport)

        // Each peer handles incoming requests by echoing the method name back
        backgroundScope.launch {
            for (call in peerA.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    call.replyWithResult(JsonPrimitive("reply from A: ${call.method}"))
                }
            }
        }
        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    call.replyWithResult(JsonPrimitive("reply from B: ${call.method}"))
                }
            }
        }

        // When — both peers send requests to each other concurrently
        val resultAtoB = withContext(Dispatchers.Default) {
            peerA.request("ping", JsonPrimitive("from A"), 2.seconds)
        }
        val resultBtoA = withContext(Dispatchers.Default) {
            peerB.request("pong", JsonPrimitive("from B"), 2.seconds)
        }

        // Then
        assertEquals(JsonPrimitive("reply from B: ping"), resultAtoB.getOrThrow())
        assertEquals(JsonPrimitive("reply from A: pong"), resultBtoA.getOrThrow())
    }

    /**
     * Validates that calling close() twice does not throw or hang.
     */
    @Test
    fun `close is idempotent`() = runTest {
        // Given
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        val peerA = JsonRpc.of(InMemoryJsonRpcTransportSession(inboxChannel = channel1, outboxChannel = channel2))

        // When / Then — second close() must not throw
        peerA.close()
        peerA.close()
    }

    /**
     * Validates that an incoming message with null params is surfaced correctly in callsInbox
     * with params == null.
     *
     * The params field is optional in the JSON-RPC 2.0 spec. [JsonNull] (serialized as
     * JSON `null`) is used here as a proxy: it round-trips as Kotlin null on the receiving side,
     * exercising the nullable params path without requiring raw channel injection.
     */
    @Test
    fun `Incoming notification with null params is received with null params`() = runTest {
        // Given
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        val peerATransport = InMemoryJsonRpcTransportSession(inboxChannel = channel1, outboxChannel = channel2)
        val peerBTransport = InMemoryJsonRpcTransportSession(inboxChannel = channel2, outboxChannel = channel1)

        val peerA = JsonRpc.of(peerATransport)
        val peerB = JsonRpc.of(peerBTransport)

        val accumulator = mutableListOf<JsonRpcCall>()
        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                accumulator += call
            }
        }

        // When — JsonNull is a valid JsonElement; it serializes to "params": null on the wire
        peerA.notify("no-params", JsonNull)
        withContext(Dispatchers.Default) { delay(100.milliseconds) }

        // Then
        val call = accumulator.firstOrNull()
        assertNotNull(call)
        assertEquals("no-params", call.method)
        assertEquals(null, call.params)
    }

    /**
     * Validates that a cascading (nested) request pattern works correctly:
     * peerA sends "chain" to peerB, peerB's handler makes a nested "callback" request back
     * to peerA before replying, and peerA handles "callback" in a concurrent handler.
     *
     * Each handler is dispatched with [launch] so the consumer loop stays free to receive
     * further calls. Without [launch], peerB's sequential consumer would block inside
     * peerB.request("callback"), and if a response to that request itself depended on the
     * consumer being free, a deadlock would result.
     */
    @Test
    fun `Cascading nested request resolves correctly`() = runTest {
        // Given
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        val peerATransport = InMemoryJsonRpcTransportSession(inboxChannel = channel1, outboxChannel = channel2)
        val peerBTransport = InMemoryJsonRpcTransportSession(inboxChannel = channel2, outboxChannel = channel1)

        val peerA = JsonRpc.of(peerATransport)
        val peerB = JsonRpc.of(peerBTransport)

        // Both consumers run on Dispatchers.Default so they are dispatched immediately by
        // Coroutine 1 (also on Default) without depending on the test-scheduler clock.

        // peerA handles the nested "callback" request that peerB sends back
        backgroundScope.launch(Dispatchers.Default) {
            for (call in peerA.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse && call.method == "callback") {
                    backgroundScope.launch(Dispatchers.Default) {
                        call.replyWithResult(JsonPrimitive("callback-ok"))
                    }
                }
            }
        }

        // peerB handles "chain": first calls back to peerA, then replies with the callback's result
        backgroundScope.launch(Dispatchers.Default) {
            for (call in peerB.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse && call.method == "chain") {
                    backgroundScope.launch(Dispatchers.Default) {
                        val callbackResult = peerB.request("callback", JsonPrimitive("ping"), 5.seconds)
                        call.replyWithResult(callbackResult.getOrThrow())
                    }
                }
            }
        }

        // When — peerA initiates the chain
        val result = withContext(Dispatchers.Default) {
            peerA.request("chain", JsonPrimitive("start"), 5.seconds)
        }

        // Then — the full cascading round-trip resolves with the nested callback's result
        assertEquals(JsonPrimitive("callback-ok"), result.getOrThrow())
    }

}