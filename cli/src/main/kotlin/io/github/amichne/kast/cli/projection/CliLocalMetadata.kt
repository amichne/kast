package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.CliLocalCommand
import io.github.amichne.kast.cli.CliProcessOutput
import io.github.amichne.kast.cli.CliTextDocument
import io.github.amichne.kast.cli.canonicalCliSyntaxes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val RUNTIME_ID_PATTERN = Regex("sha256:[0-9a-f]{64}")

enum class CliLocalMetadataFailure { PRODUCT_VERSION_INVALID, RUNTIME_ID_INVALID, SCHEMA_INVALID }

sealed interface CliLocalMetadataAdmission {
    data class Admitted(val metadata: CliLocalMetadata) : CliLocalMetadataAdmission
    data class Rejected(val failure: CliLocalMetadataFailure) : CliLocalMetadataAdmission
}

/** Exact process-local help, version, and schema documents. */
class CliLocalMetadata private constructor(
    private val help: CliTextDocument,
    private val version: CliTextDocument,
    private val schema: CliJsonDocument,
) {
    fun output(command: CliLocalCommand): CliProcessOutput = when (command) {
        CliLocalCommand.HELP -> help
        CliLocalCommand.VERSION -> version
        CliLocalCommand.SCHEMA -> schema
    }

    companion object {
        /**
         * Proof transition: `String + String + String -> CliLocalMetadataAdmission`.
         *
         * Establishes non-blank product identity, canonical runtime identity, canonical JSON
         * schema, and help generated from the executable command authority.
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
                    CliTextDocument.admitted(helpText()),
                    CliTextDocument.admitted(
                        "kast $productVersion (semantic runtime $runtimeIdentity)",
                    ),
                    CliJsonDocument.from(schemaObject),
                ),
            )
        }

        private fun helpText(): String = buildString {
            appendLine("Usage: kast <command> [options]")
            appendLine("       kast --help | --version | --schema")
            appendLine()
            appendLine("Semantic commands:")
            canonicalCliSyntaxes.forEach { syntax -> appendLine("  ${syntax.usage}") }
            appendLine()
            appendLine("Relation kinds: references, callers, callees, implementations, inheritors, overrides, type-uses")
            appendLine("Change intents: add-file, add-declaration, replace-declaration, rename-symbol")
            appendLine("Exit codes: 0 success, 2 usage, 3 root, 4 runtime, 5 transport, 6 wire, 7 projection, 8 operation, 9 bootstrap")
        }.trimEnd()
    }
}
