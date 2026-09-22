package io.github.amichne.kast.appserver.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class KastCatalogStage {
    SOURCE,
    DOCUMENT,
    PROJECTION,
    TOOL_METADATA,
    INPUT_SCHEMA,
    OUTPUT_SCHEMA,
}

/** One bounded qualification result; never includes schema text, arguments, paths or secrets. */
@Serializable
internal sealed interface KastCatalogObservation {
    @Serializable @SerialName("kast_catalog_admitted") data object Admitted : KastCatalogObservation

    @Serializable
    @SerialName("kast_catalog_rejected")
    data class Rejected(val stage: KastCatalogStage, val failure: KastQualificationFailure) : KastCatalogObservation
}

internal fun interface KastCatalogObserver {
    fun observe(event: KastCatalogObservation)
}

internal object JsonLineKastCatalogObserver : KastCatalogObserver {
    override fun observe(event: KastCatalogObservation) {
        System.err.println(Json.encodeToString(event))
    }
}
