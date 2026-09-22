@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.amichne.kast.protocol.wire.presentation

import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.IndexSyncQualification
import io.github.amichne.kast.protocol.contract.IndexSyncRejection
import io.github.amichne.kast.protocol.contract.IndexSyncResult
import kotlinx.serialization.Serializable

object CanonicalIndexCliDocuments {
    fun project(outcome: OperationOutcome<IndexSyncResult, IndexSyncQualification, IndexSyncRejection>) =
        projectClosedOutcome(
            outcome,
            complete = { result, live ->
                completeFactory.create(
                    IndexSyncCompleteCliDocument(
                        CanonicalOperation.INDEX_SYNC.id.value,
                        "complete",
                        result.state.cliName(),
                        live = live,
                    )
                )
            },
            qualified = { result, qualification, live ->
                qualifiedFactory.create(
                    IndexSyncQualifiedCliDocument(
                        CanonicalOperation.INDEX_SYNC.id.value,
                        "qualified",
                        result.state.cliName(),
                        qualification.cliName(),
                        live = live,
                    )
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
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

@Serializable
private data class IndexSyncQualifiedCliDocument(
    val operation: String,
    val status: String,
    val state: String,
    val qualification: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val live: LiveReadCliEvidence? = null,
)

private val completeFactory = CanonicalJsonDocument.generated(IndexSyncCompleteCliDocument.serializer())
private val qualifiedFactory = CanonicalJsonDocument.generated(IndexSyncQualifiedCliDocument.serializer())
