@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.amichne.kast.api.contract

import io.github.amichne.kast.api.protocol.SCHEMA_VERSION
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.uuid.Uuid

@Serializable(with = RuntimeOpenProjectRootSerializer::class)
@JvmInline
value class RuntimeOpenProjectRoot private constructor(
    private val value: Path,
) {
    fun toJavaPath(): Path = value

    override fun toString(): String = value.toString()

    companion object {
        fun of(path: Path): RuntimeOpenProjectRoot {
            val canonical = path.toRealPath()
            require(Files.isDirectory(canonical)) { "Project root must be an existing directory" }
            return RuntimeOpenProjectRoot(canonical)
        }

        fun parse(raw: String): RuntimeOpenProjectRoot {
            val supplied = Path.of(raw)
            require(supplied.isAbsolute) { "Project root must be absolute" }
            return of(supplied).also { root ->
                require(root.toString() == raw) { "Project root must already be canonical" }
            }
        }
    }
}

object RuntimeOpenProjectRootSerializer : KSerializer<RuntimeOpenProjectRoot> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RuntimeOpenProjectRoot", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: RuntimeOpenProjectRoot) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): RuntimeOpenProjectRoot =
        RuntimeOpenProjectRoot.parse(decoder.decodeString())
}

@Serializable(with = RuntimeOpenProjectRequestIdSerializer::class)
@JvmInline
value class RuntimeOpenProjectRequestId private constructor(
    private val value: Uuid,
) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): RuntimeOpenProjectRequestId =
            RuntimeOpenProjectRequestId(Uuid.random())

        fun parse(raw: String): RuntimeOpenProjectRequestId =
            RuntimeOpenProjectRequestId(Uuid.parse(raw))
    }
}

object RuntimeOpenProjectRequestIdSerializer : KSerializer<RuntimeOpenProjectRequestId> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("RuntimeOpenProjectRequestId", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: RuntimeOpenProjectRequestId) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): RuntimeOpenProjectRequestId =
        RuntimeOpenProjectRequestId.parse(decoder.decodeString())
}

@Serializable
data class RuntimeOpenProjectRequest(
    val canonicalRoot: RuntimeOpenProjectRoot,
    val requestId: RuntimeOpenProjectRequestId,
)

@Serializable
enum class RuntimeOpenProjectResult {
    ALREADY_OPEN,
    OPENED_NEW_PROJECT,
}

@Serializable
data class RuntimeOpenProjectResponse(
    val result: RuntimeOpenProjectResult,
    val schemaVersion: Int = SCHEMA_VERSION,
)
