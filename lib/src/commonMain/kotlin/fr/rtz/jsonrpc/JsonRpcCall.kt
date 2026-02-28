package fr.rtz.jsonrpc

import kotlinx.serialization.json.JsonElement

/**
 * Models a JSON RPC request object
 */
sealed interface JsonRpcCall {

    /**
     * A String containing the name of the method to be invoked. Method names that begin with the word rpc followed by
     * a period character (U+002E or ASCII 46) are reserved for rpc-internal methods and extensions and
     * MUST NOT be used for anything else.
     */
    val method: String

    /**
     * A Structured value that holds the parameter values to be used during the invocation of the method.
     * This member MAY be omitted.
     *
     * **Note:** Per the JSON RPC 2.0 specification, this value MUST be either a [kotlinx.serialization.json.JsonObject]
     * or a [kotlinx.serialization.json.JsonArray] when present. Passing other [JsonElement] subtypes
     * (e.g. [kotlinx.serialization.json.JsonPrimitive]) produces technically non-conformant messages;
     * the library does not enforce this constraint at runtime.
     */
    val params: JsonElement?

    /**
     * Request object without an "id" member. A Request object that is a Notification signifies the Client's lack of
     * interest in the corresponding Response object, and as such no Response object needs to be returned to the client.
     * The Server MUST NOT reply to a Notification, including those that are within a batch request.
     *
     * Notifications are not confirmable by definition, since they do not have a Response object to be returned.
     * As such, the Client would not be aware of any errors (like e.g. "Invalid params","Internal error").
     */
    interface Notify : JsonRpcCall

    /**
     * Standard Request Object which expects a reply.
     */
    interface ExpectsResponse : JsonRpcCall {

        /**
         * An identifier established by the Client that MUST contain a String, Number, or NULL value if included.
         * The value SHOULD normally not be Null and Numbers SHOULD NOT contain fractional parts.
         * The Server MUST reply with the same value in the Response object if included.
         * This member is used to correlate the context between the two objects.
         */
        val requestId: String

        /**
         * Replies with the [result]. Could be thought of as a 200 in the HTTP World
         */
        suspend fun replyWithResult(result: JsonElement)

        /**
         * Replies with [JsonRpcException.MethodNotFound]. Could be thought of as a 404 in the HTTP World
         */
        suspend fun replyWithMethodNotFound()

        /**
         * Replies with [JsonRpcException.InvalidParams]. Could be thought of as a 400 in the HTTP World
         */
        suspend fun replyWithInvalidParams()

        /**
         * Replies with [JsonRpcException.ServerError]. Could be thought of as a 500 in the HTTP World
         *
         * @param data Optional [JsonElement] providing additional error context, forwarded as the
         *   JSON RPC 2.0 `error.data` field.
         */
        suspend fun replyWithServerError(code: Int, message: String, data: JsonElement? = null)

    }

}