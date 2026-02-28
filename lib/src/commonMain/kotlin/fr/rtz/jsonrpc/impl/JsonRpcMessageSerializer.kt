package fr.rtz.jsonrpc.impl

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * [JsonRpcMessage] custom serializer that handles polymorphic serialization. As Kotlin native polymorphic serialization
 * strategy relies on the addition on a 'type' property, it cannot be used for this protocol implementation.
 */
@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
internal object JsonRpcMessageSerializer : KSerializer<JsonRpcMessage> {

    private const val METHOD = "method"
    private const val ID = "id"
    private const val RESULT = "result"

    override val descriptor: SerialDescriptor = buildSerialDescriptor(
        serialName = "JsonRpcMessage",
        kind = PolymorphicKind.SEALED
    ) {
        // Add descriptors for all possible subtypes
        element("Request.Standard", JsonRpcMessage.Request.Standard.serializer().descriptor)
        element("Request.Notify", JsonRpcMessage.Request.Notify.serializer().descriptor)
        element("Response.Result", JsonRpcMessage.Response.Result.serializer().descriptor)
        element("Response.Error", JsonRpcMessage.Response.Error.serializer().descriptor)
    }

    override fun serialize(encoder: Encoder, value: JsonRpcMessage) {
        when (value) {
            is JsonRpcMessage.Request.Standard -> JsonRpcMessage.Request.Standard.serializer().serialize(encoder, value)
            is JsonRpcMessage.Request.Notify -> JsonRpcMessage.Request.Notify.serializer().serialize(encoder, value)
            is JsonRpcMessage.Response.Error -> JsonRpcMessage.Response.Error.serializer().serialize(encoder, value)
            is JsonRpcMessage.Response.Result -> JsonRpcMessage.Response.Result.serializer().serialize(encoder, value)
        }
    }

    override fun deserialize(decoder: Decoder): JsonRpcMessage {
        val jsonObject = decoder.decodeSerializableValue(JsonObject.Companion.serializer())
        // Presence of "method" is the reliable way to distinguish requests from responses,
        // since "params" is optional per the JSON-RPC 2.0 spec.
        return if (jsonObject.containsKey(METHOD)) {
            if (jsonObject.containsKey(ID)) {
                Json.decodeFromJsonElement(JsonRpcMessage.Request.Standard.serializer(), jsonObject)
            } else {
                Json.decodeFromJsonElement(JsonRpcMessage.Request.Notify.serializer(), jsonObject)
            }
        } else {
            if (jsonObject.containsKey(RESULT)) {
                Json.decodeFromJsonElement(JsonRpcMessage.Response.Result.serializer(), jsonObject)
            } else {
                Json.decodeFromJsonElement(JsonRpcMessage.Response.Error.serializer(), jsonObject)
            }
        }
    }
}