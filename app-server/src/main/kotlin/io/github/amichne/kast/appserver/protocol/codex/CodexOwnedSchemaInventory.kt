package io.github.amichne.kast.appserver.protocol.codex

import io.github.amichne.kast.kernel.Refinement
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Complete exact-name schema inventory, with the removed rollback route admitted only as a pair. */
internal class CodexOwnedSchemaInventory private constructor(val documents: Map<CodexOwnedSchema, JsonObject>) {
    companion object {
        fun admit(
            files: List<CollectedCodexSchema>
        ): Refinement<CodexOwnedSchemaInventory, CodexProtocolQualificationFailure> {
            val documents = linkedMapOf<CodexOwnedSchema, JsonObject>()
            CodexOwnedSchema.entries.forEach { required ->
                val matches = files.filter { file ->
                    Path.of(file.relativePath).fileName.toString() == required.fileName
                }
                if (matches.isEmpty()) {
                    if (required in legacyRollbackSchemas) return@forEach
                    return Refinement.Rejected(CodexProtocolQualificationFailure.MISSING_REQUIRED_SCHEMA)
                }
                when (val parsed = parseExact(matches)) {
                    is Refinement.Refined -> documents[required] = parsed.value
                    is Refinement.Rejected -> return parsed
                }
            }
            if (documents.keys.intersect(legacyRollbackSchemas).size == 1) {
                return Refinement.Rejected(CodexProtocolQualificationFailure.MISSING_REQUIRED_SCHEMA)
            }
            return Refinement.Refined(CodexOwnedSchemaInventory(documents.toMap()))
        }

        private fun parseExact(
            matches: List<CollectedCodexSchema>
        ): Refinement<JsonObject, CodexProtocolQualificationFailure> {
            if (matches.size != 1) {
                return Refinement.Rejected(CodexProtocolQualificationFailure.AMBIGUOUS_REQUIRED_SCHEMA)
            }
            val document =
                try {
                    Json.parseToJsonElement(matches.single().bytes.toString(StandardCharsets.UTF_8)) as? JsonObject
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                } ?: return Refinement.Rejected(CodexProtocolQualificationFailure.INVALID_REQUIRED_SCHEMA)
            return Refinement.Refined(document)
        }
    }
}
