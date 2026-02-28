package fr.rtz.jsonrpc.utils

import fr.rtz.jsonrpc.JsonRpcTransportSession
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel

internal class InMemoryJsonRpcTransportSession(
    private val inboxChannel: ReceiveChannel<String>,
    private val outboxChannel: SendChannel<String>
) : JsonRpcTransportSession {

    override val messageInbox: ReceiveChannel<String>
        get() = inboxChannel

    override suspend fun send(json: String) {
        outboxChannel.send(json)
    }

    override suspend fun close() {
        outboxChannel.close()
    }
}