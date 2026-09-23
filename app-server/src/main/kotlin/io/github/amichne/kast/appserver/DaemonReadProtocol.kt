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
internal object DaemonReadProtocol {
    const val route = "/kast-read"
    const val version = 1
    const val maximumRequestBytes = BrokerOperationalLimits.maximumToolArgumentBytes + 4096
    const val maximumResponseBytes = BrokerOperationalLimits.maximumToolResultBytes + 4096
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }
}

/** Closed RPC presentations. Adding a public tool requires an explicit routing decision. */
@Serializable
internal enum class DaemonReadTool(val identity: PublicToolIdentity) {
    SEARCH_CLASSES(PublicToolIdentity.SEARCH_CLASSES),
    SEARCH_FUNCTIONS(PublicToolIdentity.SEARCH_FUNCTIONS),
    SEARCH_DECLARATIONS(PublicToolIdentity.SEARCH_DECLARATIONS),
    CHECK_DIAGNOSTICS(PublicToolIdentity.CHECK_DIAGNOSTICS),
    QUERY_SYMBOLS(PublicToolIdentity.QUERY_SYMBOLS);

    companion object {
        fun from(identity: PublicToolIdentity): DaemonReadTool =
            when (identity) {
                PublicToolIdentity.SEARCH_CLASSES -> SEARCH_CLASSES
                PublicToolIdentity.SEARCH_FUNCTIONS -> SEARCH_FUNCTIONS
                PublicToolIdentity.SEARCH_DECLARATIONS -> SEARCH_DECLARATIONS
                PublicToolIdentity.CHECK_DIAGNOSTICS -> CHECK_DIAGNOSTICS
                PublicToolIdentity.QUERY_SYMBOLS -> QUERY_SYMBOLS
            }
    }
}

@Serializable
internal data class DaemonReadRequest(
    val target: DaemonManagementTarget,
    val root: String,
    val tool: DaemonReadTool,
    /** Opaque only until the daemon re-admits the selected public tool's exact schema. */
    val arguments: JsonElement,
    @Required val version: Int = DaemonReadProtocol.version,
)

@Serializable
internal sealed interface DaemonReadResponse {
    // The native projector owns each outcome document's concrete schema; this envelope retains its variant.
    @Serializable
    @SerialName("complete")
    data class Complete(val target: DaemonManagementTarget, val root: String, val document: JsonElement) :
        DaemonReadResponse

    @Serializable
    @SerialName("qualified")
    data class Qualified(val target: DaemonManagementTarget, val root: String, val document: JsonElement) :
        DaemonReadResponse

    @Serializable
    @SerialName("operation_rejected")
    data class OperationRejected(val target: DaemonManagementTarget, val root: String, val document: JsonElement) :
        DaemonReadResponse

    @Serializable @SerialName("rejected") data class Rejected(val failure: DaemonReadFailure) : DaemonReadResponse
}

@Serializable
sealed interface DaemonReadFailure {
    @Serializable @SerialName("protocol") data class Protocol(val reason: DaemonReadProtocolFailure) : DaemonReadFailure

    @Serializable @SerialName("root") data class Root(val reason: CanonicalRootFailure) : DaemonReadFailure

    @Serializable @SerialName("input") data class Input(val reason: DaemonReadInputFailure) : DaemonReadFailure

    @Serializable
    @SerialName("preparation")
    data class Preparation(val reason: WorkspacePreparationFailure) : DaemonReadFailure

    @Serializable
    @SerialName("workspace")
    data class Workspace(val id: String, val cause: WorkspaceDemandCause) : DaemonReadFailure

    @Serializable @SerialName("host") data class Host(val reason: ExistingIdeFailure) : DaemonReadFailure
}

@Serializable
enum class DaemonReadProtocolFailure {
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
sealed interface DaemonReadInputFailure {
    @Serializable @SerialName("schema_mismatch") data object SchemaMismatch : DaemonReadInputFailure

    @Serializable @SerialName("schema_rejected") data object SchemaRejected : DaemonReadInputFailure

    @Serializable @SerialName("syntax_rejected") data object SyntaxRejected : DaemonReadInputFailure

