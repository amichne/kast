package io.github.amichne.kast.appserver

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun coordinatorConfigurationIdentity(
    configuration: io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
): String {
    val values =
        configuration
            .childIdentityInputs(io.github.amichne.kast.distribution.contract.configuration.ConfigurationChild.BROKER)
            .filterKeys { it != "KAST_INDEXER_MAX_HEAP" && it != "KAST_CONFIGURATION_FILE" }
    val material =
        Json.encodeToString(
            CoordinatorIdentityInputs(values.toSortedMap().map { CoordinatorIdentityInput(it.key, it.value) })
        )
    return java.util.HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8)))
}

@Serializable private data class CoordinatorIdentityInputs(val inputs: List<CoordinatorIdentityInput>)

@Serializable private data class CoordinatorIdentityInput(val key: String, val value: String)
