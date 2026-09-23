package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.CanonicalRootFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolInputFailure
import io.github.amichne.kast.appserver.runtime.WorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandFailure
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandResult
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationFailure
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.OperationPreparation
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import java.nio.file.Path
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** A single schema-bound read on the installed daemon's owned Unix socket. */
internal object DaemonQueryProtocol {
    const val route = "/kast-query"
    const val version = 1
    const val maximumRequestBytes = BrokerOperationalLimits.maximumToolArgumentBytes + 4096
    const val maximumResponseBytes = BrokerOperationalLimits.maximumToolResultBytes + 4096
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }
}

@Serializable
internal data class DaemonQueryRequest(
    val target: DaemonManagementTarget,
    val root: String,
    /** Opaque only until the daemon re-admits the exact public query_symbols schema. */
    val arguments: JsonElement,
    @Required val version: Int = DaemonQueryProtocol.version,
)

@Serializable
internal sealed interface DaemonQueryResponse {
    // The native query projector owns each outcome document's concrete schema; this envelope retains its variant.
    @Serializable
    @SerialName("complete")
    data class Complete(val target: DaemonManagementTarget, val root: String, val document: JsonElement) :
        DaemonQueryResponse

    @Serializable
    @SerialName("qualified")
    data class Qualified(val target: DaemonManagementTarget, val root: String, val document: JsonElement) :
        DaemonQueryResponse

    @Serializable
    @SerialName("operation_rejected")
    data class OperationRejected(val target: DaemonManagementTarget, val root: String, val document: JsonElement) :
        DaemonQueryResponse

    @Serializable @SerialName("rejected") data class Rejected(val failure: DaemonQueryFailure) : DaemonQueryResponse
}

@Serializable
sealed interface DaemonQueryFailure {
    @Serializable
    @SerialName("protocol")
    data class Protocol(val reason: DaemonQueryProtocolFailure) : DaemonQueryFailure

    @Serializable @SerialName("root") data class Root(val reason: CanonicalRootFailure) : DaemonQueryFailure

    @Serializable @SerialName("input") data class Input(val reason: DaemonQueryInputFailure) : DaemonQueryFailure

    @Serializable
    @SerialName("preparation")
    data class Preparation(val reason: WorkspacePreparationFailure) : DaemonQueryFailure

    @Serializable
    @SerialName("workspace")
    data class Workspace(val id: String, val cause: WorkspaceDemandCause) : DaemonQueryFailure

    @Serializable @SerialName("host") data class Host(val reason: ExistingIdeFailure) : DaemonQueryFailure
}

@Serializable
enum class DaemonQueryProtocolFailure {
    INVALID_REQUEST,
    UNSUPPORTED_VERSION,
    IDENTITY_REJECTED,
    LIFECYCLE_TRANSITION,
    OPERATION_UNSUPPORTED,
    RESPONSE_REJECTED,
    UNAVAILABLE,
    OUTCOME_UNOBSERVED,
    CAPACITY_EXCEEDED,
}

@Serializable
sealed interface DaemonQueryInputFailure {
    @Serializable @SerialName("schema_mismatch") data object SchemaMismatch : DaemonQueryInputFailure

    @Serializable @SerialName("schema_rejected") data object SchemaRejected : DaemonQueryInputFailure

    @Serializable @SerialName("syntax_rejected") data object SyntaxRejected : DaemonQueryInputFailure

    @Serializable
    @SerialName("parameter")
    data class Parameter(
        val parameter: io.github.amichne.kast.appserver.query.PublicToolParameter,
        val rule: io.github.amichne.kast.appserver.query.PublicToolRule,
    ) : DaemonQueryInputFailure
}

