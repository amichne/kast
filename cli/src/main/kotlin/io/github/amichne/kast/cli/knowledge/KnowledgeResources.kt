package io.github.amichne.kast.cli.knowledge

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Bounded file effects under an already canonical installed root. */
internal class KnowledgeResources(private val root: Path) {
    fun <T> read(resource: KnowledgeResourcePath, serializer: KSerializer<T>): KnowledgeAdmission<T> {
        val candidate = root.resolve(resource.value)
        val bytes =
            try {
                if (Files.isSymbolicLink(candidate) || candidate.toRealPath() != candidate) {
                    return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.RESOURCE_REJECTED)
                }
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.RESOURCE_UNAVAILABLE)
                }
                Files.newInputStream(candidate).use { it.readNBytes(MAX_KNOWLEDGE_RESOURCE_BYTES + 1) }
            } catch (_: IOException) {
                return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.RESOURCE_UNAVAILABLE)
            } catch (_: SecurityException) {
                return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.RESOURCE_UNAVAILABLE)
            }
        if (bytes.size > MAX_KNOWLEDGE_RESOURCE_BYTES) {
            return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.RESOURCE_TOO_LARGE)
        }
        val text =
            try {
                StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                return KnowledgeAdmission.Rejected(KnowledgeLookupFailure.RESOURCE_REJECTED)
            }
        return try {
            KnowledgeAdmission.Accepted(JSON.decodeFromString(serializer, text))
        } catch (_: SerializationException) {
            KnowledgeAdmission.Rejected(KnowledgeLookupFailure.RESOURCE_REJECTED)
        } catch (_: IllegalArgumentException) {
            KnowledgeAdmission.Rejected(KnowledgeLookupFailure.RESOURCE_REJECTED)
        }
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }
    }
}
