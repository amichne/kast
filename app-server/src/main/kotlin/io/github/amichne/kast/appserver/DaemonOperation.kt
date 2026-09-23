package io.github.amichne.kast.appserver

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.HostedApprovalAssertion
import io.github.amichne.kast.appserver.ide.HostedMutationOperation
import io.github.amichne.kast.appserver.ide.HostedPlanIdentity
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.appserver.query.PublicToolInputFailure
import io.github.amichne.kast.appserver.runtime.WorkspaceDemand
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandFailure
import io.github.amichne.kast.appserver.runtime.WorkspaceDemandResult
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.presentation.OperationPreparation
import io.github.amichne.kast.protocol.wire.presentation.ProjectedOperationOutcome
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import java.nio.file.Path
import kotlinx.serialization.json.JsonElement

internal class DaemonOperation(
    private val target: DaemonManagementTarget,
    private val available: () -> Boolean,
    private val demand: WorkspaceDemand,
) {
    suspend fun execute(request: DaemonOperationRequest): DaemonOperationResponse {
        fun reject(reason: DaemonOperationProtocolFailure) =
            DaemonOperationResponse.Rejected(DaemonOperationFailure.Protocol(reason))
        if (request.version != DaemonOperationProtocol.version)
            return reject(DaemonOperationProtocolFailure.UNSUPPORTED_VERSION)
        if (request.target != target) return reject(DaemonOperationProtocolFailure.IDENTITY_REJECTED)
        if (!available()) return reject(DaemonOperationProtocolFailure.LIFECYCLE_TRANSITION)
        val root =
            when (val admitted = admitReadRoot(request.root)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return DaemonOperationResponse.Rejected(admitted.failure)
            }
        val native =
            when (val selection = request.selection) {
                is DaemonOperationSelection.Change ->
                    when (val admitted = selection.action.admitChange()) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return DaemonOperationResponse.Rejected(admitted.failure)
                    }
                is DaemonOperationSelection.PublicTool,
                is DaemonOperationSelection.Canonical ->
                    when (val admitted = selection.admitRead()) {
                        is Refinement.Refined -> admitted.value
                        is Refinement.Rejected -> return DaemonOperationResponse.Rejected(admitted.failure)
                    }
            }
        return when (val result = demand.query(root, native)) {
            is WorkspaceDemandResult.Rejected -> DaemonOperationResponse.Rejected(result.failure.wire())
            is WorkspaceDemandResult.Native ->
                result.exchange.wire(
                    target,
                    root,
                    request.selection is DaemonOperationSelection.Change &&
                        request.selection.action is DaemonChangeAction.Prepare,
                )
        }
    }
}

private fun DaemonOperationSelection.admitRead(): Refinement<ExistingIdeOperation.Read, DaemonOperationFailure> {
    val preparation =
        when (val result = prepareRead()) {
            is Refinement.Refined -> result.value
            is Refinement.Rejected -> return result
        }
    val prepared =
        when (preparation) {
            is OperationPreparation.Prepared -> preparation.request
            is OperationPreparation.Rejected ->
                return Refinement.Rejected(
                    DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.RESPONSE_REJECTED)
                )
        }
    if (prepared.operation != operation())
        return Refinement.Rejected(
            DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.OPERATION_UNSUPPORTED)
        )
    return when (val admitted = ExistingIdeOperation.Read.admit(prepared)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(DaemonOperationFailure.Host(admitted.failure))
    }
}

private fun DaemonOperationSelection.prepareRead(): Refinement<OperationPreparation, DaemonOperationFailure> =
    when (this) {
        is DaemonOperationSelection.PublicTool -> preparePublicTool()
        is DaemonOperationSelection.Canonical -> Refinement.Refined(read.prepare())
        is DaemonOperationSelection.Change ->
            Refinement.Rejected(DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.OPERATION_UNSUPPORTED))
    }

private fun DaemonOperationSelection.PublicTool.preparePublicTool():
    Refinement<OperationPreparation, DaemonOperationFailure> {
    val admitted =
        when (val input = PublicToolContract.admit(tool.identity, arguments)) {
            is Refinement.Refined -> input.value
            is Refinement.Rejected -> return Refinement.Rejected(DaemonOperationFailure.Input(input.failure.wire()))
        }
    val preparers = canonicalCliRequestPreparers()
    return Refinement.Refined(
        when (val canonical = admitted.canonical) {
            is PublicToolCanonical.Query -> preparers.queryRun.prepare(canonical.request)
            is PublicToolCanonical.Diagnostics -> preparers.diagnosticCheck.prepare(canonical.request)
        }
    )
}

private fun DaemonCanonicalRead.prepare(): OperationPreparation {
    val preparers = canonicalCliRequestPreparers()
    return when (this) {
        is DaemonCanonicalRead.SymbolDiscover -> preparers.symbolDiscover.prepare(request)
        is DaemonCanonicalRead.SymbolInspect -> preparers.symbolInspect.prepare(request)
        is DaemonCanonicalRead.SourceRead -> preparers.sourceRead.prepare(request)
        is DaemonCanonicalRead.RelationRead -> preparers.relationRead.prepare(request)
        is DaemonCanonicalRead.TraversalRun -> preparers.traversalRun.prepare(request)
    }
}

