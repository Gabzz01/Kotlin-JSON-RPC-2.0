@file:OptIn(ExperimentalCoroutinesApi::class)

package fr.rtz.jsonrpc.examples

import fr.rtz.jsonrpc.JsonRpc
import fr.rtz.jsonrpc.JsonRpcCall
import fr.rtz.jsonrpc.JsonRpcTransportSession
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

suspend fun main() {
    val coroutineScope = CoroutineScope(Dispatchers.Default)
    val serverJob = coroutineScope.launch { startServer() }
    // Give some time to the server to start
    delay(1.seconds)
    val client = KtorWebsocketClientTransport(coroutineScope)
    val jsonRpc = JsonRpc.of(client.connect())
    // Handle messages sent to Ktor Server to Ktor Client over Json RPC
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

private fun startServer(): Deferred<JsonRpc> {
    val jsonRpcHandlerCoroutineScope = CoroutineScope(Dispatchers.Default)
    val deferred = CompletableDeferred<JsonRpc>()
    embeddedServer(Netty, port = 8080) {
        install(ServerWebSockets)
        routing {
            webSocket("/json-rpc") {
                val jsonRpc = JsonRpc.of(
                    KtorWebsocketTransportSession(
                        CoroutineScope(Dispatchers.IO), this
                    )
                )
                jsonRpcHandlerCoroutineScope.launch {
                    for (call in jsonRpc.callsInbox) {
                        JsonRpcCallHandlerExample.handle(call)
                    }
                }
                deferred.complete(jsonRpc)
                val response = jsonRpc.request(method = "hello", params = buildJsonObject { })
                val peerName = response.getOrElse { err ->
                    logger.error(err) { "Failed to get peer response" }
                    return@webSocket
                }
                logger.info { "$peerName said hello back." }
            }
        }
    }.start(wait = false)
    return deferred
}

// --- Transport -------------------------------------------------------------

private class KtorWebsocketTransportSession(
    coroutineScope: CoroutineScope,
    private val session: WebSocketSession
) : JsonRpcTransportSession {

    override val messageInbox: ReceiveChannel<String> = coroutineScope.produce {
        for (item in session.incoming) {
            if (item is Frame.Text) {
                send(item.readText())
            } else if (item is Frame.Close) {
                session.close()
            }
        }
    }

    override suspend fun send(json: String) {
        session.send(json)
    }

    override suspend fun close() {
        session.close()
    }
}

private class KtorWebsocketClientTransport(private val coroutineScope: CoroutineScope) {

    suspend fun connect(): JsonRpcTransportSession {
        val client = HttpClient(CIO) {
            install(WebSockets)
        }
        val session = client.webSocketSession(
            method = HttpMethod.Get,
            host = "127.0.0.1",
            port = 8080,
            path = "/json-rpc"
        )
        return KtorWebsocketTransportSession(coroutineScope, session)
    }
}
