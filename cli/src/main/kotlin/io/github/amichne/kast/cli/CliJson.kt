package io.github.amichne.kast.cli

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val cliJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    classDiscriminator = "type"
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
        /** Selects the generated serializer for one closed CLI document type. */
        internal fun <Value> generated(
            serializer: KSerializer<Value>,
        ): Factory<Value> = Factory(serializer)
    }

    /** A generated serializer bound to the CLI's sole configured JSON instance. */
    internal class Factory<Value>(
        private val serializer: KSerializer<Value>,
    ) {
        fun create(value: Value): CliJsonDocument = CliJsonDocument(
            cliJson.encodeToString(serializer, value),
        )
    }
}

internal enum class CliOpenJsonObjectFailure {
    MALFORMED,
    NOT_AN_OBJECT,
}

internal sealed interface CliOpenJsonObjectAdmission {
    data class Admitted(
        val value: CliOpenJsonObject,
    ) : CliOpenJsonObjectAdmission

    data class Rejected(
        val failure: CliOpenJsonObjectFailure,
    ) : CliOpenJsonObjectAdmission
}

/** One deliberately open JSON object admitted only for installed schema composition. */
@Serializable
@JvmInline
internal value class CliOpenJsonObject private constructor(
    internal val value: JsonObject,
) {
    fun document(): CliJsonDocument = openJsonObjectFactory.create(this)

    companion object {
        /**
         * Proof transition: `String -> CliOpenJsonObjectAdmission`.
         *
         * Establishes syntactically valid object-shaped JSON while preserving its deliberately
         * open fields. [CliOpenJsonObjectFailure] is the closed expected failure. The underlying
         * [JsonObject] may leave this type only when embedded in the installed schema document.
         */
        fun parse(raw: String): CliOpenJsonObjectAdmission {
            val element = try {
                cliJson.parseToJsonElement(raw)
            } catch (_: SerializationException) {
                return CliOpenJsonObjectAdmission.Rejected(CliOpenJsonObjectFailure.MALFORMED)
            }
            return if (element is JsonObject) {
                CliOpenJsonObjectAdmission.Admitted(CliOpenJsonObject(element))
            } else {
                CliOpenJsonObjectAdmission.Rejected(CliOpenJsonObjectFailure.NOT_AN_OBJECT)
            }
        }
    }
}

private val openJsonObjectFactory = CliJsonDocument.generated(CliOpenJsonObject.serializer())

/** Stable non-blank local metadata ready for stdout. */
class CliTextDocument private constructor(
    override val value: String,
) : CliProcessOutput {
    companion object {
        internal val commandRejected: CliTextDocument = CliTextDocument("command rejected")

        /**
         * Proof transition: `String -> CliTextDocumentAdmission`.
         *
         * Establishes a non-blank process text document. [CliTextDocumentFailure] is the closed
         * expected failure. Raw rendered text is admitted only at local-metadata or Clikt output
         * boundaries.
         */
        internal fun admit(value: String): CliTextDocumentAdmission = if (value.isBlank()) {
            CliTextDocumentAdmission.Rejected(CliTextDocumentFailure.BLANK)
        } else {
            CliTextDocumentAdmission.Admitted(CliTextDocument(value))
        }
    }
}

internal enum class CliTextDocumentFailure { BLANK }

internal sealed interface CliTextDocumentAdmission {
    data class Admitted(val document: CliTextDocument) : CliTextDocumentAdmission
    data class Rejected(val failure: CliTextDocumentFailure) : CliTextDocumentAdmission
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
