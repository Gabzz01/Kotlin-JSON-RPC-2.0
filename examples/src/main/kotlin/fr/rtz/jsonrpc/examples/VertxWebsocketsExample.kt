package fr.rtz.jsonrpc.examples

import fr.rtz.jsonrpc.JsonRpc
import fr.rtz.jsonrpc.JsonRpcCall
import fr.rtz.jsonrpc.JsonRpcTransportSession
import io.github.oshai.kotlinlogging.KotlinLogging
import io.vertx.core.Vertx
import io.vertx.core.http.WebSocketBase
import io.vertx.core.http.WebSocketConnectOptions
import io.vertx.kotlin.coroutines.coAwait
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

suspend fun main() {
    val vertx = Vertx.vertx()
    val coroutineScope = CoroutineScope(Dispatchers.Default)

    val serverJob = coroutineScope.launch { startServer(vertx, coroutineScope) }
    // Give some time to the server to start
    delay(1.seconds)

    val options = WebSocketConnectOptions()
        .setHost("127.0.0.1")
        .setPort(8080)
        .setURI("/json-rpc")
    val ws = vertx.createHttpClient().webSocket(options).coAwait()
    val jsonRpc = JsonRpc.of(VertxWebsocketTransportSession(ws))
    // Handle messages sent from Vert.x Server to Vert.x Client over JSON-RPC
    val clientJob = coroutineScope.launch {
        for (call in jsonRpc.callsInbox) {
            JsonRpcCallHandlerExample.handle(call)
        }
    }
    logger.info { "Json RPC Connection established" }
    jsonRpc.notify("hello", buildJsonObject { put("name", "World") })
    serverJob.cancelAndJoin()
    clientJob.cancelAndJoin()
    vertx.close().coAwait()
}

private fun startServer(vertx: Vertx, scope: CoroutineScope) {
    val jsonRpcHandlerCoroutineScope = CoroutineScope(Dispatchers.Default)

    vertx.createHttpServer()
        .webSocketHandler { ws ->
            val jsonRpc = JsonRpc.of(VertxWebsocketTransportSession(ws))
            jsonRpcHandlerCoroutineScope.launch {
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
        }
        .listen(8080)
}

// --- Transport -------------------------------------------------------------

private class VertxWebsocketTransportSession(
    private val ws: WebSocketBase,
) : JsonRpcTransportSession {

    private val channel = Channel<String>(Channel.UNLIMITED)

    init {
        ws.textMessageHandler { msg -> channel.trySend(msg) }
        ws.closeHandler { channel.close() }
        ws.exceptionHandler { channel.close(it) }
    }

    override val messageInbox: ReceiveChannel<String> = channel

    override suspend fun send(json: String) {
        ws.writeTextMessage(json).coAwait()
    }

    override suspend fun close() {
        ws.close().coAwait()
    }
}
