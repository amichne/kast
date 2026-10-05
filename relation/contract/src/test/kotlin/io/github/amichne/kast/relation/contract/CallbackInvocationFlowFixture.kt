package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path

internal class CallbackInvocationFlowFixture {
    val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/fixture")).value()
    val lease = SemanticReadLease(root, EvidenceGeneration.parse(1).value())
    val file =
        SymbolDiscoveryFileIdentity.Workspace(
            CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of("/fixture/Flow.kt")).value()
        )
    val caller = endpoint("read", 0, 200, emptyList())
    val target = endpoint("nativeBoundary", 210, 400, listOf("Project", "()->Unit"))
    val body = anonymous(30, 60)
    val invocation = ValueInvocation.fromCompiler(caller, range(20, 80), target).value()
    val supplyingOwner = RelationCallableBody.Named.fromCompiler(caller.evidence).value()
    val binding =
        CallbackArgumentBinding.fromCompiler(
                invocation = invocation,
                invocationOwner = supplyingOwner,
                position = ValueArgumentPosition.parse(1).value(),
                parameter = occurrence(225, 245),
            )
            .value()
    val nested = CallbackParameterInvocation.fromCompiler(occurrence(270, 285), anonymous(260, 300)).value()

    fun create(binding: CallbackArgumentBinding, invocations: List<CallbackParameterInvocation>) =
        CallbackInvocationFlow.fromCompiler(
            basis = lease.identity,
            body = body,
            binding = CallbackBindingEvidence.Bound(binding),
            invocations = invocations,
            obligations = emptySet(),
        )

    fun observed(invocations: List<CallbackParameterInvocation>, causes: Set<CallbackInvocationFlowCause>) =
        CallbackInvocationFlow.fromCompiler(
                basis = lease.identity,
                body = body,
                binding = CallbackBindingEvidence.Bound(binding),
                invocations = invocations,
                obligations = causes,
            )
            .value()

    fun anonymous(start: Int, end: Int): RelationCallableBody.Anonymous {
        val range = range(start, end)
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(file, range),
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = emptyList(),
                    rawTypeParameterCount = 0,
                )
                .value()
        return RelationCallableBody.Anonymous.fromCompiler(
                file,
                range,
                signature as CanonicalCompilerSignature.Function,
            )
            .value()
    }

    fun endpoint(name: String, start: Int, end: Int, parameters: List<String>): RelationEndpoint.Resolved {
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = "fixture.$name",
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = parameters,
                    rawTypeParameterCount = 0,
                )
                .value()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file = file,
                    rawStartInclusive = start,
                    rawEndExclusive = end,
                    rawName = name,
                    rawQualifiedIdentity = "fixture.$name",
                    kind = CompilerSymbolKind.FUNCTION,
                    signature = signature,
                )
                .value()
        return RelationEndpoint.resolve(
                lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                ),
                evidence,
            )
            .value()
    }

    fun occurrence(start: Int, end: Int) = RelationOccurrence.fromBoundary(file, start, end).value()

    fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).value()

    fun <V, F> Refinement<V, F>.value(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Unexpected rejection: $failure")
        }

    fun <V, F> Refinement<V, F>.failure(): F =
        when (this) {
            is Refinement.Refined -> error("Expected rejection")
            is Refinement.Rejected -> failure
        }
}
