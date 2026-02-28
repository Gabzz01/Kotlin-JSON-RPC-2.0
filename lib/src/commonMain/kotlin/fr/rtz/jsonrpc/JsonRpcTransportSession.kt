package fr.rtz.jsonrpc

import kotlinx.coroutines.channels.ReceiveChannel

/**
 * The raw transport contract that backs a [JsonRpc] session.
 *
 * Implement this interface to adapt any underlying connection technology (WebSocket, TCP, stdio,
 * SSE, …) to the JSON-RPC layer. Pass the implementation to [JsonRpc.of] and the protocol
 * handling is taken care of automatically.
 *
 * ## Implementing this interface
 *
 * A minimal implementation has three responsibilities:
 *
 * **1. Feed [messageInbox]** — run a read loop on your transport and send each incoming raw JSON
 * string into the channel. Using [kotlinx.coroutines.channels.produce] is a convenient way to do
 * this, as the channel is closed automatically when the coroutine completes:
 *
 * ```kotlin
 * override val messageInbox: ReceiveChannel<String> = coroutineScope.produce {
 *     for (frame in websocketSession.incoming) {
 *         send(frame.readText())
 *     }
 *     // channel closes here → JsonRpc detects the disconnection
 * }
 * ```
 *
 * **Closing [messageInbox] is critical.** When the channel closes, [JsonRpc] stops its message
 * loop and closes [JsonRpc.callsInbox]. If the channel never closes, the session will hang
 * indefinitely after the connection drops.
 *
 * **2. Implement [send]** — write the given JSON string to the underlying transport.
 * [JsonRpc] serializes all calls to [send] internally, so this function is **never called
 * concurrently**. No additional locking is needed on your end.
 *
 * **3. Implement [close]** — tear down the underlying connection. This triggers the remote peer
 * to close as well, which in turn causes your read loop to finish and [messageInbox] to close.
 * Any exception thrown here is caught and logged by [JsonRpc]; it will not propagate to the caller.
 * Implement it as idempotent when possible.
 */
interface JsonRpcTransportSession {

    /**
     * A channel of raw incoming JSON strings from the remote peer.
     *
     * The implementation is responsible for feeding every incoming message into this channel.
     * **The channel must be closed when the underlying connection ends** (either cleanly or due to
     * an error), so that [JsonRpc] can detect the disconnection and terminate its internal loops.
     */
    val messageInbox: ReceiveChannel<String>

    /**
     * Writes a raw JSON string to the underlying transport.
     *
     * [JsonRpc] guarantees that this function is never called concurrently — all sends are
     * serialized internally. The implementation only needs to forward the string to the transport.
     *
     * @param json A fully serialized JSON-RPC message ready to be sent over the wire.
     */
    suspend fun send(json: String)

    /**
     * Closes the underlying transport connection.
     *
     * Called by [JsonRpc.close] as part of its shutdown sequence. Any exception thrown here is
     * swallowed and logged as a warning by [JsonRpc]; it will not propagate to the caller.
     *
     * Closing the transport should cause the remote peer to close as well, which will drain and
     * close [messageInbox] naturally through the read loop.
     */
    suspend fun close()

}
