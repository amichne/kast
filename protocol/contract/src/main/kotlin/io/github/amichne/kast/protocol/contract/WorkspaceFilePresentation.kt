@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**
 * The canonical workspace root of one presentation. A file below it is presented relative to it, as file inputs
 * require. Any other file, such as a library, stays absolute. The wire has no root, so its paths do not change.
 */
class PresentedWorkspaceRoot private constructor(private val prefix: String) {
    fun present(file: String): String =
        if (file.length > prefix.length && file.startsWith(prefix)) file.substring(prefix.length) else file

    /** Binds this root to one JSON configuration. */
    fun serializersModule(): SerializersModule = SerializersModule { contextual(Binding(this@PresentedWorkspaceRoot)) }

    /** Carries the root to each file serializer. The root itself is never a serialized value. */
    private class Binding(val root: PresentedWorkspaceRoot) : KSerializer<PresentedWorkspaceRoot> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("$SERIAL_PACKAGE.PresentedWorkspaceRoot", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: PresentedWorkspaceRoot): Unit = unsupported()

        override fun deserialize(decoder: Decoder): PresentedWorkspaceRoot = unsupported()

        private fun unsupported(): Nothing = throw SerializationException("A presentation root is not a document value")
    }

    companion object {
        /** The caller has already proved that [root] is one canonical absolute directory. */
        fun fromCanonical(root: String): PresentedWorkspaceRoot =
            PresentedWorkspaceRoot(if (root.endsWith('/')) root else "$root/")

        internal fun present(encoder: Encoder, file: String): String =
            when (val bound = encoder.serializersModule.getContextual(PresentedWorkspaceRoot::class)) {
                is Binding -> bound.root.present(file)
                else -> file
            }
    }
}

/** One workspace or library file path in a document. */
object WorkspaceFileSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor(WORKSPACE_FILE_SERIAL_NAME, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) =
        encoder.encodeString(PresentedWorkspaceRoot.present(encoder, value))

    override fun deserialize(decoder: Decoder): String = decoder.decodeString()
}

/** The same path where a contract already admits it as bounded text. */
object WorkspaceFileTextSerializer : KSerializer<ProtocolText> {
    override val descriptor: SerialDescriptor =
        SerialDescriptor(WORKSPACE_FILE_TEXT_SERIAL_NAME, ProtocolTextSerializer.descriptor)

    override fun serialize(encoder: Encoder, value: ProtocolText) =
        encoder.encodeString(PresentedWorkspaceRoot.present(encoder, value.value))

    override fun deserialize(decoder: Decoder): ProtocolText = ProtocolTextSerializer.deserialize(decoder)
}

private const val SERIAL_PACKAGE = "io.github.amichne.kast.protocol.contract"
const val WORKSPACE_FILE_SERIAL_NAME = "$SERIAL_PACKAGE.WorkspaceFile"
const val WORKSPACE_FILE_TEXT_SERIAL_NAME = "$SERIAL_PACKAGE.WorkspaceFileText"
