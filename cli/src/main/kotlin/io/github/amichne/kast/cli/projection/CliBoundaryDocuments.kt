package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.appserver.query.PublicToolInputFailure
import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliTextDocument
import io.github.amichne.kast.cli.command.CliCommandFailure
import io.github.amichne.kast.cli.command.outputReason
import io.github.amichne.kast.cli.rpc.QuerySessionRejectionDetail
import io.github.amichne.kast.cli.rpc.publicToolInputRejectionDetail
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import kotlinx.serialization.Serializable

internal object CliBoundaryDocuments {
    val boundarySerializer: kotlinx.serialization.KSerializer<*> = BoundaryRejectedDocument.serializer()

    fun boundaryRejected(status: CliBoundaryExitStatus, reason: String): CanonicalJsonDocument =
        boundaryFactory.create(BoundaryRejectedDocument("rejected", status.name.lowercase(), reason))

    fun publicInputRejected(failure: PublicToolInputFailure): CanonicalJsonDocument =
        boundaryFactory.create(
            BoundaryRejectedDocument(
                "rejected",
                "usage",
                "invalid-public-tool-arguments",
                publicToolInputRejectionDetail(failure),
            )
        )

    fun usageRejected(failure: CliCommandFailure, diagnostic: CliTextDocument): CanonicalJsonDocument =
        usageFactory.create(UsageRejectedDocument("rejected", "usage", failure.outputReason(), diagnostic.value))
}

@Serializable
private data class BoundaryRejectedDocument(
    val status: String,
    val boundary: String,
    val reason: String,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val detail: QuerySessionRejectionDetail? = null,
)

@Serializable
private data class UsageRejectedDocument(
    val status: String,
    val boundary: String,
    val reason: String,
    val diagnostic: String,
)

private val boundaryFactory = CanonicalJsonDocument.generated(BoundaryRejectedDocument.serializer())
private val usageFactory = CanonicalJsonDocument.generated(UsageRejectedDocument.serializer())