    @Serializable
    @SerialName("parameter")
    data class Parameter(
        val parameter: io.github.amichne.kast.appserver.query.PublicToolParameter,
        val rule: io.github.amichne.kast.appserver.query.PublicToolRule,
    ) : DaemonReadInputFailure
}

internal class DaemonRead(
    private val target: DaemonManagementTarget,
    private val available: () -> Boolean,
    private val demand: WorkspaceDemand,
) {
    suspend fun execute(request: DaemonReadRequest): DaemonReadResponse {
        fun reject(reason: DaemonReadProtocolFailure) = DaemonReadResponse.Rejected(DaemonReadFailure.Protocol(reason))
        if (request.version != DaemonReadProtocol.version) return reject(DaemonReadProtocolFailure.UNSUPPORTED_VERSION)
        if (request.target != target) return reject(DaemonReadProtocolFailure.IDENTITY_REJECTED)
        if (!available()) return reject(DaemonReadProtocolFailure.LIFECYCLE_TRANSITION)
        val root =
            when (val admitted = admitReadRoot(request.root)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return DaemonReadResponse.Rejected(admitted.failure)
            }
        val admitted =
            when (val input = PublicToolContract.admit(request.tool.identity, request.arguments)) {
                is Refinement.Refined -> input.value
                is Refinement.Rejected ->
                    return DaemonReadResponse.Rejected(DaemonReadFailure.Input(input.failure.wire()))
            }
        val prepared =
            when (
                val preparation =
                    when (val canonical = admitted.canonical) {
                        is PublicToolCanonical.Query ->
                            canonicalCliRequestPreparers().queryRun.prepare(canonical.request)
                        is PublicToolCanonical.Diagnostics ->
                            canonicalCliRequestPreparers().diagnosticCheck.prepare(canonical.request)
                    }
            ) {
                is OperationPreparation.Prepared -> preparation.request
                is OperationPreparation.Rejected -> return reject(DaemonReadProtocolFailure.RESPONSE_REJECTED)
            }
        if (prepared.operation != request.tool.identity.operation)
            return reject(DaemonReadProtocolFailure.OPERATION_UNSUPPORTED)
        val read =
            when (val operation = ExistingIdeOperation.Read.admit(prepared)) {
                is Refinement.Refined -> operation.value
                is Refinement.Rejected -> return DaemonReadResponse.Rejected(DaemonReadFailure.Host(operation.failure))
            }
        return when (val result = demand.query(root, read)) {
            is WorkspaceDemandResult.Rejected -> DaemonReadResponse.Rejected(result.failure.wire())
            is WorkspaceDemandResult.Native -> result.exchange.wire(target, root)
        }
    }
}

private fun admitReadRoot(raw: String): Refinement<CanonicalRoot, DaemonReadFailure> {
    val discovered =
        try {
            FilesystemCanonicalRootDiscovery.discover(Path.of(raw))
        } catch (_: IllegalArgumentException) {
            return Refinement.Rejected(DaemonReadFailure.Protocol(DaemonReadProtocolFailure.INVALID_REQUEST))
        }
    return when (discovered) {
        is CanonicalRootDiscovery.Rejected -> Refinement.Rejected(DaemonReadFailure.Root(discovered.failure))
        is CanonicalRootDiscovery.Discovered ->
            if (discovered.root.path.toString() == raw) Refinement.Refined(discovered.root)
            else Refinement.Rejected(DaemonReadFailure.Protocol(DaemonReadProtocolFailure.INVALID_REQUEST))
    }
}

private fun PublicToolInputFailure.wire(): DaemonReadInputFailure =
    when (this) {
        PublicToolInputFailure.SchemaMismatch -> DaemonReadInputFailure.SchemaMismatch
        PublicToolInputFailure.SchemaRejected -> DaemonReadInputFailure.SchemaRejected
        PublicToolInputFailure.SyntaxRejected -> DaemonReadInputFailure.SyntaxRejected
        is PublicToolInputFailure.Parameter -> DaemonReadInputFailure.Parameter(parameter, rule)
    }

private fun WorkspaceDemandFailure.wire(): DaemonReadFailure =
    when (this) {
        is WorkspaceDemandFailure.Admission -> DaemonReadFailure.Preparation(failure)
        is WorkspaceDemandFailure.Operation -> DaemonReadFailure.Workspace(id.value.toString(), cause)
    }

private fun ExistingIdeExchange.wire(target: DaemonManagementTarget, root: CanonicalRoot): DaemonReadResponse {
    fun document(value: String): JsonElement = DaemonReadProtocol.json.parseToJsonElement(value)
    val path = root.path.toString()
    return when (this) {
        is ExistingIdeExchange.Received ->
            DaemonReadResponse.Rejected(DaemonReadFailure.Protocol(DaemonReadProtocolFailure.RESPONSE_REJECTED))
        is ExistingIdeExchange.HostRejected ->
            DaemonReadResponse.OperationRejected(target, path, document(this.document.value))
        is ExistingIdeExchange.Rejected -> DaemonReadResponse.Rejected(DaemonReadFailure.Host(failure))
        is ExistingIdeExchange.Semantic ->
            when (val projected = outcome) {
                is ProjectedOperationOutcome.Complete ->
                    DaemonReadResponse.Complete(target, path, document(projected.document.value))
                is ProjectedOperationOutcome.Qualified ->
                    DaemonReadResponse.Qualified(target, path, document(projected.document.value))
                is ProjectedOperationOutcome.Rejected ->
                    DaemonReadResponse.OperationRejected(target, path, document(projected.document.value))
            }
    }
}
