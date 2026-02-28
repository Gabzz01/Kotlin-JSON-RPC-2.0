# Kotlin JSON-RPC 2.0

[![Kotlin](https://img.shields.io/badge/kotlin-2.3.10-blue.svg?logo=kotlin)](http://kotlinlang.org)
![GitHub License](https://img.shields.io/github/license/Gabzz01/Kotlin-JSON-RPC-2.0)

A pure Kotlin, transport-agnostic implementation of the [JSON RPC 2.0 specification](https://www.jsonrpc.org/specification) based on 
`kotlinx.serialization.json` and `kotlinx.coroutines`.

## Overview

[JSON-RPC 2.0](https://www.jsonrpc.org/specification) is a lightweight remote procedure call protocol built on JSON.
Unlike traditional request/response APIs, it is **bidirectional** — once a connection is established, either peer can initiate
requests, responses, or fire-and-forget notifications.

This library was built out of a concrete use case: enabling **bidirectional communication between a master server
and IoT edge controllers** over a persistent connection, where only the master server is publicly exposed.
It has since been battle tested in production against real hardware, unreliable connections,
and the constraints of industrial deployments.

As specified by the JSON-RPC 2.0 specification, this library is transport agnostic, letting you implement the transport
layer that fits your problem (STDIO, WebSockets, SSE, MQTT, and more). Transport implementation examples are available
in the [examples](./examples) directory.

It also aims to be minimal and easily extensible, leaving extension points for
auto-reconnect, retries, circuit breakers, telemetry, and whatever else your use case demands.

## Getting Started

### Installation
Add the Github Packages repository and the library dependency:

```kotlin
// build.gradle.kts
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/Gabzz01/Kotlin-JSON-RPC-2.0")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("USERNAME")
            password = project.findProperty("gpr.key") ?: System.getenv("TOKEN")
        }
    }
}

dependencies {
    implementation("io.github.gabzz01:lib:ef968e2")
}
```

### Transport Implementation

Define your transport layer by implementing the `JsonRpcTransportSession` interface
```kotlin
// Example using Ktor's WebSocket client
// The scope parameter is explicit — the caller owns the lifecycle
suspend fun CoroutineScope.jsonRpcOverWebsocket(url: String): JsonRpcTransportSession {
    val client = HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = 20_000
        }
    }
    val session = client.webSocketSession(url)
    val inboxChannel = Channel<String>(Channel.UNLIMITED)

    val job = launch {
        for (frame in session.incoming) {
            if (frame is Frame.Text) {
                inboxChannel.send(frame.readText())
            }
        }
    }
    
    return object : JsonRpcTransportSession {
        override val messageInbox: ReceiveChannel<String> = inboxChannel
        override suspend fun send(json: String) = session.send(Frame.Text(json))
        override suspend fun close() { session.close(); job.cancelAndJoin() }
    }
}
```

### Request and Handle

```kotlin
suspend fun main() {
    val transportSession = jsonRpcOverWebsocket("ws://localhost:8080", this)
    val jsonRpc = JsonRpc.of(transportSession)

    // Send requests
    val response = jsonRpc.request(method = "greet", params = buildJsonObject { put("name", "John") })

    // Or notifications
    jsonRpc.notify(method = "sensor.updates", params = buildJsonObject { put("temperature", 22) })

    // Consume incoming calls — structured, cancelled when main() returns
    coroutineScope {
        launch {
            for (call in jsonRpc.callsInbox) {
                when (call) {
                    is JsonRpcCall.ExpectsResponse -> {
                        if (call.method == "sensor.set-temperature") {
                            try {
                                call.replyWithResult(buildJsonObject { put("status", "OK") })
                            } catch (err: Throwable) {
                                call.replyWithServerError()
                            }
                        } else {
                            call.replyWithMethodNotFound()
                        }
                    }
                    is JsonRpcCall.Notify -> {
                        println("Received notification: method=${call.method}, params=${call.params}")
                    }
                }
            }
        }
    }
}
```