private fun DaemonChangeAction.admitChange(): Refinement<ExistingIdeOperation, DaemonOperationFailure> {
    val preparers = canonicalCliRequestPreparers()
    return when (this) {
        is DaemonChangeAction.Plan -> {
            val prepared =
                when (val result = preparers.changePlan.prepare(request)) {
                    is OperationPreparation.Prepared -> result.request
                    is OperationPreparation.Rejected ->
                        return Refinement.Rejected(
                            DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.RESPONSE_REJECTED)
                        )
                }
            when (val admitted = ExistingIdeOperation.Plan.admit(prepared)) {
                is Refinement.Refined -> admitted
                is Refinement.Rejected -> Refinement.Rejected(DaemonOperationFailure.Host(admitted.failure))
            }
        }
        is DaemonChangeAction.Prepare ->
            when (val parsed = HostedPlanIdentity.parse(identity)) {
                is Refinement.Refined ->
                    Refinement.Refined(ExistingIdeOperation.ApprovalPreparation(kind, parsed.value))
                is Refinement.Rejected -> Refinement.Rejected(DaemonOperationFailure.Host(parsed.failure))
            }
        is DaemonChangeAction.Apply ->
            admitApproved(
                preparers.changeApply.prepare(request),
                HostedMutationOperation.CHANGE_APPLY,
                request.planIdentity.value,
                assertion,
            )
        is DaemonChangeAction.Recover ->
            admitApproved(
                preparers.changeRecover.prepare(request),
                HostedMutationOperation.CHANGE_RECOVER,
                request.planIdentity.value,
                assertion,
            )
    }
}

private fun admitApproved(
    preparation: OperationPreparation,
    kind: HostedMutationOperation,
    rawIdentity: String,
    rawAssertion: String,
): Refinement<ExistingIdeOperation, DaemonOperationFailure> {
    val prepared =
        when (preparation) {
            is OperationPreparation.Prepared -> preparation.request
            is OperationPreparation.Rejected ->
                return Refinement.Rejected(
                    DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.RESPONSE_REJECTED)
                )
        }
    val identity =
        when (val parsed = HostedPlanIdentity.parse(rawIdentity)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(DaemonOperationFailure.Host(parsed.failure))
        }
    val assertion =
        when (val parsed = HostedApprovalAssertion.parse(rawAssertion)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(DaemonOperationFailure.Host(parsed.failure))
        }
    return when (val admitted = ExistingIdeOperation.ApprovedMutation.admit(prepared, kind, identity, assertion)) {
        is Refinement.Refined -> admitted
        is Refinement.Rejected -> Refinement.Rejected(DaemonOperationFailure.Host(admitted.failure))
    }
}

private fun admitReadRoot(raw: String): Refinement<CanonicalRoot, DaemonOperationFailure> {
    val discovered =
        try {
            FilesystemCanonicalRootDiscovery.discover(Path.of(raw))
        } catch (_: IllegalArgumentException) {
            return Refinement.Rejected(DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.INVALID_REQUEST))
        }
    return when (discovered) {
        is CanonicalRootDiscovery.Rejected -> Refinement.Rejected(DaemonOperationFailure.Root(discovered.failure))
        is CanonicalRootDiscovery.Discovered ->
            if (discovered.root.path.toString() == raw) Refinement.Refined(discovered.root)
            else Refinement.Rejected(DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.INVALID_REQUEST))
    }
}

private fun PublicToolInputFailure.wire(): DaemonOperationInputFailure =
    when (this) {
        PublicToolInputFailure.SchemaMismatch -> DaemonOperationInputFailure.SchemaMismatch
        PublicToolInputFailure.SchemaRejected -> DaemonOperationInputFailure.SchemaRejected
        PublicToolInputFailure.SyntaxRejected -> DaemonOperationInputFailure.SyntaxRejected
        is PublicToolInputFailure.Parameter -> DaemonOperationInputFailure.Parameter(parameter, rule)
    }

private fun WorkspaceDemandFailure.wire(): DaemonOperationFailure =
    when (this) {
        is WorkspaceDemandFailure.Admission -> DaemonOperationFailure.Preparation(failure)
        is WorkspaceDemandFailure.Operation -> DaemonOperationFailure.Workspace(id.value.toString(), cause)
    }

private fun ExistingIdeExchange.wire(
    target: DaemonManagementTarget,
    root: CanonicalRoot,
    allowHosted: Boolean,
): DaemonOperationResponse {
    fun document(value: String): JsonElement = DaemonOperationProtocol.json.parseToJsonElement(value)
    val path = root.path.toString()
    if (allowHosted)
        return when (this) {
            is ExistingIdeExchange.Received ->
                DaemonOperationResponse.Hosted(target, path, document(this.document.value))
            is ExistingIdeExchange.Rejected -> DaemonOperationResponse.Rejected(DaemonOperationFailure.Host(failure))
            else ->
                DaemonOperationResponse.Rejected(
                    DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.RESPONSE_REJECTED)
                )
        }
    return when (this) {
        is ExistingIdeExchange.Received ->
            DaemonOperationResponse.Rejected(
                DaemonOperationFailure.Protocol(DaemonOperationProtocolFailure.RESPONSE_REJECTED)
            )
        is ExistingIdeExchange.HostRejected ->
            DaemonOperationResponse.OperationRejected(target, path, document(this.document.value))
        is ExistingIdeExchange.Rejected -> DaemonOperationResponse.Rejected(DaemonOperationFailure.Host(failure))
        is ExistingIdeExchange.Semantic ->
            when (val projected = outcome) {
                is ProjectedOperationOutcome.Complete ->
                    DaemonOperationResponse.Complete(target, path, document(projected.document.value))
                is ProjectedOperationOutcome.Qualified ->
                    DaemonOperationResponse.Qualified(target, path, document(projected.document.value))
                is ProjectedOperationOutcome.Rejected ->
                    DaemonOperationResponse.OperationRejected(target, path, document(projected.document.value))
            }
    }
}
