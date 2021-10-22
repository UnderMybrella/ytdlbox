package dev.brella.ytdlbox

import io.ktor.util.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object Base64ByteArraySerialiser: KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("dev.brella.ytdlbox.Base64ByteArray", PrimitiveKind.STRING)

    @OptIn(InternalAPI::class)
    override fun deserialize(decoder: Decoder): ByteArray =
        decoder.decodeString().decodeBase64Bytes()

    @OptIn(InternalAPI::class)
    override fun serialize(encoder: Encoder, value: ByteArray) =
        encoder.encodeString(value.encodeBase64())
}