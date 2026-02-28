package fr.rtz.jsonrpc.examples

import fr.rtz.jsonrpc.JsonRpc
import fr.rtz.jsonrpc.JsonRpcCall
import fr.rtz.jsonrpc.JsonRpcTransportSession
import io.github.oshai.kotlinlogging.KotlinLogging
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.Router
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.kotlin.coroutines.toChannel
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
 * Vert.x SSE JSON-RPC example.
 *
 * Demonstrates a hybrid transport where JSON-RPC runs over two HTTP channels:
 *  - **Server → Client**: Server-Sent Events (`GET /sse`) — a persistent push stream
 *  - **Client → Server**: HTTP POST (`POST /messages`) — one request per message
 */
suspend fun main() {
    val vertx = Vertx.vertx()
    val coroutineScope = CoroutineScope(Dispatchers.Default)

    val serverJob = coroutineScope.launch { startServer(vertx, coroutineScope) }
    // Give some time to the server to start
    delay(1.seconds)

    val session = connectToServer(vertx, coroutineScope)
    val jsonRpc = JsonRpc.of(session)
    // Handle messages sent from the SSE Server to the SSE Client over JSON-RPC
    val clientJob = coroutineScope.launch {
        for (call in jsonRpc.callsInbox) {
            JsonRpcCallHandlerExample.handle(call)
        }
    }
    logger.info { "JSON-RPC Connection established" }
    jsonRpc.notify("hello", buildJsonObject { put("name", "World") })
    serverJob.cancelAndJoin()
    clientJob.cancelAndJoin()
    vertx.close().coAwait()
}

private fun startServer(vertx: Vertx, scope: CoroutineScope) {
    val jsonRpcHandlerScope = CoroutineScope(Dispatchers.Default)
    // Shared between the SSE handler (producer) and the POST handler (consumer).
    // The POST handler suspends on await() until the SSE connection is established.
    val activeInboxChannel = CompletableDeferred<Channel<String>>()

    val router = Router.router(vertx)

    router.get("/sse").handler { ctx ->
        val sseResponse = ctx.response()
        sseResponse
            .putHeader("Content-Type", "text/event-stream")
            .putHeader("Cache-Control", "no-cache")
            .putHeader("Connection", "keep-alive")
            .setChunked(true)

        val inboxChannel = Channel<String>(Channel.UNLIMITED)
        activeInboxChannel.complete(inboxChannel)

        // outboxChannel bridges session.send() calls to the SSE push stream below
        val outboxChannel = Channel<String>(Channel.UNLIMITED)

        val session = object : JsonRpcTransportSession {
            override val messageInbox: ReceiveChannel<String> = inboxChannel
            override suspend fun send(json: String) { outboxChannel.send(json) }
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

        scope.launch {
            val response = jsonRpc.request(method = "hello", params = buildJsonObject { })
            val peerName = response.getOrElse { err ->
                logger.error(err) { "Failed to get peer response" }
                return@launch
            }
            logger.info { "$peerName said hello back." }
        }

        // Keep the SSE connection alive by pumping outgoing messages as SSE events
        scope.launch {
            for (msg in outboxChannel) {
                sseResponse.write("data: $msg\n\n")
            }
        }
    }

    router.post("/messages").handler { ctx ->
        scope.launch {
            val body = ctx.request().body().coAwait().toString()
            // Waits for the SSE connection to be established if not yet ready
            activeInboxChannel.await().send(body)
            ctx.response().setStatusCode(202).end()
        }
    }

    vertx.createHttpServer()
        .requestHandler(router)
        .listen(8080)
}

/**
 * A client using a hybrid SSE + HTTP POST approach:
 *  - **Server → Client**: Server-Sent Events on `GET /sse`
 *  - **Client → Server**: HTTP POST on `POST /messages`
 */
private fun connectToServer(
    vertx: Vertx,
    coroutineScope: CoroutineScope,
    host: String = "127.0.0.1",
    port: Int = 8080,
): JsonRpcTransportSession {
    val inboxChannel = Channel<String>(Channel.UNLIMITED)
    val httpClient = vertx.createHttpClient()

    // Open the SSE connection in background; incoming events are pumped into inboxChannel
    coroutineScope.launch {
        val req = httpClient.request(HttpMethod.GET, port, host, "/sse").coAwait()
        val response = req.send().coAwait()

        for (buffer in response.toChannel(vertx)) {
            buffer.toString(Charsets.UTF_8).lines().forEach { line ->
                if (line.startsWith("data: ")) {
                    inboxChannel.trySend(line.removePrefix("data: "))
                }
            }
        }
        inboxChannel.close()
    }

    return object : JsonRpcTransportSession {

        override val messageInbox: ReceiveChannel<String> = inboxChannel

        override suspend fun send(json: String) {
            val req = httpClient.request(HttpMethod.POST, port, host, "/messages").coAwait()
            req.putHeader("Content-Type", "application/json")
            req.send(json).coAwait()
        }

        override suspend fun close() {
            httpClient.close()
        }
    }
}
