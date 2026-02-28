package fr.rtz.jsonrpc.examples

import fr.rtz.jsonrpc.JsonRpcCall
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * [JsonRpcCall] handler example
 */
object JsonRpcCallHandlerExample {

    const val GREET_METHOD = "greet"
    const val DIVIDE_METHOD = "divide"

    private val logger = KotlinLogging.logger { }

    suspend fun handle(call: JsonRpcCall) {
        when (call) {
            is JsonRpcCall.ExpectsResponse -> {
                when (call.method) {

                    GREET_METHOD -> call.replyWithResult(buildJsonObject {
                        put("message", "Nice to meet you")
                    })

                    DIVIDE_METHOD -> {
                        val a = call.params?.jsonObject["a"]?.jsonPrimitive?.intOrNull
                            ?: return call.replyWithInvalidParams()
                        val b = call.params?.jsonObject["b"]?.jsonPrimitive?.intOrNull
                            ?: return call.replyWithInvalidParams()
                        val result = a / b
                        call.replyWithResult(buildJsonObject {
                            put("result", result)
                        })
                    }

                    else -> {
                        call.replyWithMethodNotFound()
                    }
                }
            }

            is JsonRpcCall.Notify -> {
                logger.info { "Received notify call : method=${call.method}, params=${call.params}" }
            }
        }
    }
}