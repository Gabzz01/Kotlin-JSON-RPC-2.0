package fr.rtz.jsonrpc.examples

import fr.rtz.jsonrpc.JsonRpc
import fr.rtz.jsonrpc.JsonRpcCall
import fr.rtz.jsonrpc.JsonRpcTransportSession
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.handler.codec.mqtt.MqttProperties
import io.netty.handler.codec.mqtt.MqttQoS
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mqtt.MqttClient
import io.vertx.mqtt.MqttClientOptions
import io.vertx.mqtt.MqttServer
import io.vertx.mqtt.messages.codes.MqttSubAckReasonCode
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

private const val MQTT_PORT = 1883
private const val CLIENT_ID = "json-rpc-client"

/**
 * Topics for bidirectional JSON-RPC over MQTT:
 *  - **Client → Server**: [TOPIC_SERVER] — client publishes here, server receives via [MqttServer.endpointHandler]
 *  - **Server → Client**: [TOPIC_CLIENT] — server publishes here, client subscribes
 */
private const val TOPIC_SERVER = "rpc/server"
private const val TOPIC_CLIENT = "rpc/client/$CLIENT_ID"

/**
 * Vert.x MQTT JSON-RPC example.
 *
 * Starts an embedded MQTT server (no external broker needed — Vert.x implements the
 * MQTT protocol directly), then connects an MQTT client and exchanges JSON-RPC messages
 * over two dedicated topics.
 */
suspend fun main() {
    val vertx = Vertx.vertx()
    val coroutineScope = CoroutineScope(Dispatchers.Default)

    val serverJob = coroutineScope.launch { startServer(vertx, coroutineScope) }
    // Give the server time to bind before the client connects
    delay(1.seconds)

    val session = connectClient(vertx, coroutineScope)
    val jsonRpc = JsonRpc.of(session)
    // Handle messages sent from the MQTT Server to the MQTT Client over JSON-RPC
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

    MqttServer.create(vertx)
        .endpointHandler { endpoint ->
            logger.info { "Client [${endpoint.clientIdentifier()}] connected" }
            endpoint.accept(false)

            // Channel for JSON-RPC messages coming from the client
            val inboxChannel = Channel<String>(Channel.UNLIMITED)

            endpoint.publishHandler { msg ->
                inboxChannel.trySend(msg.payload().toString(Charsets.UTF_8))
                if (msg.qosLevel() == MqttQoS.AT_LEAST_ONCE) {
                    endpoint.publishAcknowledge(msg.messageId())
                }
            }

            // Acknowledge SUBSCRIBE and start the JSON-RPC session once the client
            // is subscribed to its response topic and ready to receive pushes
            endpoint.subscribeHandler { subscribe ->
                val reasonCodes = subscribe.topicSubscriptions().map { sub ->
                    MqttSubAckReasonCode.qosGranted(sub.qualityOfService())
                }
                endpoint.subscribeAcknowledge(
                    subscribe.messageId(),
                    reasonCodes,
                    MqttProperties.NO_PROPERTIES,
                )

                val session = object : JsonRpcTransportSession {
                    override val messageInbox: ReceiveChannel<String> = inboxChannel
                    override suspend fun send(json: String) {
                        endpoint.publish(
                            TOPIC_CLIENT,
                            Buffer.buffer(json),
                            MqttQoS.AT_LEAST_ONCE,
                            false,
                            false,
                        ).coAwait()
                    }

                    override suspend fun close() {
                        inboxChannel.close()
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
            }

            endpoint.closeHandler {
                logger.info { "Client [${endpoint.clientIdentifier()}] disconnected" }
            }
        }
        .listen(MQTT_PORT)
}

private suspend fun connectClient(
    vertx: Vertx,
    coroutineScope: CoroutineScope,
): JsonRpcTransportSession {
    val options = MqttClientOptions()
        .setClientId(CLIENT_ID)
        .setCleanSession(true)
        .setAutoKeepAlive(true)

    val client = MqttClient.create(vertx, options)

    // Set the publish handler BEFORE connecting — messages can arrive immediately after subscribe
    val inboxChannel = Channel<String>(Channel.UNLIMITED)
    client.publishHandler { msg ->
        inboxChannel.trySend(msg.payload().toString(Charsets.UTF_8))
    }
    client.closeHandler {
        inboxChannel.close()
    }

    client.connect(MQTT_PORT, "127.0.0.1").coAwait()
    // Subscribe to the per-client response topic before sending anything
    client.subscribe(TOPIC_CLIENT, 1).coAwait()
    logger.info { "MQTT client connected and subscribed to $TOPIC_CLIENT" }

    return object : JsonRpcTransportSession {
        override val messageInbox: ReceiveChannel<String> = inboxChannel

        override suspend fun send(json: String) {
            client.publish(
                TOPIC_SERVER,
                Buffer.buffer(json),
                MqttQoS.AT_LEAST_ONCE,
                false,
                false,
            ).coAwait()
        }

        override suspend fun close() {
            client.disconnect().coAwait()
        }
    }
}
