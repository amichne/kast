package io.github.amichne.kast.api.contract.compatibility

import io.github.amichne.kast.api.docs.DocField
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeIdentity(
    @DocField(description = "Implementation version of the runtime host.")
    val implementationVersion: RuntimeImplementationVersion,
    @DocField(description = "Backend kind hosting semantic operations.")
    val backendKind: RuntimeBackendKind,
)
