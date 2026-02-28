@file:OptIn(ExperimentalCoroutinesApi::class)

package fr.rtz.jsonrpc.examples

import fr.rtz.jsonrpc.JsonRpc
import fr.rtz.jsonrpc.JsonRpcTransportSession
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

private val logger = KotlinLogging.logger {}

/**
 * Stdio JSON-RPC example.
 *
 * Run without arguments to start the **client**, which spawns a child server process
 * and communicates with it over JSON-RPC via the child's stdin/stdout.
 *
 * The child process is the same executable launched with `--server`, which reads
 * JSON-RPC messages from its stdin and writes responses to its stdout.
 */
suspend fun main(args: Array<String>) {
    if ("--server" in args) {
        runServer()
    } else {
        runClient()
    }
}

// --- Server mode -----------------------------------------------------------

/**
 * Wraps [System.in] / [System.out] as a [JsonRpcTransportSession] and handles
 * incoming calls until the parent process closes the pipe.
 */
private suspend fun runServer() {
    val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    val writer = BufferedWriter(OutputStreamWriter(System.out, Charsets.UTF_8))

    val session = object : JsonRpcTransportSession {

        override val messageInbox: ReceiveChannel<String> = CoroutineScope(Dispatchers.IO).produce {
            var line = reader.readLine()
            while (line != null) {
                send(line)
                line = reader.readLine()
            }
        }

        override suspend fun send(json: String) = withContext(Dispatchers.IO) {
            writer.write(json)
            writer.newLine()
            writer.flush()
        }

        override suspend fun close() {}
    }

    val connection = JsonRpc.of(session)

    for (call in connection.callsInbox) {
        JsonRpcCallHandlerExample.handle(call)
    }
}

// --- Client mode -----------------------------------------------------------

/**
 * Spawns a child server process (this same executable with `--server`) and
 * communicates with it over JSON-RPC via the child's stdin/stdout.
 */
private suspend fun runClient() {
    val javaPath = ProcessHandle.current().info().command().orElse("java")
    val classPath = System.getProperty("java.class.path")

    val process = ProcessBuilder(
        javaPath, "-cp", classPath,
        "fr.rtz.jsonrpc.examples.stdio.StdioExampleKt",
        "--server",
    )
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()

    val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
    val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))

    val session = object : JsonRpcTransportSession {

        override val messageInbox: ReceiveChannel<String> = CoroutineScope(Dispatchers.IO).produce {
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
            writer.close()
            process.destroy()
        }
    }

    val connection = JsonRpc.of(session)

    // Request → ExpectsResponse
    val response = connection.request("greet", buildJsonObject { put("name", "World") })
    val reply = response.getOrThrow()
    logger.info { "Server replied: $reply" }

    // Notification → no response expected
    connection.notify("log", buildJsonObject { put("text", "example complete") })

    connection.close()
}
