package fr.rtz.jsonrpc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.jvm.JvmInline


/**
 * Exceptions defined by the protocol.
 *
 * @param code A Number that indicates the error type that occurred.
 * @param message SHOULD be limited to a concise single sentence.
 * @param data A primitive or structured value that contains additional information about the error.
 *   The value of this member is defined by the server. May be omitted.
 */
sealed class JsonRpcException(
    val code: Code,
    override val message: String,
    val data: JsonElement? = null,
) : Exception(message) {

    @JvmInline
    @Serializable
    value class Code(val value: Int) {

        companion object {
            val PARSE_ERROR = Code(-32700)
            val INVALID_REQUEST = Code(-32600)
            val METHOD_NOT_FOUND = Code(-32601)
            val INVALID_PARAMS = Code(-32602)
            val INTERNAL_ERROR = Code(-32603)
        }
    }

    /**
     * Invalid JSON was received by the server. An error occurred on the server while parsing the JSON text.
     */
    class ParseError(message: String, data: JsonElement? = null) : JsonRpcException(
        code = Code.PARSE_ERROR, message = message, data = data
    )

    /**
     * The JSON sent is not a valid Request object.
     */
    class InvalidRequest(message: String, data: JsonElement? = null) : JsonRpcException(
        code = Code.INVALID_REQUEST, message = message, data = data
    )

    /**
     * The method does not exist / is not available
     */
    class MethodNotFound(message: String, data: JsonElement? = null) : JsonRpcException(
        code = Code.METHOD_NOT_FOUND, message = message, data = data
    )

    /**
     * Invalid method parameter(s).
     */
    class InvalidParams(message: String, data: JsonElement? = null) : JsonRpcException(
        code = Code.INVALID_PARAMS, message = message, data = data
    )

    /**
     * Internal JSON-RPC error.
     */
    class InternalError(message: String, data: JsonElement? = null) : JsonRpcException(
        code = Code.INTERNAL_ERROR, message = message, data = data
    )

    /**
     * Reserved for implementation-defined server-errors.
     *
     * @param code Implementation defined error code which should be in the -32000 to -32099 range.
     */
    class ServerError(code: Int, message: String, data: JsonElement? = null) : JsonRpcException(
        code = Code(code), message = message, data = data
    )

}
