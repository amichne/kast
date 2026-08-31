package io.github.amichne.kast.cli

internal sealed interface InstalledProtocolResourcesConstruction {
    data class Constructed(
        val resources: InstalledProtocolResources,
    ) : InstalledProtocolResourcesConstruction

    data class Rejected(
        val failure: InstalledCompositionFailure,
    ) : InstalledProtocolResourcesConstruction
}

/** Exact installed protocol bytes retained for schema and command projection. */
internal data class InstalledProtocolResources(
    val operationRegistry: String,
    val wireSchema: String,
)
