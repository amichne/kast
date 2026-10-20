package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

/** Assumed detached facts exercise composition. Native fixtures establish actual argument mapping. */
internal class ValueLetCallbackOracleCase {
    val metadata = ReviewedModelOracleCase()
    val host = metadata.host
    val call = metadata.call(endpoint = endpoint())
    val binding = ReviewedLibraryBinding.fromCompiler(call, ReviewedValueModel.Let, metadata.artifact).flowRefined()
    val body = anonymous(flowRange(52, 58))
    val formal =
        ValueFormalParameter.fromCompilerImplicit(
                body,
                ValueArgumentPosition.parse(0).flowRefined(),
                SymbolDiscoveryCandidateName.parse("it").flowRefined(),
            )
            .flowRefined()
    val argument = RelationOccurrence.fromSource(host.file, body.range)

    fun frame() = ValueCallFrame.reviewedCallback(binding, argument, formal).flowRefined()

    fun receiver(kind: ValueReceiverKind = ValueReceiverKind.EXTENSION, invocation: ValueInvocation = call) =
        metadata.receiver(path = metadata.suffix, kind = kind, call = invocation)

    fun returned(frame: ValueCallFrame, path: ValueAccessPath = ValueAccessPath.Whole): ValueSite =
        ValueSite.fromCompiler(
                enclosing = host,
                range = flowRange(54, 56),
                role = ValueRole.Return,
                executionOwner = body,
                valuePath = path,
                callStack = metadata.stack.enter(frame).flowRefined(),
                predicates = metadata.predicates,
            )
            .flowRefined()

    fun anonymous(range: io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange): ValueExecutionOwner {
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = RelationCallableBody.Anonymous.sourceIdentity(host.file, range),
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = listOf("String"),
                    rawTypeParameterCount = 0,
                )
                .flowRefined() as CanonicalCompilerSignature.Function
        val body = RelationCallableBody.Anonymous.fromCompiler(host.file, range, signature).flowRefined()
        return ValueExecutionOwner.fromCompiler(host, body, host.lease.identity).flowRefined()
    }

    private fun endpoint(): RelationEndpoint {
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = "kotlin.let",
                    rawReceiverType = "T",
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = listOf("(T)->R"),
                    rawTypeParameterCount = 2,
                )
                .flowRefined()
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file =
                        SymbolDiscoveryFileIdentity.External(
                            DetachedVirtualFileUrl.parse(ReviewedModelOracleCase.ROOT + "kotlin/StandardKt.class")
                                .flowRefined()
                        ),
                    rawStartInclusive = 100,
                    rawEndExclusive = 200,
                    rawName = "let",
                    rawQualifiedIdentity = "kotlin.let",
                    kind = CompilerSymbolKind.FUNCTION,
                    signature = signature,
                )
                .flowRefined()
        return RelationEndpoint.resolve(host.lease, metadata.callable.scope, evidence).flowRefined()
    }
}
