package fr.rtz.jsonrpc.helpers

import fr.rtz.jsonrpc.JsonRpcCall
import fr.rtz.jsonrpc.JsonRpc
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

interface JsonRpcEndpoint<I> {
    val method: String
    val inputSerializer: KSerializer<I>

    interface WithReply<I, O> : JsonRpcEndpoint<I> {
        val outputSerializer: KSerializer<O>
    }
}

suspend fun <I, O> JsonRpc.request(call: Pair<JsonRpcEndpoint.WithReply<I, O>, I>, json: Json = Json): O {
    val (endpoint, params) = call
    val paramsAsJson = json.encodeToJsonElement(endpoint.inputSerializer, params)
    val responseAsJson = this.request(endpoint.method, paramsAsJson)
    val response = json.decodeFromJsonElement(endpoint.outputSerializer, responseAsJson.getOrThrow())
    return response
}

interface JsonRpcMessageHandler {
    suspend fun handle(ctx: JsonRpcCall)
}

interface RpcMessageHandlerBuilder {

    fun <I, O> requestHandler(op: JsonRpcEndpoint.WithReply<I, O>, handler: suspend (I) -> O)

    fun <I> notifyHandler(op: JsonRpcEndpoint<I>, handler: suspend (I) -> Unit)

}

@Suppress("MethodName")
fun JsonRpcMessageHandler(
    json: Json = Json,
    block: RpcMessageHandlerBuilder.() -> Unit,
): JsonRpcMessageHandler {

    val handlers = mutableMapOf<String, suspend (JsonRpcCall.ExpectsResponse) -> Unit>()
    val notifiees = mutableMapOf<String, suspend (JsonRpcCall.Notify) -> Unit>()

    val builder = object : RpcMessageHandlerBuilder {
        override fun <I, O> requestHandler(
            op: JsonRpcEndpoint.WithReply<I, O>,
            handler: suspend (I) -> O
        ) {
            handlers[op.method] = { ctx ->
                val params = json.decodeFromJsonElement(op.inputSerializer, ctx.params ?: JsonNull)
                val response = handler(params)
                val responseAsJson = json.encodeToJsonElement(op.outputSerializer, response)
                ctx.replyWithResult(responseAsJson)
            }
        }

        override fun <I> notifyHandler(
            op: JsonRpcEndpoint<I>,
            handler: suspend (I) -> Unit
        ) {
            notifiees[op.method] = { ctx ->
                val params = json.decodeFromJsonElement(op.inputSerializer, ctx.params ?: JsonNull)
                handler(params)
            }
        }
    }

    with(builder) { block() }

    return object : JsonRpcMessageHandler {
        override suspend fun handle(ctx: JsonRpcCall) {
            when (ctx) {
                is JsonRpcCall.ExpectsResponse -> {
                    val op = handlers[ctx.method] ?: return ctx.replyWithMethodNotFound()
                    try {
                        op(ctx)
                    } catch (err: Throwable) {
                        ctx.replyWithServerError(-32000, err.message ?: "An error occurred")
                    }
                }

                is JsonRpcCall.Notify -> {
                    val op = notifiees[ctx.method] ?: return
                    try {
                        op(ctx)
                    } catch (_: Throwable) {
                        // Notifications cannot be error-replied per spec; swallow silently
                    }
                }
            }
        }
    }
}
