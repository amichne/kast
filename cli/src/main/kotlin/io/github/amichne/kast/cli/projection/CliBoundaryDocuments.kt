package io.github.amichne.kast.cli.projection

import io.github.amichne.kast.cli.CliBoundaryExitStatus
import io.github.amichne.kast.cli.CliJsonDocument
import io.github.amichne.kast.cli.CliTextDocument
import io.github.amichne.kast.cli.command.CliCommandFailure
import io.github.amichne.kast.cli.command.outputReason
import kotlinx.serialization.Serializable

internal object CliBoundaryDocuments {
    fun brokerStopped(): CliJsonDocument =
        brokerFactory.create(BrokerStoppedDocument("broker serve", "complete", "stopped"))

    fun boundaryRejected(status: CliBoundaryExitStatus, reason: String): CliJsonDocument =
        boundaryFactory.create(BoundaryRejectedDocument("rejected", status.name.lowercase(), reason))

    fun usageRejected(failure: CliCommandFailure, diagnostic: CliTextDocument): CliJsonDocument =
        usageFactory.create(UsageRejectedDocument("rejected", "usage", failure.outputReason(), diagnostic.value))
}

@Serializable private data class BrokerStoppedDocument(val command: String, val status: String, val broker: String)

@Serializable private data class BoundaryRejectedDocument(val status: String, val boundary: String, val reason: String)

@Serializable
private data class UsageRejectedDocument(
    val status: String,
    val boundary: String,
    val reason: String,
    val diagnostic: String,
)

private val brokerFactory = CliJsonDocument.generated(BrokerStoppedDocument.serializer())
private val boundaryFactory = CliJsonDocument.generated(BoundaryRejectedDocument.serializer())
private val usageFactory = CliJsonDocument.generated(UsageRejectedDocument.serializer())
