package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/** Only finite labels, bounded counts and existing host correlation cross the log boundary. */
internal fun publishHostedVfsEvidence(
    host: IdeReadHostLifetime,
    evidence: HostedVfsBatchEvidence,
    publish: (String) -> Unit,
) {
    val receipt: HostedVfsDiagnosticDocument =
        when (evidence) {
            HostedVfsBatchEvidence.OutsideRoot -> return
            is HostedVfsBatchEvidence.Observed ->
                HostedVfsDiagnosticDocument.Observed(
                    host.value.toString(),
                    evidence.counts.map { count ->
                        HostedVfsCountDocument(
                            kind = count.coordinate.kind,
                            origin = count.coordinate.origin,
                            category = count.coordinate.category,
                            paths = count.paths.value,
                        )
                    },
                )
            is HostedVfsBatchEvidence.Rejected ->
                HostedVfsDiagnosticDocument.Rejected(host.value.toString(), evidence.failure)
        }
    publish(Json.encodeToString(receipt))
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("outcome")
internal sealed interface HostedVfsDiagnosticDocument {
    @Serializable
    @SerialName("observed")
    data class Observed(
        val host: String,
        val counts: List<HostedVfsCountDocument>,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) val event: String = "kast_hosted_vfs",
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) val pathCategoriesAreSyntactic: Boolean = true,
    ) : HostedVfsDiagnosticDocument

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        val host: String,
        val failure: HostedVfsObservationFailure,
        @EncodeDefault(EncodeDefault.Mode.ALWAYS) val event: String = "kast_hosted_vfs",
    ) : HostedVfsDiagnosticDocument
}

@Serializable
internal data class HostedVfsCountDocument(
    val kind: HostedVfsEventKind,
    val origin: HostedVfsEventOrigin,
    val category: HostedVfsPathCategory,
    val paths: Int,
)
