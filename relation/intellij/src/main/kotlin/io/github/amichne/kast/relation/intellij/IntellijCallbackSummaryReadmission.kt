package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackEndpointReadmissions
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.CallbackReadmission
import io.github.amichne.kast.relation.contract.CallbackSummaryReadmissionFailure
import io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.ValueDeclarationIdentity
import io.github.amichne.kast.relation.contract.requiredCompilerDeclarations
import io.github.amichne.kast.relation.contract.requiredEndpoints
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure

/** Called after complete dependency continuity; every endpoint receives fresh K2 refinement and work accounting. */
internal fun readmitCallbackSummary(
    previous: CallbackParameterSummary,
    request: RelationRequest,
    projection: IntellijK2RelationProjection,
    scope: CompiledRelationScope,
    admitWork: () -> CallbackWorkAdmission,
): CallbackReadmission<CallbackParameterSummary> =
    when (
        val admitted =
            readmitCallbackEndpoints(previous.requiredEndpoints(), request, projection, scope, admitWork = admitWork)
    ) {
        is Refinement.Refined -> admitted.value.readmit(previous)
        is Refinement.Rejected -> admitted
    }

internal fun readmitCallbackSupplierInventory(
    previous: CompleteCallbackSupplierInventory,
    request: RelationRequest,
    projection: IntellijK2RelationProjection,
    scope: CompiledRelationScope,
    admitWork: () -> CallbackWorkAdmission,
): CallbackReadmission<CompleteCallbackSupplierInventory> =
    when (
        val admitted =
            readmitCallbackEndpoints(
                previous.requiredEndpoints(),
                request,
                projection,
                scope,
                previous.requiredCompilerDeclarations().toList(),
                admitWork,
            )
    ) {
        is Refinement.Refined -> admitted.value.readmit(previous)
        is Refinement.Rejected -> admitted
    }

internal fun readmitCallbackEndpoints(
    required: List<RelationEndpoint>,
    request: RelationRequest,
    projection: IntellijK2RelationProjection,
    scope: CompiledRelationScope,
    requiredDeclarations: List<CompilerGroundedSymbolEvidence> = emptyList(),
    admitWork: () -> CallbackWorkAdmission,
): CallbackReadmission<CallbackEndpointReadmissions> {
    val authority =
        request.subject.lease as? LiveSemanticReadAuthority
            ?: return Refinement.Rejected(
                CallbackSummaryReadmissionFailure.Authority(LiveSemanticReadFailure.WRONG_HOST)
            )
    return NativeCallbackEndpointReadmissions(authority, request, projection, scope, admitWork)
        .read(required, requiredDeclarations)
}

private class NativeCallbackEndpointReadmissions(
    private val authority: LiveSemanticReadAuthority,
    private val request: RelationRequest,
    private val projection: IntellijK2RelationProjection,
    private val scope: CompiledRelationScope,
    private val admitWork: () -> CallbackWorkAdmission,
) {
    fun read(
        required: List<RelationEndpoint>,
        requiredDeclarations: List<CompilerGroundedSymbolEvidence>,
    ): CallbackReadmission<CallbackEndpointReadmissions> {
        val endpoints = linkedMapOf<RelationEndpoint, RelationEndpoint.Resolved>()
        for (previous in required) when (val admitted = endpoint(previous)) {
            is Refinement.Refined -> endpoints[previous] = admitted.value
            is Refinement.Rejected -> return admitted
        }
        val declarations = linkedMapOf<CompilerGroundedSymbolEvidence, CompilerGroundedSymbolEvidence>()
        for (previous in requiredDeclarations) when (val admitted = declaration(previous)) {
            is Refinement.Refined -> declarations[previous] = admitted.value
            is Refinement.Rejected -> return admitted
        }
        return CallbackEndpointReadmissions.fromCompiler(authority, endpoints, declarations)
    }

    private fun endpoint(previous: RelationEndpoint): CallbackReadmission<RelationEndpoint.Resolved> = withWork {
        when (val restored = projection.subject(scope, previous)) {
            is IntellijRelationSubjectLookup.Rejected ->
                Refinement.Rejected(CallbackSummaryReadmissionFailure.MissingEndpoint(previous.valueIdentity))
            is IntellijRelationSubjectLookup.Found ->
                when (
                    val admitted =
                        RelationEndpoint.resolve(authority, previous.scope, restored.evidence, previous.constraints)
                ) {
                    is Refinement.Refined -> admitted
                    is Refinement.Rejected ->
                        Refinement.Rejected(CallbackSummaryReadmissionFailure.EndpointChanged(previous.valueIdentity))
                }
        }
    }

    private fun declaration(
        previous: CompilerGroundedSymbolEvidence
    ): CallbackReadmission<CompilerGroundedSymbolEvidence> = withWork {
        val identity = ValueDeclarationIdentity.fromCompiler(previous)
        when (
            val endpoint = RelationEndpoint.resolve(authority, request.searchScope, previous, request.searchConstraints)
        ) {
            is Refinement.Rejected -> Refinement.Rejected(CallbackSummaryReadmissionFailure.EndpointChanged(identity))
            is Refinement.Refined ->
                when (val restored = projection.subject(scope, endpoint.value)) {
                    is IntellijRelationSubjectLookup.Found -> Refinement.Refined(restored.evidence)
                    is IntellijRelationSubjectLookup.Rejected ->
                        Refinement.Rejected(CallbackSummaryReadmissionFailure.MissingEndpoint(identity))
                }
        }
    }

    private inline fun <T> withWork(read: () -> CallbackReadmission<T>): CallbackReadmission<T> =
        when (admitWork()) {
            CallbackWorkAdmission.READY -> read()
            CallbackWorkAdmission.WORK_LIMIT_REACHED ->
                Refinement.Rejected(CallbackSummaryReadmissionFailure.WorkLimitReached)
            CallbackWorkAdmission.TIME_LIMIT_REACHED ->
                Refinement.Rejected(CallbackSummaryReadmissionFailure.TimeLimitReached)
        }
}