internal class DaemonQuery(
    private val target: DaemonManagementTarget,
    private val available: () -> Boolean,
    private val demand: WorkspaceDemand,
) {
    suspend fun execute(request: DaemonQueryRequest): DaemonQueryResponse {
        fun reject(reason: DaemonQueryProtocolFailure) =
            DaemonQueryResponse.Rejected(DaemonQueryFailure.Protocol(reason))
        if (request.version != DaemonQueryProtocol.version)
            return reject(DaemonQueryProtocolFailure.UNSUPPORTED_VERSION)
        if (request.target != target) return reject(DaemonQueryProtocolFailure.IDENTITY_REJECTED)
        if (!available()) return reject(DaemonQueryProtocolFailure.LIFECYCLE_TRANSITION)
        val root =
            when (val admitted = admitQueryRoot(request.root)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return DaemonQueryResponse.Rejected(admitted.failure)
            }
        val admitted =
            when (val input = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, request.arguments)) {
                is Refinement.Refined -> input.value
                is Refinement.Rejected ->
                    return DaemonQueryResponse.Rejected(DaemonQueryFailure.Input(input.failure.wire()))
            }
        val query =
            (admitted.canonical as? PublicToolCanonical.Query)?.request
                ?: return reject(DaemonQueryProtocolFailure.OPERATION_UNSUPPORTED)
        val prepared =
            when (val preparation = canonicalCliRequestPreparers().queryRun.prepare(query)) {
                is OperationPreparation.Prepared -> preparation.request
                is OperationPreparation.Rejected -> return reject(DaemonQueryProtocolFailure.RESPONSE_REJECTED)
            }
        val read =
            when (val operation = ExistingIdeOperation.Read.admit(prepared)) {
                is Refinement.Refined -> operation.value
                is Refinement.Rejected ->
                    return DaemonQueryResponse.Rejected(DaemonQueryFailure.Host(operation.failure))
            }
        return when (val result = demand.query(root, read)) {
            is WorkspaceDemandResult.Rejected -> DaemonQueryResponse.Rejected(result.failure.wire())
            is WorkspaceDemandResult.Native -> result.exchange.wire(target, root)
        }
    }
}

private fun admitQueryRoot(raw: String): Refinement<CanonicalRoot, DaemonQueryFailure> {
    val discovered =
        try {
            FilesystemCanonicalRootDiscovery.discover(Path.of(raw))
        } catch (_: IllegalArgumentException) {
            return Refinement.Rejected(DaemonQueryFailure.Protocol(DaemonQueryProtocolFailure.INVALID_REQUEST))
        }
    return when (discovered) {
        is CanonicalRootDiscovery.Rejected -> Refinement.Rejected(DaemonQueryFailure.Root(discovered.failure))
        is CanonicalRootDiscovery.Discovered ->
            if (discovered.root.path.toString() == raw) Refinement.Refined(discovered.root)
            else Refinement.Rejected(DaemonQueryFailure.Protocol(DaemonQueryProtocolFailure.INVALID_REQUEST))
    }
}

private fun PublicToolInputFailure.wire(): DaemonQueryInputFailure =
    when (this) {
        PublicToolInputFailure.SchemaMismatch -> DaemonQueryInputFailure.SchemaMismatch
        PublicToolInputFailure.SchemaRejected -> DaemonQueryInputFailure.SchemaRejected
        PublicToolInputFailure.SyntaxRejected -> DaemonQueryInputFailure.SyntaxRejected
        is PublicToolInputFailure.Parameter -> DaemonQueryInputFailure.Parameter(parameter, rule)
    }

private fun WorkspaceDemandFailure.wire(): DaemonQueryFailure =
    when (this) {
        is WorkspaceDemandFailure.Admission -> DaemonQueryFailure.Preparation(failure)
        is WorkspaceDemandFailure.Operation -> DaemonQueryFailure.Workspace(id.value.toString(), cause)
    }

private fun ExistingIdeExchange.wire(target: DaemonManagementTarget, root: CanonicalRoot): DaemonQueryResponse {
    fun document(value: String): JsonElement = DaemonQueryProtocol.json.parseToJsonElement(value)
    val path = root.path.toString()
    return when (this) {
        is ExistingIdeExchange.Received ->
            DaemonQueryResponse.Rejected(DaemonQueryFailure.Protocol(DaemonQueryProtocolFailure.RESPONSE_REJECTED))
        is ExistingIdeExchange.HostRejected ->
            DaemonQueryResponse.OperationRejected(target, path, document(this.document.value))
        is ExistingIdeExchange.Rejected -> DaemonQueryResponse.Rejected(DaemonQueryFailure.Host(failure))
        is ExistingIdeExchange.Semantic ->
            when (val projected = outcome) {
                is ProjectedOperationOutcome.Complete ->
                    DaemonQueryResponse.Complete(target, path, document(projected.document.value))
                is ProjectedOperationOutcome.Qualified ->
                    DaemonQueryResponse.Qualified(target, path, document(projected.document.value))
                is ProjectedOperationOutcome.Rejected ->
                    DaemonQueryResponse.OperationRejected(target, path, document(projected.document.value))
            }
    }
}
