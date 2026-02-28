package fr.rtz.jsonrpc

import fr.rtz.jsonrpc.utils.InMemoryJsonRpcTransportSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class JsonRpcFailureTests {

    /**
     * Validates that receiving an invalid JSON payload results in a ParseError sent back to the peer.
     */
    @Test
    fun `Parse Error`() = runTest(UnconfinedTestDispatcher()) {
        // Given
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        val peerATransport = InMemoryJsonRpcTransportSession(inboxChannel = channel1, outboxChannel = channel2)
        val peerBTransport = InMemoryJsonRpcTransportSession(inboxChannel = channel2, outboxChannel = channel1)

        val peerA = JsonRpc.of(peerATransport)
        val peerB = JsonRpc.of(peerBTransport)

        // Collect on Dispatchers.Default so the deferred is completed on the same dispatcher
        // as the handlerScope emissions — avoids cross-dispatcher race on JS.
        val peerALocalError = CompletableDeferred<JsonRpcException>()
        backgroundScope.launch {
            peerA.localErrors.collect { peerALocalError.complete(it) }
        }
        val peerBRemoteError = CompletableDeferred<JsonRpcException>()
        backgroundScope.launch {
            peerB.remoteErrors.collect { peerBRemoteError.complete(it) }
        }

        // When
        channel1.send("Invalid Json Payload")

        // Then — await on the real event loop; no fixed delay needed
        assertIs<JsonRpcException.ParseError>(peerBRemoteError.await())
        assertIs<JsonRpcException.ParseError>(peerALocalError.await())
    }

    /**
     * Validates that receiving an invalid Request Object results in a InvalidRequest sent back to the peer.
     */
    @Test
    fun `Invalid Request`() = runTest(UnconfinedTestDispatcher()) {
        // Given
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        val peerATransport = InMemoryJsonRpcTransportSession(inboxChannel = channel1, outboxChannel = channel2)
        val peerBTransport = InMemoryJsonRpcTransportSession(inboxChannel = channel2, outboxChannel = channel1)

        val peerA = JsonRpc.of(peerATransport)
        val peerB = JsonRpc.of(peerBTransport)

        val peerALocalError = CompletableDeferred<JsonRpcException>()
        backgroundScope.launch {
            peerA.localErrors.collect { peerALocalError.complete(it) }
        }
        val peerBRemoteError = CompletableDeferred<JsonRpcException>()
        backgroundScope.launch {
            peerB.remoteErrors.collect { peerBRemoteError.complete(it) }
        }

        // When
        channel1.send("{\"foo\":1, \"bar\":2}")

        // Then — await on the real event loop; no fixed delay needed
        assertIs<JsonRpcException.InvalidRequest>(peerBRemoteError.await())
        assertIs<JsonRpcException.InvalidRequest>(peerALocalError.await())
    }

    /**
     * Validates that responding with 'method not found' results in a MethodNotFound error sent back to the peer.
     */
    @Test
    fun `Method Not Found`() = runTest(UnconfinedTestDispatcher()) {
        // Given
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        val peerATransport = InMemoryJsonRpcTransportSession(inboxChannel = channel1, outboxChannel = channel2)
        val peerBTransport = InMemoryJsonRpcTransportSession(inboxChannel = channel2, outboxChannel = channel1)

        val caughtExceptions = mutableListOf<Throwable>()
        val peerA = JsonRpc.of(peerATransport)
        val peerB = JsonRpc.of(peerBTransport)

        backgroundScope.launch {
            peerB.localErrors.collect { caughtExceptions.add(it) }
        }

        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    call.replyWithMethodNotFound()
                }
            }
        }
        // When
        val result = withContext(Dispatchers.Default) {
            val result = peerA.request(method = "abc", params = buildJsonObject { })
            // Wait some non-virtual time to accumulate the exception
            delay(100.milliseconds)
            result
        }

        // Then
        // Validate error on peerB
        val exception = caughtExceptions.firstOrNull()
        assertNotNull(exception)
        assertIs<JsonRpcException.MethodNotFound>(exception)
        // Validate error on peerA
        assertTrue(result.isFailure)
        assertIs<JsonRpcException.MethodNotFound>(result.exceptionOrNull())
    }

    /**
     * Validates that responding with 'invalid params' results in a InvalidParams error sent back to the peer.
     */
    @Test
    fun `Invalid Params Test`() = runTest(UnconfinedTestDispatcher()) {
        // Given
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        val peerATransport = InMemoryJsonRpcTransportSession(inboxChannel = channel1, outboxChannel = channel2)
        val peerBTransport = InMemoryJsonRpcTransportSession(inboxChannel = channel2, outboxChannel = channel1)

        val caughtExceptions = mutableListOf<Throwable>()
        val peerA = JsonRpc.of(peerATransport)
        val peerB = JsonRpc.of(peerBTransport)

        backgroundScope.launch {
            peerB.localErrors.collect { caughtExceptions.add(it) }
        }

        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    call.replyWithInvalidParams()
                }
            }
        }
        // When
        val result = withContext(Dispatchers.Default) {
            val result = peerA.request(method = "abc", params = buildJsonObject { })
            // Wait some non-virtual time to accumulate the exception
            delay(100.milliseconds)
            result
        }

        // Then
        // Validate error on peerB
        val exception = caughtExceptions.firstOrNull()
        assertNotNull(exception)
        assertIs<JsonRpcException.InvalidParams>(exception)
        // Validate error on peerA
        assertTrue(result.isFailure)
        assertIs<JsonRpcException.InvalidParams>(result.exceptionOrNull())
    }

    /**
     * Validates that responding with 'server error' results in a ServerError error sent back to the peer.
     */
    @Test
    fun `Server Error`() = runTest(UnconfinedTestDispatcher()) {
        // Given
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        val peerATransport = InMemoryJsonRpcTransportSession(inboxChannel = channel1, outboxChannel = channel2)
        val peerBTransport = InMemoryJsonRpcTransportSession(inboxChannel = channel2, outboxChannel = channel1)

        val caughtExceptions = mutableListOf<Throwable>()
        val peerA = JsonRpc.of(peerATransport)
        val peerB = JsonRpc.of(peerBTransport)

        backgroundScope.launch {
            peerB.localErrors.collect { caughtExceptions.add(it) }
        }

        backgroundScope.launch {
            for (call in peerB.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    call.replyWithServerError(-32000, "An error occurred")
                }
            }
        }
        // When
        val result = withContext(Dispatchers.Default) {
            val result = peerA.request(method = "abc", params = buildJsonObject { })
            // Wait some non-virtual time to accumulate the exception
            delay(100.milliseconds)
            result
        }

        // Then
        // Validate error on peerB
        val exception = caughtExceptions.firstOrNull()
        assertNotNull(exception)
        assertIs<JsonRpcException.ServerError>(exception)
        // Validate error on peerA
        assertTrue(result.isFailure)
        assertIs<JsonRpcException.ServerError>(result.exceptionOrNull())
    }

    /**
     * Validates that the message loop survives a parse error and continues processing subsequent messages.
     * A slow callsInbox consumer must not prevent response delivery (deadlock regression test).
     */
    @Test
    fun `Connection recovers after a parse error and continues processing messages`() = runTest(UnconfinedTestDispatcher()) {
        // Given
        val channel1 = Channel<String>()
        val channel2 = Channel<String>()
        val peerATransport = InMemoryJsonRpcTransportSession(inboxChannel = channel1, outboxChannel = channel2)
        val peerBTransport = InMemoryJsonRpcTransportSession(inboxChannel = channel2, outboxChannel = channel1)

        val peerA = JsonRpc.of(peerATransport)
        val peerB = JsonRpc.of(peerBTransport)

        backgroundScope.launch {
            for (call in peerA.callsInbox) {
                if (call is JsonRpcCall.ExpectsResponse) {
                    call.replyWithResult(call.params!!)
                }
            }
        }

        // When — inject a malformed message directly into peerA's inbox
        channel1.send("not valid json {{{")
        withContext(Dispatchers.Default) {
            // Give peerA time to process the bad message
            delay(100.milliseconds)
        }

        // Then — a subsequent valid request is still processed normally,
        // proving the message loop survived the parse error
        val result = withContext(Dispatchers.Default) {
            peerB.request("echo", JsonPrimitive("hello"), 2.seconds)
        }
        assertEquals(JsonPrimitive("hello"), result.getOrThrow())
    }
}