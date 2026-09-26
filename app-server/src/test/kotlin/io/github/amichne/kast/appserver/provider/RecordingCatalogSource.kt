package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.installedKastCatalogFixture
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.CanonicalAgentToolDefinitions
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

internal class RecordingCatalogSource(
    private val schema: String,
    private val replacementSchema: String = schema,
) : KastCatalogSource {
    var reads = 0
        private set

    override fun read(): Refinement<String, KastQualificationFailure> {
        check(reads < 2) { "Unexpected catalog read" }
        reads += 1
        return Refinement.Refined(if (reads == 1) schema else replacementSchema)
    }
}

internal fun capabilitySchema(): String {
    val policy = JsonPrimitive(CanonicalAgentToolDefinitions.policy.text)
    val changeDescription = JsonPrimitive(CanonicalAgentToolDefinitions.changeApply.description.value)
    val source =
        checkNotNull(RecordingCatalogSource::class.java.getResource("/kast-provider-capability.json"))
            .readText()
            .replace("@POLICY@", policy.toString())
            .replace("@CHANGE_DESCRIPTION@", changeDescription.toString())
    val json = Json { ignoreUnknownKeys = true }
    val overrides =
        json.decodeFromString<KastCapabilityBoundary>(source).serverProjection.hostedBootstrap.tools.associateBy {
            it.name
        }
    val base = json.decodeFromString<KastCapabilityBoundary>(installedKastCatalogFixture())
    return json.encodeToString(
        base.copy(
            serverProjection =
                base.serverProjection.copy(
                    hostedBootstrap =
                        base.serverProjection.hostedBootstrap.copy(
                            tools = base.serverProjection.hostedBootstrap.tools.map { overrides[it.name] ?: it }
                        )
                )
        )
    )
}
