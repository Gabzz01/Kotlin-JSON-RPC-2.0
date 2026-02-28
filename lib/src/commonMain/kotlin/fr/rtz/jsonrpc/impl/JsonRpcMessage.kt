package fr.rtz.jsonrpc.impl

import fr.rtz.jsonrpc.JsonRpcException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * JSON RPC 2.0 objects definition
 */
@Serializable(JsonRpcMessageSerializer::class)
internal sealed class JsonRpcMessage {

    @SerialName("jsonrpc")
    val jsonrpc: String = JSON_RPC_VERSION

    companion object {
        private const val JSON_RPC_VERSION = "2.0"
    }

    @Serializable
    sealed class Request : JsonRpcMessage() {

        @SerialName("method")
        abstract val method: String

        @SerialName("params")
        abstract val params: JsonElement?

        @Serializable
        data class Standard(
            @SerialName("id")
            val id: String,
            override val method: String,
            override val params: JsonElement?,
        ) : Request() {
            override fun toString(): String {
                return "JsonRpcRequestMessage(id=$id, method=$method)"
            }
        }

        @Serializable
        data class Notify(
            override val method: String,
            override val params: JsonElement?,
        ) : Request() {
            override fun toString(): String {
                return "JsonRpcNotifyMessage(method=$method)"
            }
        }

    }

    @Serializable
    sealed class Response : JsonRpcMessage() {

        @SerialName("id")
        abstract val id: String?

        @Serializable
        data class Result(
            override val id: String? = null,
            @Serializable
            val result: JsonElement
        ) : Response() {
            override fun toString(): String {
                return "JsonRpcSuccessResponseMessage(id=$id)"
            }
        }

        @Serializable
        data class Error(
            override val id: String? = null,
            @SerialName("code")
            val code: JsonRpcException.Code,
            @SerialName("message")
            val message: String?,
            @SerialName("data")
            val data: JsonElement?
        ) : Response() {
            override fun toString(): String {
                return "JsonRpcErrorResponseMessage(id=$id, code=$code)"
            }
        }
    }
}
