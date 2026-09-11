package io.github.amichne.kast.distribution.contract.configuration

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One generated document shared by passive CLI inspection and staged installation metadata. */
@Serializable
data class ConfigurationSchemaDocument(
    val operation: String = "config-schema",
    val status: String = "complete",
    val parameters: List<ConfigurationDeclaration>,
    val operationalLimits: List<ConfigurationOperationalLimit> = emptyList(),
    val sourceCoverage: String = "declared-process-and-saved-input-contracts",
)

object KastConfigurationSchema {
    val document: ConfigurationSchemaDocument
        get() = ConfigurationSchemaDocument(parameters = KastConfigurationCatalogue.declarations)

    val encoded: String
        get() = json.encodeToString(ConfigurationSchemaDocument.serializer(), document) + "\n"

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.isEmpty()) { "configuration catalogue projection accepts no arguments" }
        print(encoded)
    }
}
