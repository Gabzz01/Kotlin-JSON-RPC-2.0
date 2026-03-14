package fr.rtz.jsonrpc

import fr.rtz.jsonrpc.impl.JsonRpcImpl
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A [JSON-RPC 2.0](https://www.jsonrpc.org/specification) session between two peers.
 *
 * Create an instance with [JsonRpc.of], providing a [JsonRpcTransportSession] that handles the
 * raw byte/string transport (WebSocket, TCP, stdio, SSE, etc.):
 *
 * ```kotlin
 * val jsonRpc = JsonRpc.of(myTransportSession)
 * ```
 *
 * ## Sending messages
 *
 * - [request] — sends a call and suspends until the remote peer replies or the timeout elapses.
 * - [notify] — sends a fire-and-forget message; the remote peer MUST NOT reply.
 *
 * Both functions are safe to call concurrently from multiple coroutines.
 *
 * ## Receiving messages
 *
 * Consume [callsInbox] to handle incoming requests and notifications from the remote peer.
 * See [callsInbox] for the recommended consumption patterns.
 *
 * ## Error observability
 *
 * - [localErrors] — protocol errors detected locally (e.g. malformed incoming JSON).
 * - [remoteErrors] — error responses received from the remote peer that could not be correlated
 *   to a pending [request] (e.g. the remote could not parse the request ID).
 *
 * ## Lifecycle
 *
 * Call [close] to shut down the session. It cancels internal coroutines, drains pending requests
 * with an error, closes [callsInbox], and delegates to [JsonRpcTransportSession.close].
 */
interface JsonRpc {

    companion object {

        /**
         * Factory method which builds a [JsonRpc] session on top of a [JsonRpcTransportSession].
         */
        fun of(session: JsonRpcTransportSession): JsonRpc {
            return JsonRpcImpl(session)
        }
    }

    /**
     * An unlimited channel that delivers incoming [JsonRpcCall]s from the remote peer.
     *
     * The channel remains open for the lifetime of the session and is closed automatically when
     * [close] is called or the underlying transport terminates.
     *
     * Each element is either a [JsonRpcCall.Notify] (no reply needed) or a
     * [JsonRpcCall.ExpectsResponse] (a reply is required). Failing to reply to a
     * [JsonRpcCall.ExpectsResponse] will leave the remote caller suspended until its timeout elapses.
     *
     * ## Sequential handler
     *
     * Use this when each handler completes quickly and does not call [request] back on the same session.
     *
     * ```kotlin
     * scope.launch {
     *     for (call in jsonRpc.callsInbox) {
     *         when (call) {
     *             is JsonRpcCall.ExpectsResponse -> when (call.method) {
     *                 "greet" -> call.replyWithResult(JsonPrimitive("Hello!"))
     *                 else    -> call.replyWithMethodNotFound()
     *             }
     *             is JsonRpcCall.Notify -> { /* fire-and-forget, no reply needed */ }
     *         }
     *     }
     * }
     * ```
     *
     * ## Concurrent handler
     *
     * Wrap the handler body in a nested [kotlinx.coroutines.launch] so the consumer loop stays free
     * to receive the next message while prior handlers are still running. This is **required** when
     * a handler suspends on [request]: without it, the loop cannot deliver the awaited response,
     * causing a deadlock.
     *
     * Use a [kotlinx.coroutines.SupervisorJob] in the scope so that one failing handler does not
     * cancel the others.
     *
     * ```kotlin
     * val handlerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
     *
     * handlerScope.launch {
     *     for (call in jsonRpc.callsInbox) {
     *         handlerScope.launch {  // each call is handled concurrently
     *             when (call) {
     *                 is JsonRpcCall.ExpectsResponse -> {
     *                     val upstream = otherPeer.request("fetch", call.params!!, 5.seconds)
     *                     call.replyWithResult(upstream.getOrThrow())
     *                 }
     *                 is JsonRpcCall.Notify -> handleNotification(call)
     *             }
     *         }
     *     }
     * }
     * ```
     */
    val callsInbox: ReceiveChannel<JsonRpcCall>

    /**
     * A [SharedFlow] of [JsonRpcException] that occurred locally for Observability purposes.
     */
    val localErrors: SharedFlow<JsonRpcException>

    /**
     * A [SharedFlow] of [JsonRpcException] that originated from the remote peer and were not directly linked to a request.
     *
     * It enables the tracking of errors sent back from the remote where the request id could not be
     * determined (e.g. ParseError, InvalidRequest).
     */
    val remoteErrors: SharedFlow<JsonRpcException>

    /**
     * Sends a request to the remote peer and suspends until a response is received or [timeout] elapses.
     *
     * Multiple concurrent calls to [request] and [notify] are safe: sends are serialized internally,
     * so callers never need to coordinate access. Each call suspends only for the duration of its own
     * transport write; the [timeout] covers the full round-trip from send to response.
     *
     * Returns a [Result] wrapping the response payload on success, or a [Result.failure] wrapping a
     * [JsonRpcException] on failure (remote error response, timeout, or connection closed mid-flight).
     *
     * @param method The name of the remote method to invoke.
     * @param params The invocation parameters. Per the JSON-RPC 2.0 specification this MUST be a
     *   [kotlinx.serialization.json.JsonObject] or a [kotlinx.serialization.json.JsonArray].
     * @param timeout Maximum time to wait for a response before returning [Result.failure] with a
     *   [JsonRpcException.InternalError]. Defaults to 30 seconds.
     */
    suspend fun request(method: String, params: JsonElement, timeout: Duration = 30.seconds): Result<JsonElement>

    /**
     * Sends a notification to the remote peer without expecting a response.
     *
     * Per the JSON-RPC 2.0 specification, the server MUST NOT reply to a notification. Use this
     * for fire-and-forget messages where delivery confirmation is not required.
     *
     * Like [request], concurrent calls are safe: sends are serialized internally, so this function
     * suspends only until the message has been handed off to the underlying transport.
     *
     * @param method The name of the remote method to invoke.
     * @param params The invocation parameters. Per the JSON-RPC 2.0 specification this MUST be a
     *   [kotlinx.serialization.json.JsonObject] or a [kotlinx.serialization.json.JsonArray].
     */
    suspend fun notify(method: String, params: JsonElement)

    /**
     * Closes the session and releases all associated resources.
     *
     * Performs the following steps in order:
     * - Cancels the internal coroutine scope, stopping message processing.
     * - Closes [callsInbox], causing any active `for` loop over it to terminate.
     * - Completes all pending [request] calls exceptionally with [JsonRpcException.InternalError].
     * - Calls [JsonRpcTransportSession.close] on the underlying transport. Any error thrown by
     *   the transport is swallowed and logged as a warning.
     *
     * This function is idempotent: calling it more than once has no effect and does not throw.
     */
    suspend fun close()

}