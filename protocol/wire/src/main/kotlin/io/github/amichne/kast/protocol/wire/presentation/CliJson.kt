package io.github.amichne.kast.protocol.wire.presentation

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

private val cliJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    classDiscriminator = "type"
}

interface OutputDocument {
    val value: String
}

/** A canonical compact JSON document ready for the process output boundary. */
class CanonicalJsonDocument private constructor(override val value: String) : OutputDocument {
    companion object {
        /** Selects the generated serializer for one closed CLI document type. */
        fun <Value> generated(serializer: KSerializer<Value>): Factory<Value> = Factory(serializer)
    }

    /** A generated serializer bound to the CLI's sole configured JSON instance. */
    class Factory<Value>(private val serializer: KSerializer<Value>) {
        fun create(value: Value): CanonicalJsonDocument =
            CanonicalJsonDocument(cliJson.encodeToString(serializer, value))
    }
}

/** Exhaustive operation-outcome projection before process status selection. */
sealed interface ProjectedOperationOutcome {
    data class Complete(val document: CanonicalJsonDocument) : ProjectedOperationOutcome

    data class Qualified(val document: CanonicalJsonDocument) : ProjectedOperationOutcome

    data class Rejected(val document: CanonicalJsonDocument) : ProjectedOperationOutcome
}
