package io.github.amichne.kast.distribution.contract.configuration

import kotlinx.serialization.Serializable

/** One generated document shared by passive CLI inspection and staged installation metadata. */
@Serializable
data class ConfigurationSchemaDocument(
    val operation: String = "config-schema",
    val status: String = "complete",
    val parameters: List<ConfigurationDeclaration>,
    val operationalLimits: List<ConfigurationOperationalLimit> = emptyList(),
    val sourceCoverage: String = "declared-process-and-saved-input-contracts",
)
