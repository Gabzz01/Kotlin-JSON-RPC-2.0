@file:OptIn(ExperimentalUuidApi::class, InternalSerializationApi::class, ExperimentalSerializationApi::class)

package fr.rtz.jsonrpc.impl

import fr.rtz.jsonrpc.JsonRpc
import fr.rtz.jsonrpc.JsonRpcException
import fr.rtz.jsonrpc.JsonRpcCall
import fr.rtz.jsonrpc.JsonRpcTransportSession
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

internal class JsonRpcImpl(
    private val session: JsonRpcTransportSession,
) : JsonRpc {

    // Error tracking for observability
    private val _localErrors = MutableSharedFlow<JsonRpcException>(extraBufferCapacity = 16)
    private val _remoteErrors = MutableSharedFlow<JsonRpcException>(extraBufferCapacity = 16)

    // Dispatchers.Default is available on all KMP targets; Dispatchers.IO is JVM-only
    private val handlerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val pendingRequests = mutableMapOf<String, CompletableDeferred<JsonElement>>()
    private val pendingRequestsMutex = Mutex()

    // Serializes all session.send() calls. Real transports (WebSocket, TCP) typically do not
    // support concurrent writes — interleaved sends corrupt or silently drop messages.
    private val sendMutex = Mutex()

    override val callsInbox: Channel<JsonRpcCall> = Channel(Channel.UNLIMITED)

    override val localErrors = _localErrors.asSharedFlow()

    override val remoteErrors = _remoteErrors.asSharedFlow()

    companion object {
        private val logger = KotlinLogging.logger { }
        private val json = Json { ignoreUnknownKeys = true }
    }

    init {
        // Reads raw messages and routes them.
        handlerScope.launch {
            for (message in session.messageInbox) {
                try {
                    val messageAsJsonElement = try {
                        json.decodeFromString(JsonElement.serializer(), message)
                    } catch (_: Throwable) {
                        val jsonRpcException = JsonRpcException.ParseError("Failed to parse JSON Payload : $message")
                        _localErrors.emit(jsonRpcException)
                        val jsonRpcExceptionMessage = jsonRpcException.toJsonRpcMessage(null)
                        val response = json.encodeToString(jsonRpcExceptionMessage)
                        sendMutex.withLock { session.send(response) }
                        continue
                    }
                    val jsonRpcMessage = try {
                        json.decodeFromJsonElement(JsonRpcMessage.serializer(), messageAsJsonElement)
                    } catch (_: Throwable) {
                        // Best-effort ID extraction so the error response can be correlated by the remote peer
                        val extractedId = (messageAsJsonElement as? JsonObject)
                            ?.get("id")?.jsonPrimitive?.contentOrNull
                        val jsonRpcException = JsonRpcException.InvalidRequest(
                            "Failed to parse incoming JsonRpcMessage : $message"
                        )
                        _localErrors.emit(jsonRpcException)
                        val jsonRpcExceptionMessage = jsonRpcException.toJsonRpcMessage(extractedId)
                        val response = json.encodeToString(jsonRpcExceptionMessage)
                        sendMutex.withLock { session.send(response) }
                        continue
                    }
                    logger.debug { "Received $jsonRpcMessage" }
                    when (jsonRpcMessage) {
                        is JsonRpcMessage.Request -> {
                            val call = when (jsonRpcMessage) {
                                is JsonRpcMessage.Request.Standard -> JsonRpcExpectsResponseCallImpl(jsonRpcMessage)
                                is JsonRpcMessage.Request.Notify -> JsonRpcNotifyCallImpl(jsonRpcMessage)
                            }
                            callsInbox.send(call)
                        }

                        is JsonRpcMessage.Response -> {
                            handleResponse(jsonRpcMessage)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.error(e) { "Unexpected error processing message: $message" }
                    _localErrors.emit(JsonRpcException.InternalError("Unexpected error processing message: ${e.message}"))
                }
            }
            close()
        }
    }

    override suspend fun close() {
        logger.info { "Closing JSON-RPC connection..." }

        handlerScope.coroutineContext[Job]?.cancel()
        callsInbox.close()

        pendingRequestsMutex.withLock {
            pendingRequests.entries.forEach { (id, deferred) ->
                deferred.completeExceptionally(
                    JsonRpcException.InternalError("Connection closed while request $id was pending")
                )
            }
            pendingRequests.clear()
        }

        runCatching {
            session.close()
        }.onFailure { err ->
            logger.warn(err) { "Error while closing transport" }
        }

        logger.info { "JSON-RPC connection closed" }
    }

    override suspend fun request(
        method: String, params: JsonElement, timeout: Duration
    ): Result<JsonElement> {
        val id = Uuid.random().toString()
        val jsonParams = json.encodeToJsonElement(params)
        val request = JsonRpcMessage.Request.Standard(id, method, jsonParams)
        val jsonRequest = json.encodeToString(request)
        val completableFuture = CompletableDeferred<JsonElement>()
        pendingRequestsMutex.withLock { pendingRequests[id] = completableFuture }
        try {
            logger.debug { "Sending $request" }
            sendMutex.withLock { session.send(jsonRequest) }
            logger.debug { "Successfully sent $request" }
            return withTimeout(timeout) {
                Result.success(completableFuture.await())
            }
        } catch (_: TimeoutCancellationException) {
            throw JsonRpcException.InternalError("Request timed out for $request")
        } catch (err: JsonRpcException) {
            return Result.failure(err)
        } finally {
            pendingRequestsMutex.withLock { pendingRequests.remove(id) }
        }
    }

    override suspend fun notify(method: String, params: JsonElement) {
        val request = JsonRpcMessage.Request.Notify(method, params)
        val jsonRequest = json.encodeToString(request)
        logger.debug { "Sending $request" }
        sendMutex.withLock { session.send(jsonRequest) }
        logger.debug { "Successfully sent $request" }
    }

    private suspend fun handleResponse(response: JsonRpcMessage.Response) {
        val exchangeId = response.id
        // A null exchange id means the remote peer failed to identify the request (ParseError / InvalidRequest)
        val responseCompletable = exchangeId?.let {
            pendingRequestsMutex.withLock { pendingRequests.remove(it) }
        }
        when (response) {
            is JsonRpcMessage.Response.Error -> {
                val exception = when (response.code) {
                    JsonRpcException.Code.PARSE_ERROR -> JsonRpcException.ParseError(
                        response.message
                            ?: "Invalid JSON was received by the server. An error occurred on the server while parsing the JSON text.",
                        data = response.data
                    )

                    JsonRpcException.Code.INVALID_REQUEST -> JsonRpcException.InvalidRequest(
                        response.message ?: "The JSON sent is not a valid Request object.",
                        data = response.data
                    )

                    JsonRpcException.Code.METHOD_NOT_FOUND -> JsonRpcException.MethodNotFound(
                        response.message ?: "The method does not exist / is not available.",
                        data = response.data
                    )

                    JsonRpcException.Code.INVALID_PARAMS -> JsonRpcException.InvalidParams(
                        response.message ?: "No message",
                        data = response.data
                    )

                    JsonRpcException.Code.INTERNAL_ERROR -> JsonRpcException.InternalError(
                        "Remote peer responded with an Internal Error : ${response.message}",
                        data = response.data
                    )

                    else -> JsonRpcException.ServerError(
                        response.code.value, response.message ?: "No message",
                        data = response.data
                    )
                }
                if (responseCompletable == null) {
                    _remoteErrors.emit(exception)
                } else {
                    responseCompletable.completeExceptionally(exception)
                }
            }

            is JsonRpcMessage.Response.Result -> {
                responseCompletable?.complete(response.result)
            }
        }
    }

    private class JsonRpcNotifyCallImpl(
        message: JsonRpcMessage.Request.Notify
    ) : JsonRpcCall.Notify {
        override val method = message.method
        override val params = message.params
    }

    private inner class JsonRpcExpectsResponseCallImpl(
        message: JsonRpcMessage.Request.Standard,
    ) : JsonRpcCall.ExpectsResponse {
        override val requestId: String = message.id
        override val method = message.method
        override val params = message.params

        private val replyLock = Mutex()
        private var replied = false

        private suspend fun sendReply(reply: JsonRpcMessage) {
            val alreadyReplied = replyLock.withLock {
                if (replied) true else {
                    replied = true; false
                }
            }
            if (alreadyReplied) {
                logger.warn { "Duplicate reply suppressed for request $requestId" }
                return
            }
            val replyJson = json.encodeToString(reply)
            logger.debug { "Replying $reply" }
            sendMutex.withLock { session.send(replyJson) }
            logger.debug { "Successfully sent $reply" }
        }

        override suspend fun replyWithResult(result: JsonElement) {
            sendReply(JsonRpcMessage.Response.Result(id = requestId, result = result))
        }

        override suspend fun replyWithMethodNotFound() {
            val exception = JsonRpcException.MethodNotFound("")
            _localErrors.emit(exception)
            sendReply(exception.toJsonRpcMessage(requestId))
        }

        override suspend fun replyWithInvalidParams() {
            val exception = JsonRpcException.InvalidParams("")
            _localErrors.emit(exception)
            sendReply(exception.toJsonRpcMessage(requestId))
        }

        override suspend fun replyWithServerError(code: Int, message: String, data: JsonElement?) {
            val exception = JsonRpcException.ServerError(code, message, data = data)
            _localErrors.emit(exception)
            sendReply(exception.toJsonRpcMessage(requestId))
        }
    }

    private fun JsonRpcException.toJsonRpcMessage(id: String?): JsonRpcMessage {
        return JsonRpcMessage.Response.Error(
            id = id,
            message = this.message,
            code = this.code,
            data = this.data
        )
    }
}
