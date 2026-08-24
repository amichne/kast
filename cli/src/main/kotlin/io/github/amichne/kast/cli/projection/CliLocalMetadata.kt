package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.CliOpenJsonObject
import io.github.amichne.kast.cli.CliOpenJsonObjectAdmission
import io.github.amichne.kast.cli.CliProcessOutput
import io.github.amichne.kast.cli.CliTextDocument
import io.github.amichne.kast.cli.CliTextDocumentAdmission
import io.github.amichne.kast.cli.command.CliLocalCommand

private val RUNTIME_ID_PATTERN = Regex("sha256:[0-9a-f]{64}")

enum class CliLocalMetadataFailure {
    PRODUCT_VERSION_INVALID,
    RUNTIME_ID_INVALID,
    SCHEMA_INVALID,
    VERSION_DOCUMENT_INVALID,
}

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
            val version = when (val admission = versionDocument(productVersion, runtimeIdentity)) {
                is CliLocalVersionAdmission.Admitted -> admission.document
                is CliLocalVersionAdmission.Rejected -> return CliLocalMetadataAdmission.Rejected(
                    admission.failure,
                )
            }
            val schemaObject = when (val admission = CliOpenJsonObject.parse(schema)) {
                is CliOpenJsonObjectAdmission.Admitted -> admission.value
                is CliOpenJsonObjectAdmission.Rejected -> return CliLocalMetadataAdmission.Rejected(
                    CliLocalMetadataFailure.SCHEMA_INVALID,
                )
            }
            return CliLocalMetadataAdmission.Admitted(
                CliLocalMetadata(version, schemaObject.document()),
            )
        }

        /**
         * Proof transition: `String + String + CliJsonDocument -> CliLocalMetadataAdmission`.
         *
         * Preserves a generated schema document while establishing the product and runtime
         * identities. [CliLocalMetadataFailure] is the closed expected failure. Raw identities
         * may leave only at installed metadata composition.
         */
        internal fun admit(
            productVersion: String,
            runtimeIdentity: String,
            schema: CliJsonDocument,
        ): CliLocalMetadataAdmission = when (
            val admission = versionDocument(productVersion, runtimeIdentity)
        ) {
            is CliLocalVersionAdmission.Admitted -> CliLocalMetadataAdmission.Admitted(
                CliLocalMetadata(admission.document, schema),
            )
            is CliLocalVersionAdmission.Rejected -> CliLocalMetadataAdmission.Rejected(
                admission.failure,
            )
        }

        /**
         * Proof transition: `String + String -> CliLocalVersionAdmission`.
         *
         * Establishes a non-blank product version and canonical runtime identity in one admitted
         * text document. [CliLocalMetadataFailure] is the closed expected failure. Raw identity
         * text is retained only in the returned process document.
         */
        private fun versionDocument(
            productVersion: String,
            runtimeIdentity: String,
        ): CliLocalVersionAdmission = when {
            productVersion.isBlank() -> CliLocalVersionAdmission.Rejected(
                CliLocalMetadataFailure.PRODUCT_VERSION_INVALID,
            )
            !RUNTIME_ID_PATTERN.matches(runtimeIdentity) -> CliLocalVersionAdmission.Rejected(
                CliLocalMetadataFailure.RUNTIME_ID_INVALID,
            )
            else -> when (
                val admission = CliTextDocument.admit(
                    "kast $productVersion (semantic runtime $runtimeIdentity)",
                )
            ) {
                is CliTextDocumentAdmission.Admitted ->
                    CliLocalVersionAdmission.Admitted(admission.document)
                is CliTextDocumentAdmission.Rejected -> CliLocalVersionAdmission.Rejected(
                    CliLocalMetadataFailure.VERSION_DOCUMENT_INVALID,
                )
            }
        }
    }
}

private sealed interface CliLocalVersionAdmission {
    data class Admitted(
        val document: CliTextDocument,
    ) : CliLocalVersionAdmission

    data class Rejected(
        val failure: CliLocalMetadataFailure,
    ) : CliLocalVersionAdmission
}
