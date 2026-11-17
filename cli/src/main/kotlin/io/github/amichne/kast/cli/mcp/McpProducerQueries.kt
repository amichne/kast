package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.direct.DirectToolDocument
import io.github.amichne.kast.cli.generatedRequestSchema
import io.github.amichne.kast.cli.publicshell.CliAction
import io.github.amichne.kast.cli.publicshell.CliBudget
import io.github.amichne.kast.cli.publicshell.CliEnvelope
import io.github.amichne.kast.cli.publicshell.CliExpandRelation
import io.github.amichne.kast.cli.publicshell.CliOutput
import io.github.amichne.kast.cli.publicshell.CliRelation
import io.github.amichne.kast.cli.publicshell.CliRetention
import io.github.amichne.kast.cli.publicshell.CliSource
import io.github.amichne.kast.cli.publicshell.CliTerminal
import io.github.amichne.kast.protocol.contract.ImpactWitnessSectionDocument
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

private const val DISCOVER = "producer_invocations"
private const val IMPACT = "impact"
private const val QUERY = "query_symbols"
private val producerJson = Json { explicitNulls = false }

internal fun producerTools(catalog: List<DirectToolDocument>, selected: PublicToolCatalog): List<DirectToolDocument> {
    if (selected != PublicToolCatalog.COMPLETE) return emptyList()
    val query = catalog.singleOrNull { it.name == QUERY } ?: return emptyList()
    return listOf(
        query.copy(
            name = DISCOVER,
            description =
                "Find Kotlin calls to an issued callable. Pass its output row’s producerSeed unchanged to impact.",
            inputSchema = generatedRequestSchema(ProducerDiscovery.serializer()),
        ),
        query.copy(
            name = IMPACT,
            description =
                "Trace an issued producerSeed in this workspace. Return findings with their qualifications.",
            inputSchema = generatedRequestSchema(ImpactSelection.serializer()),
        ),
    )
}

internal fun McpToolCall.asProducerQuery(): McpToolCall {
    val input = arguments ?: emptyMcpArguments
    return when (name) {
        DISCOVER -> {
            val discovery = producerJson.decodeFromJsonElement(ProducerDiscovery.serializer(), input)
            val request =
                CliAction.Run(
                    source = CliSource.References(listOf(discovery.callable)),
                    steps = listOf(CliExpandRelation(CliRelation.CALLERS)),
                    terminalStep = CliTerminal.Emit(CliOutput.Occurrences),
                    retention = CliRetention.RETAIN,
                    executionBudget = discovery.executionBudget,
                )
            McpToolCall(QUERY, producerJson.encodeToJsonElement(CliEnvelope(request)).jsonObject)
        }
        IMPACT -> {
            val selection = producerJson.decodeFromJsonElement(ImpactSelection.serializer(), input)
            if (selection.producerSeed.candidateRef == null) throw SerializationException("Missing issued candidateRef")
            val request =
                CliAction.Run(
                    source = CliSource.Impact(seeds = listOf(selection.producerSeed)),
                    steps = null,
                    terminalStep = CliTerminal.Emit(CliOutput.Witness(selection.section)),
                    retention = CliRetention.RETAIN,
                    executionBudget = selection.executionBudget,
                )
            McpToolCall(QUERY, producerJson.encodeToJsonElement(CliEnvelope(request)).jsonObject)
        }
        else -> this
    }
}

@Serializable private data class ProducerDiscovery(val callable: String, val executionBudget: CliBudget? = null)

@Serializable
private data class ImpactSelection(
    val producerSeed: QueryImpactProducerDocument,
    val section: ImpactWitnessSectionDocument = ImpactWitnessSectionDocument.FINDINGS,
    val executionBudget: CliBudget? = null,
)
