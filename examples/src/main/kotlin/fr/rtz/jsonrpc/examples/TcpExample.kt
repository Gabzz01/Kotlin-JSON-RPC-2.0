package fr.rtz.jsonrpc.examples

import fr.rtz.jsonrpc.JsonRpc
import fr.rtz.jsonrpc.JsonRpcCall
import fr.rtz.jsonrpc.JsonRpcTransportSession
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}
private const val PORT = 9000

/**
 * TCP Socket JSON-RPC example.
 *
 * Starts a TCP server on [PORT] that handles JSON-RPC calls, then connects a
 * client and demonstrates a [JsonRpcCall.ExpectsResponse] and a [JsonRpcCall.Notify].
 */
suspend fun main() {
    val coroutineScope = CoroutineScope(Dispatchers.Default)

    val serverJob = coroutineScope.launch { runServer() }
    // Give the server time to bind before the client connects
    delay(1.seconds)

    runClient(coroutineScope)

    serverJob.cancelAndJoin()
}

// --- Server ----------------------------------------------------------------

private suspend fun runServer() {
    val serverSocket = withContext(Dispatchers.IO) { ServerSocket(PORT) }
    logger.info { "Listening on port $PORT" }

    // Accept one client for this example
    val socket = withContext(Dispatchers.IO) { serverSocket.accept() }
    logger.info { "Client connected from ${socket.inetAddress.hostAddress}" }

    val session = TcpJsonRpcTransportSession(CoroutineScope(Dispatchers.IO), socket)
    val connection = JsonRpc.of(session)

    for (call in connection.callsInbox) {
        JsonRpcCallHandlerExample.handle(call)
    }
}

// --- Client ----------------------------------------------------------------

private suspend fun runClient(coroutineScope: CoroutineScope) {
    val socket = withContext(Dispatchers.IO) { Socket("127.0.0.1", PORT) }
    val connection = JsonRpc.of(TcpJsonRpcTransportSession(coroutineScope, socket))

    // Request → ExpectsResponse
    val response = connection.request("greet", buildJsonObject { put("name", "World") })
    val reply = response.getOrThrow()
    logger.info { "Server replied: $reply" }

    // Notification → no response expected
    connection.notify("log", buildJsonObject { put("text", "example complete") })

    connection.close()
}

// --- Transport -------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
private class TcpJsonRpcTransportSession(
    coroutineScope: CoroutineScope,
    private val socket: Socket,
) : JsonRpcTransportSession {

    private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
    private val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

    override val messageInbox: ReceiveChannel<String> = coroutineScope.produce(Dispatchers.IO) {
        try {
            var line = reader.readLine()
            while (line != null) {
                send(line)
                line = reader.readLine()
            }
        } finally {
            close()
        }
    }

    override suspend fun send(json: String) = withContext(Dispatchers.IO) {
        writer.write(json)
        writer.newLine()
        writer.flush()
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        socket.close()
    }
}
