@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire

import io.github.amichne.kast.protocol.contract.BoundedProtocolList
import io.github.amichne.kast.protocol.contract.ProtocolText
import io.github.amichne.kast.protocol.contract.TraversalProgressDocument
import io.github.amichne.kast.protocol.contract.TraversalRunResult
import io.github.amichne.kast.protocol.contract.TraversalStrategyDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TraversalRunResultWireDocument(
    val snapshotRoot: String,
    val records: List<TraversalRecordWireDocument>,
    val progress: TraversalProgressDocument = TraversalProgressDocument(),
    val strategy: TraversalStrategyDocument = TraversalStrategyDocument.BreadthFirst,
    val partialExpansions: List<TraversalPartialExpansionWireDocument> = emptyList(),
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: io.github.amichne.kast.protocol.contract.ExecutionBudgetReport? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @kotlinx.serialization.SerialName("reference_acquisitions")
    val referenceAcquisitions: io.github.amichne.kast.protocol.contract.ReadReferenceAcquisitions? = null,
)

internal fun TraversalRunResult.toSymbolWireDocument() =
    TraversalRunResultWireDocument(
        snapshotRoot = snapshotRoot.value,
        records = records.values.map { it.toWireDocument() },
        progress = progress,
        strategy = strategy,
        partialExpansions = partialExpansions.values.map { it.toWireDocument() },
        executionBudget = executionBudget,
        referenceAcquisitions = referenceAcquisitions,
    )

/**
 * `TraversalRunResultWireDocument -> TraversalRunResult` establishes a bounded exact-symbol list; invalid raw fields
 * become `WireFailure.InvalidPayload` at this wire boundary.
 */
internal fun TraversalRunResultWireDocument.toContract(): WireDocumentConversion<TraversalRunResult> =
    combineConverted(
            ProtocolText.parse(snapshotRoot).toWireDocumentConversion(),
            records
                .convertEach { it.toContract() }
                .flatMapConverted { values -> BoundedProtocolList.create(values).toWireDocumentConversion() },
            { root, records ->
                TraversalRunResult(
                    root,
                    records,
                    progress,
                    strategy,
                    executionBudget = executionBudget,
                    referenceAcquisitions = referenceAcquisitions,
                )
            },
        )
        .flatMapConverted { result ->
            partialExpansions
                .convertEach { it.toContract() }
                .flatMapConverted { BoundedProtocolList.create(it).toWireDocumentConversion() }
                .mapConverted { result.copy(partialExpansions = it) }
        }
