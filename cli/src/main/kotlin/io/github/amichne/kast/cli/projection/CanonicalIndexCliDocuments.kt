package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.IndexSyncQualification
import io.github.amichne.kast.protocol.contract.IndexSyncRejection
import io.github.amichne.kast.protocol.contract.IndexSyncResult
import kotlinx.serialization.Serializable

internal object CanonicalIndexCliDocuments {
    fun project(
        outcome: OperationOutcome<IndexSyncResult, IndexSyncQualification, IndexSyncRejection>,
    ) = projectClosedOutcome(
        outcome,
        complete = { result ->
            completeFactory.create(
                IndexSyncCompleteCliDocument(
                    CanonicalOperation.INDEX_SYNC.id.value,
                    "complete",
                    result.state.cliName(),
                ),
            )
        },
        qualified = { result, qualification ->
            qualifiedFactory.create(
                IndexSyncQualifiedCliDocument(
                    CanonicalOperation.INDEX_SYNC.id.value,
                    "qualified",
                    result.state.cliName(),
                    qualification.cliName(),
                ),
            )
        },
        rejected = { rejection ->
            canonicalRejectedDocument(CanonicalOperation.INDEX_SYNC, rejection.cliName())
        },
    )
}

@Serializable
private data class IndexSyncCompleteCliDocument(
    val operation: String,
    val status: String,
    val state: String,
)

@Serializable
private data class IndexSyncQualifiedCliDocument(
    val operation: String,
    val status: String,
    val state: String,
    val qualification: String,
)

private val completeFactory =
    CliJsonDocument.generated(IndexSyncCompleteCliDocument.serializer())
private val qualifiedFactory =
    CliJsonDocument.generated(IndexSyncQualifiedCliDocument.serializer())
