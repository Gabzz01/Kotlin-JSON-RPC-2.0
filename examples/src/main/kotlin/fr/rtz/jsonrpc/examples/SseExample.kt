package fr.rtz.jsonrpc.examples

import fr.rtz.jsonrpc.JsonRpcCall
import fr.rtz.jsonrpc.JsonRpc
import fr.rtz.jsonrpc.JsonRpcTransportSession
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * SSE JSON-RPC example.
 *
 * Demonstrates a hybrid transport where JSON-RPC runs over two HTTP channels:
 *  - **Server → Client**: Server-Sent Events (`GET /sse`) — a persistent push stream
 *  - **Client → Server**: HTTP POST (`POST /messages`) — one request per message
 *
 * This pattern is transport-agnostic for the library: as long as a [JsonRpcTransport.Session]
 * provides a [ReceiveChannel] for incoming messages and a [send] function, JSON-RPC works on top.
 */
suspend fun main() {
    val coroutineScope = CoroutineScope(Dispatchers.Default)
    val serverJob = coroutineScope.launch { startServer() }
    // Give some time to the server to start
    delay(1.seconds)

    val session = connectToServer(coroutineScope)
    val jsonRpc = JsonRpc.of(session)
    // Handle messages sent from the SSE Server to the SSE Client over JSON-RPC
    val clientJob = coroutineScope.launch {
        for (call in jsonRpc.callsInbox) {
            JsonRpcCallHandlerExample.handle(call)
        }
    }
    logger.info { "Json RPC Connection established" }
    jsonRpc.notify("hello", buildJsonObject { put("name", "World") })
    serverJob.cancelAndJoin()
    clientJob.cancelAndJoin()
}

private fun startServer() {
    val jsonRpcHandlerScope = CoroutineScope(Dispatchers.Default)
    // Shared between the SSE handler (producer) and the POST handler (consumer).
    // The POST handler suspends on await() until the SSE connection is established.
    val activeInboxChannel = CompletableDeferred<Channel<String>>()

    embeddedServer(Netty, port = 8080) {
        install(SSE)
        routing {
            sse("/sse") {
                val inboxChannel = Channel<String>(Channel.UNLIMITED)
                activeInboxChannel.complete(inboxChannel)

                // outboxChannel bridges session.send() calls to the SSE push stream below
                val outboxChannel = Channel<String>(Channel.UNLIMITED)

                val session = object : JsonRpcTransportSession {
                    override val messageInbox: ReceiveChannel<String> = inboxChannel
                    override suspend fun send(json: String) {
                        outboxChannel.send(json)
                    }

                    override suspend fun close() {
                        inboxChannel.close()
                        outboxChannel.close()
                    }
                }

                val jsonRpc = JsonRpc.of(session)

                jsonRpcHandlerScope.launch {
                    for (call in jsonRpc.callsInbox) {
                        JsonRpcCallHandlerExample.handle(call)
                    }
                }

                jsonRpcHandlerScope.launch {
                    val response = jsonRpc.request(method = "hello", params = buildJsonObject { })
                    val peerName = response.getOrElse { err ->
                        logger.error(err) { "Failed to get peer response" }
                        return@launch
                    }
                    logger.info { "$peerName said hello back." }
                }

                // Keep the SSE connection alive by pumping outgoing messages as SSE events
                for (msg in outboxChannel) {
                    send(ServerSentEvent(data = msg))
                }
            }

            post("/messages") {
                val body = call.receiveText()
                // Waits for the SSE connection to be established if not yet ready
                val channel = activeInboxChannel.await()
                channel.send(body)
                call.respond(HttpStatusCode.Accepted)
            }
        }
    }.start(wait = false)
}

/**
 * A client using a hybrid SSE + HTTP POST approach:
 *  - **Server → Client**: Server-Sent Events on `GET /sse`
 *  - **Client → Server**: HTTP POST on `POST /messages`
 */
private fun connectToServer(
    coroutineScope: CoroutineScope,
    host: String = "127.0.0.1",
    port: Int = 8080,
): JsonRpcTransportSession {
    val httpClient = HttpClient(CIO) {
        install(io.ktor.client.plugins.sse.SSE)
    }

    val inboxChannel = Channel<String>(Channel.UNLIMITED)

    // Open the SSE connection in background; incoming events are pumped into inboxChannel
    coroutineScope.launch {
        httpClient.sse("http://$host:$port/sse") {
            incoming.collect { event ->
                event.data?.let { inboxChannel.send(it) }
            }
        }
        inboxChannel.close()
    }

    return object : JsonRpcTransportSession {

        override val messageInbox: ReceiveChannel<String> = inboxChannel

        override suspend fun send(json: String) {
            httpClient.post("http://$host:$port/messages") {
                contentType(ContentType.Application.Json)
                setBody(json)
            }
        }

        override suspend fun close() {
            httpClient.close()
        }
    }
}