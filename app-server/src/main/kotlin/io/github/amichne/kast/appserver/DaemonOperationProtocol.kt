package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRootFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.HostedMutationOperation
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationFailure
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolInspectRequest
import io.github.amichne.kast.protocol.contract.TraversalRunRequest
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** One typed semantic operation on the installed daemon's owned Unix socket. */
internal object DaemonOperationProtocol {
    const val version = 2
    const val maximumRequestBytes = BrokerOperationalLimits.maximumToolArgumentBytes + 4096
    const val maximumResponseBytes = BrokerOperationalLimits.maximumToolResultBytes + 4096
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }
}

/** Closed RPC presentations. Adding a public tool requires an explicit routing decision. */
@Serializable
internal enum class DaemonOperationTool(val identity: PublicToolIdentity) {
    CHECK_DIAGNOSTICS(PublicToolIdentity.CHECK_DIAGNOSTICS),
    QUERY_SYMBOLS(PublicToolIdentity.QUERY_SYMBOLS);

    companion object {
        fun from(identity: PublicToolIdentity): DaemonOperationTool =
            when (identity) {
                PublicToolIdentity.CHECK_DIAGNOSTICS -> CHECK_DIAGNOSTICS
                PublicToolIdentity.QUERY_SYMBOLS -> QUERY_SYMBOLS
            }
    }
}

@Serializable
internal data class DaemonOperationRequest(
    val target: DaemonManagementTarget,
    val root: String,
    val selection: DaemonOperationSelection,
    @Required val version: Int = DaemonOperationProtocol.version,
)

@Serializable
internal sealed interface DaemonOperationSelection {
    @Serializable
    @SerialName("public_tool")
    data class PublicTool(
        val tool: DaemonOperationTool,
        /** Opaque only until the daemon re-admits the selected public tool's exact schema. */
        val arguments: JsonElement,
    ) : DaemonOperationSelection

    @Serializable
    @SerialName("canonical")
    data class Canonical(val read: DaemonCanonicalRead) : DaemonOperationSelection

    @Serializable @SerialName("change") data class Change(val action: DaemonChangeAction) : DaemonOperationSelection
}

/** A change call retains its request or approval stage without an untyped mode flag. */
@Serializable
sealed interface DaemonChangeAction {
    @Serializable @SerialName("plan") data class Plan(val request: ChangePlanRequest) : DaemonChangeAction

    @Serializable
    @SerialName("prepare")
    data class Prepare(val kind: HostedMutationOperation, val identity: String) : DaemonChangeAction

    @Serializable
    @SerialName("apply")
    data class Apply(val request: ChangeApplyRequest, val assertion: String) : DaemonChangeAction

    @Serializable
    @SerialName("recover")
    data class Recover(val request: ChangeRecoverRequest, val assertion: String) : DaemonChangeAction
}

internal fun DaemonChangeAction.operation(): CanonicalOperation =
    when (this) {
        is DaemonChangeAction.Plan -> CanonicalOperation.CHANGE_PLAN
        is DaemonChangeAction.Prepare -> kind.canonical
        is DaemonChangeAction.Apply -> CanonicalOperation.CHANGE_APPLY
        is DaemonChangeAction.Recover -> CanonicalOperation.CHANGE_RECOVER
    }

/** The canonical request keeps its concrete type across the RPC boundary. */
@Serializable
sealed interface DaemonCanonicalRead {
    @Serializable
    @SerialName("symbol_discover")
    data class SymbolDiscover(val request: SymbolDiscoverRequest) : DaemonCanonicalRead

    @Serializable
    @SerialName("symbol_inspect")
    data class SymbolInspect(val request: SymbolInspectRequest) : DaemonCanonicalRead

    @Serializable @SerialName("source_read") data class SourceRead(val request: SourceReadRequest) : DaemonCanonicalRead

    @Serializable
    @SerialName("traversal_run")
    data class TraversalRun(val request: TraversalRunRequest) : DaemonCanonicalRead
}

internal fun DaemonCanonicalRead.operation(): CanonicalOperation =
    when (this) {
        is DaemonCanonicalRead.SymbolDiscover -> CanonicalOperation.SYMBOL_DISCOVER
        is DaemonCanonicalRead.SymbolInspect -> CanonicalOperation.SYMBOL_INSPECT
        is DaemonCanonicalRead.SourceRead -> CanonicalOperation.SOURCE_READ
        is DaemonCanonicalRead.TraversalRun -> CanonicalOperation.TRAVERSAL_RUN
    }

internal fun DaemonOperationSelection.operation(): CanonicalOperation =
    when (this) {
        is DaemonOperationSelection.PublicTool -> tool.identity.operation
        is DaemonOperationSelection.Canonical -> read.operation()
        is DaemonOperationSelection.Change -> action.operation()
    }

@Serializable
internal sealed interface DaemonOperationResponse {
    // The native projector owns each outcome document's concrete schema; this envelope retains its variant.
    @Serializable
    @SerialName("complete")
    data class Complete(val target: DaemonManagementTarget, val root: String, val document: JsonElement) :
        DaemonOperationResponse

    @Serializable
    @SerialName("qualified")
    data class Qualified(val target: DaemonManagementTarget, val root: String, val document: JsonElement) :
        DaemonOperationResponse

    @Serializable
    @SerialName("operation_rejected")
    data class OperationRejected(val target: DaemonManagementTarget, val root: String, val document: JsonElement) :
        DaemonOperationResponse

    @Serializable
    @SerialName("hosted")
    data class Hosted(val target: DaemonManagementTarget, val root: String, val document: JsonElement) :
        DaemonOperationResponse

    @Serializable
    @SerialName("rejected")
    data class Rejected(val failure: DaemonOperationFailure) : DaemonOperationResponse
}

@Serializable
sealed interface DaemonOperationFailure {
    @Serializable
    @SerialName("protocol")
    data class Protocol(val reason: DaemonOperationProtocolFailure) : DaemonOperationFailure

    @Serializable @SerialName("root") data class Root(val reason: CanonicalRootFailure) : DaemonOperationFailure

    @Serializable
    @SerialName("input")
    data class Input(val reason: DaemonOperationInputFailure) : DaemonOperationFailure

    @Serializable
    @SerialName("preparation")
    data class Preparation(val reason: WorkspacePreparationFailure) : DaemonOperationFailure

    @Serializable
    @SerialName("workspace")
    data class Workspace(val id: String, val cause: WorkspaceDemandCause) : DaemonOperationFailure

    @Serializable @SerialName("host") data class Host(val reason: ExistingIdeFailure) : DaemonOperationFailure
}

@Serializable
enum class DaemonOperationProtocolFailure {
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
sealed interface DaemonOperationInputFailure {
    @Serializable @SerialName("schema_mismatch") data object SchemaMismatch : DaemonOperationInputFailure

    @Serializable @SerialName("schema_rejected") data object SchemaRejected : DaemonOperationInputFailure

    @Serializable @SerialName("syntax_rejected") data object SyntaxRejected : DaemonOperationInputFailure

    @Serializable
    @SerialName("parameter")
    data class Parameter(
        val parameter: io.github.amichne.kast.appserver.query.PublicToolParameter,
        val rule: io.github.amichne.kast.appserver.query.PublicToolRule,
    ) : DaemonOperationInputFailure
}
