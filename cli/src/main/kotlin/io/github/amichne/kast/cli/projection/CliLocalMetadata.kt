package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.CliProcessOutput
import io.github.amichne.kast.cli.CliTextDocument
import io.github.amichne.kast.cli.command.CliLocalCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val RUNTIME_ID_PATTERN = Regex("sha256:[0-9a-f]{64}")

enum class CliLocalMetadataFailure { PRODUCT_VERSION_INVALID, RUNTIME_ID_INVALID, SCHEMA_INVALID }

sealed interface CliLocalMetadataAdmission {
    data class Admitted(val metadata: CliLocalMetadata) : CliLocalMetadataAdmission
    data class Rejected(val failure: CliLocalMetadataFailure) : CliLocalMetadataAdmission
}

/** Exact process-local version and schema documents. Clikt owns help rendering. */
class CliLocalMetadata private constructor(
    private val version: CliTextDocument,
    private val schema: CliJsonDocument,
) {
    fun output(command: CliLocalCommand): CliProcessOutput = when (command) {
        CliLocalCommand.VERSION -> version
        CliLocalCommand.SCHEMA -> schema
    }

    companion object {
        /**
         * Proof transition: `String + String + String -> CliLocalMetadataAdmission`.
         *
         * Establishes non-blank product identity, canonical runtime identity, and canonical JSON
         * schema. Clikt generates help from the executable command graph.
         * [CliLocalMetadataFailure] is the closed expected failure. Raw resource text may leave
         * only at this installed-control metadata boundary.
         */
        fun admit(
            productVersion: String,
            runtimeIdentity: String,
            schema: String,
        ): CliLocalMetadataAdmission {
            if (productVersion.isBlank()) {
                return CliLocalMetadataAdmission.Rejected(
                    CliLocalMetadataFailure.PRODUCT_VERSION_INVALID,
                )
            }
            if (!RUNTIME_ID_PATTERN.matches(runtimeIdentity)) {
                return CliLocalMetadataAdmission.Rejected(
                    CliLocalMetadataFailure.RUNTIME_ID_INVALID,
                )
            }
            val schemaObject = try {
                Json.parseToJsonElement(schema) as? JsonObject
            } catch (_: RuntimeException) {
                null
            } ?: return CliLocalMetadataAdmission.Rejected(CliLocalMetadataFailure.SCHEMA_INVALID)
            return CliLocalMetadataAdmission.Admitted(
                CliLocalMetadata(
                    CliTextDocument.admitted(
                        "kast $productVersion (semantic runtime $runtimeIdentity)",
                    ),
                    CliJsonDocument.from(schemaObject),
                ),
            )
        }
    }
}
