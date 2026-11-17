package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandCause
import io.github.amichne.kast.appserver.runtime.WorkspacePreparationFailure
import io.github.amichne.kast.distribution.contract.ContainerFailure
import io.github.amichne.kast.distribution.managed.CanonicalRoot
import io.github.amichne.kast.distribution.managed.CanonicalRootFailure
import io.github.amichne.kast.protocol.contract.ChangeApplyRequest
import io.github.amichne.kast.protocol.contract.ChangePlanRequest
import io.github.amichne.kast.protocol.contract.ChangeRecoverRequest
import io.github.amichne.kast.protocol.contract.SourceReadAnchorDocumentFailure
import io.github.amichne.kast.protocol.contract.SourceReadRequest
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** One typed semantic operation on the installed daemon's owned Unix socket. */
internal object DaemonOperationProtocol {
    const val version = 4
    const val maximumRequestBytes = BrokerOperationalLimits.maximumToolArgumentBytes + 4096
    const val maximumResponseBytes = BrokerOperationalLimits.maximumToolResultBytes + 4096
    val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }
}

/**
 * Coordinator-owned semantic execution, after transport and exact workspace admission. It applies only the
 * coordinator's launch settings, so the ingress rejects every request whose stated runtime settings differ.
 */
fun interface CoordinatorOperationExecutor {
    suspend fun execute(root: CanonicalRoot, operation: DaemonOperationCall): DaemonOperationResult
}

/** Closed RPC presentations. Adding a public tool requires an explicit routing decision. */
@Serializable
internal enum class DaemonOperationTool(val identity: PublicToolIdentity) {
    CHECK_DIAGNOSTICS(PublicToolIdentity.CHECK_DIAGNOSTICS),
    QUERY_SYMBOLS(PublicToolIdentity.QUERY_SYMBOLS),
}

@Serializable
internal data class DaemonOperationRequest(
    val target: DaemonManagementTarget,
    val root: String,
    val selection: DaemonOperationSelection,
    /** The caller's environment-tier runtime settings; admitted before any root or workspace effect. */
    @Required val settings: DaemonRuntimeSettings = RequestedRuntimeSettings.Unstated.wire,
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
        val catalog: PublicToolCatalog = PublicToolCatalog.COMPLETE,
    ) : DaemonOperationSelection

    @Serializable
    @SerialName("canonical")
    data class Canonical(val read: DaemonCanonicalRead) : DaemonOperationSelection

    @Serializable @SerialName("change") data class Change(val action: DaemonChangeAction) : DaemonOperationSelection

    @Serializable @SerialName("workspace_status") data object WorkspaceStatus : DaemonOperationSelection

    @Serializable @SerialName("prepare_workspace") data object PrepareWorkspace : DaemonOperationSelection
}

/** A change call retains its exact typed plan, application or recovery request. */
@Serializable
sealed interface DaemonChangeAction {
    @Serializable @SerialName("plan") data class Plan(val request: ChangePlanRequest) : DaemonChangeAction

    @Serializable @SerialName("apply") data class Apply(val request: ChangeApplyRequest) : DaemonChangeAction

    @Serializable @SerialName("recover") data class Recover(val request: ChangeRecoverRequest) : DaemonChangeAction
}

/** The canonical request keeps its concrete type across the RPC boundary. */
@Serializable
sealed interface DaemonCanonicalRead {
    @Serializable @SerialName("source_read") data class SourceRead(val request: SourceReadRequest) : DaemonCanonicalRead
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

    @Serializable
    @SerialName("managed_workspace")
    data class ManagedWorkspace(val reason: ContainerFailure) : DaemonOperationFailure

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

    @Serializable
    @SerialName("constraint")
    data class Constraint(
        val firstParameter: io.github.amichne.kast.appserver.query.PublicToolParameter,
        val remainingParameters: Set<io.github.amichne.kast.appserver.query.PublicToolParameter>,
        val rule: io.github.amichne.kast.appserver.query.PublicToolRule,
    ) : DaemonOperationInputFailure

    @Serializable
    @SerialName("source_anchor")
    data class SourceAnchor(val cause: SourceReadAnchorDocumentFailure) : DaemonOperationInputFailure

    /** The request's runtime settings were malformed, not governed by this route, or conflict with its execution. */
    @Serializable
    @SerialName("runtime_settings")
    data class RuntimeSettings(val reason: DaemonRuntimeSettingsFailure) : DaemonOperationInputFailure
}
