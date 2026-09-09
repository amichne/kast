package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.CoordinatorStatusProtocol
import io.github.amichne.kast.distribution.contract.IndexerTransportLimits
import io.github.amichne.kast.distribution.contract.WireRuntimeQualification
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationOperationalLimit
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSchemaDocument
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationScope
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationUnit
import io.github.amichne.kast.distribution.contract.configuration.KastConfigurationCatalogue
import io.github.amichne.kast.protocol.registry.OperationExecutionBudget
import kotlinx.serialization.json.Json

/** Final assembly imports owner values; it does not resolve environment, start hosts, or load providers. */
object InstalledConfigurationSchema {
    val document: ConfigurationSchemaDocument get() = ConfigurationSchemaDocument(
        parameters = KastConfigurationCatalogue.declarations,
        operationalLimits = buildList {
            for (budget in OperationExecutionBudget.entries) {
                add(limit("operation.${budget.name.lowercase()}.dispatch", ":protocol:registry", budget.operation.value, ConfigurationUnit.MILLISECONDS, "OperationExecutionBudget.${budget.name}.operation"))
                add(limit("operation.${budget.name.lowercase()}.invocation", ":protocol:registry", budget.invocation.value, ConfigurationUnit.MILLISECONDS, "OperationExecutionBudget.${budget.name}.invocation"))
            }
            add(limit("operation.local_qualification", ":protocol:registry", OperationExecutionBudget.LOCAL_QUALIFICATION.value, ConfigurationUnit.MILLISECONDS, "OperationExecutionBudget.LOCAL_QUALIFICATION"))
            add(limit("operation.workspace_readiness", ":protocol:registry", OperationExecutionBudget.WORKSPACE_READINESS.value, ConfigurationUnit.MILLISECONDS, "OperationExecutionBudget.WORKSPACE_READINESS"))
            add(limit("operation.provider_qualification", ":protocol:registry", OperationExecutionBudget.PROVIDER_QUALIFICATION.value, ConfigurationUnit.MILLISECONDS, "OperationExecutionBudget.PROVIDER_QUALIFICATION"))
            add(limit("transport.qualification.maximum_bytes", ":distribution:contract", WireRuntimeQualification.maximumBytes.toLong(), ConfigurationUnit.BYTES, "WireRuntimeQualification.maximumBytes"))
            add(limit("indexer.frame.maximum_bytes", ":distribution:contract", IndexerTransportLimits.maximumFrameBytes.toLong(), ConfigurationUnit.BYTES, "IndexerTransportLimits.maximumFrameBytes"))
            add(limit("indexer.connections.default", ":distribution:contract", IndexerTransportLimits.defaultConnections.toLong(), ConfigurationUnit.COUNT, "IndexerTransportLimits.defaultConnections", ConfigurationScope.WORKSPACE))
            add(limit("indexer.connections.maximum", ":distribution:contract", IndexerTransportLimits.maximumConnections.toLong(), ConfigurationUnit.COUNT, "IndexerTransportLimits.maximumConnections", ConfigurationScope.WORKSPACE))
            add(limit("coordinator.status.maximum_bytes", ":app-server", CoordinatorStatusProtocol.maximumMessageBytes.toLong(), ConfigurationUnit.BYTES, "CoordinatorStatusProtocol.maximumMessageBytes; generated maximum scalar widths and admitted worker count"))
            add(limit("coordinator.command.maximum_bytes", ":app-server", CoordinatorStatusProtocol.maximumCommandBytes.toLong(), ConfigurationUnit.BYTES, "CoordinatorStatusProtocol.maximumCommandBytes"))
            add(limit("broker.message.maximum_bytes", ":app-server", BrokerOperationalLimits.maximumMessageBytes.toLong(), ConfigurationUnit.BYTES, "BrokerOperationalLimits.maximumMessageBytes"))
            add(limit("broker.connections.maximum", ":app-server", BrokerOperationalLimits.maximumConnections.toLong(), ConfigurationUnit.COUNT, "BrokerOperationalLimits.maximumConnections", ConfigurationScope.INSTALLATION))
            add(limit("broker.upstream_startup", ":app-server", BrokerOperationalLimits.upstreamStartup.value, ConfigurationUnit.MILLISECONDS, "BrokerOperationalLimits.upstreamStartup", ConfigurationScope.HOST_PROFILE))
        }.sortedBy { it.key },
        sourceCoverage = "declared-process-saved-workspace-saved-installation-derived-jvm-and-operational-owner-contracts",
    )
    val encoded: String get() = json.encodeToString(ConfigurationSchemaDocument.serializer(), document) + "\n"
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    @JvmStatic fun main(arguments: Array<String>) {
        require(arguments.isEmpty()) { "configuration catalogue projection accepts no arguments" }
        print(encoded)
    }

    private fun limit(key: String, owner: String, value: Long, unit: ConfigurationUnit, authority: String, scope: ConfigurationScope = ConfigurationScope.REQUEST) =
        ConfigurationOperationalLimit(key, owner, value, unit, scope, authority)
}
