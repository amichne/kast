package io.github.amichne.kast.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val cliJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

/** One admitted process output document. */
sealed interface CliProcessOutput {
    val value: String
}

/** A canonical compact JSON document ready for the process output boundary. */
class CliJsonDocument private constructor(
    override val value: String,
) : CliProcessOutput {
    companion object {
        /**
         * Proof transition: `kotlinx.serialization.json.JsonObject -> CliJsonDocument`.
         *
         * Establishes canonical compact JSON serialization. This transition has no expected
         * failure because [JsonObject] is already structurally serializable. Raw text may leave
         * the returned type only at stdout or stderr.
         */
        fun from(value: JsonObject): CliJsonDocument = CliJsonDocument(
            cliJson.encodeToString(JsonObject.serializer(), value),
        )
    }
}

/** Stable non-blank local metadata ready for stdout. */
class CliTextDocument private constructor(
    override val value: String,
) : CliProcessOutput {
    companion object {
        internal fun admitted(value: String): CliTextDocument {
            require(value.isNotBlank())
            return CliTextDocument(value)
        }
    }
}

/** Exhaustive operation-outcome projection before process status selection. */
sealed interface ProjectedCliOutcome {
    data class Complete(
        val document: CliJsonDocument,
    ) : ProjectedCliOutcome

    data class Qualified(
        val document: CliJsonDocument,
    ) : ProjectedCliOutcome

    data class Rejected(
        val document: CliJsonDocument,
    ) : ProjectedCliOutcome
}
