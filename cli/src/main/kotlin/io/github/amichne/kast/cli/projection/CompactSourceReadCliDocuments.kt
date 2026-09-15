@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.protocol.contract.SourceReadResult
import io.github.amichne.kast.protocol.wire.compactSourceDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal fun compactSourceComplete(result: SourceReadResult): CliJsonDocument =
    compactCompleteFactory.create(
        CompactSourceCompleteCliDocument(content = result.compactContent(), executionBudget = result.executionBudget)
    )

internal fun compactSourceQualified(
    result: SourceReadResult,
    qualification: SourceReadQualificationCliDocument,
): CliJsonDocument =
    compactQualifiedFactory.create(
        CompactSourceQualifiedCliDocument(
            content = result.compactContent(),
            qualification = qualification,
            executionBudget = result.executionBudget,
        )
    )

/** Array order explicitly makes returned source the first presentation section. */
@Serializable
internal sealed interface CompactSourceContentDocument {
    @Serializable
    @SerialName("source")
    data class Source(val text: io.github.amichne.kast.protocol.wire.CompactSourceTextDocument) :
        CompactSourceContentDocument

    @Serializable
    @SerialName("structure")
    data class Structure(
        val snapshot: io.github.amichne.kast.protocol.wire.CompactSourceSnapshotDocument,
        val region: io.github.amichne.kast.protocol.wire.CompactSourceRegionDocument,
        val selections: List<io.github.amichne.kast.protocol.wire.CompactSourceSelectionEntry>,
        val entities: List<io.github.amichne.kast.protocol.wire.CompactSourceEntityDocument>,
    ) : CompactSourceContentDocument
}

@Serializable
internal data class CompactSourceCompleteCliDocument(
    val operation: String = "source.read",
    val status: String = "complete",
    val format: io.github.amichne.kast.protocol.contract.SourceReadFormatDocument =
        io.github.amichne.kast.protocol.contract.SourceReadFormatDocument.COMPACT,
    val content: List<CompactSourceContentDocument>,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: io.github.amichne.kast.protocol.contract.ExecutionBudgetReport? = null,
)

@Serializable
internal data class CompactSourceQualifiedCliDocument(
    val operation: String = "source.read",
    val status: String = "qualified",
    val format: io.github.amichne.kast.protocol.contract.SourceReadFormatDocument =
        io.github.amichne.kast.protocol.contract.SourceReadFormatDocument.COMPACT,
    val content: List<CompactSourceContentDocument>,
    val qualification: SourceReadQualificationCliDocument,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("execution_budget")
    val executionBudget: io.github.amichne.kast.protocol.contract.ExecutionBudgetReport? = null,
)

private fun SourceReadResult.compactContent(): List<CompactSourceContentDocument> {
    val compact = compactSourceDocument()
    return listOf(
        CompactSourceContentDocument.Source(compact.text),
        CompactSourceContentDocument.Structure(compact.snapshot, compact.region, compact.selections, compact.entities),
    )
}

private val compactCompleteFactory = CliJsonDocument.generated(CompactSourceCompleteCliDocument.serializer())
private val compactQualifiedFactory = CliJsonDocument.generated(CompactSourceQualifiedCliDocument.serializer())
