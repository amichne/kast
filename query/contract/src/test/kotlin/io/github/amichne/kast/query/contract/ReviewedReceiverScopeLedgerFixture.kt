package io.github.amichne.kast.query.contract

import io.github.amichne.kast.relation.contract.CallbackDependencyContract
import io.github.amichne.kast.relation.contract.CallbackDependencyContractProvenance
import io.github.amichne.kast.relation.contract.CallbackDependencyInvocationKind
import io.github.amichne.kast.relation.contract.DeclaredCallbackResultEffect
import io.github.amichne.kast.relation.contract.DeclaredCallbackValueBinding
import io.github.amichne.kast.relation.contract.DependencyArtifactDigest
import io.github.amichne.kast.relation.contract.DependencyClassDigest
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ReviewedCallbackReceiver
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedLibraryCallable
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueAccessPath
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

internal enum class ReceiverScopeFamily {
    TOP_RUN,
    RECEIVER_RUN,
    WITH,
    APPLY,
}

internal class ReviewedReceiverScopeLedgerFixture(val family: ReceiverScopeFamily) {
    val components = ReviewedComponentLedgerFixture()
    val owner = components.owner
    private val target = target()
    private val occurrence = RelationOccurrence.fromBoundary(owner.file, 30, 100).componentValue()
    private val supplier = RelationCallableBody.Named.fromCompiler(owner.evidence).componentValue()
    private val position =
        ValueArgumentPosition.parse(if (family == ReceiverScopeFamily.WITH) 1 else 0).componentValue()
    private val selected = ReviewedLibraryCallable.admit(target, position).componentValue()
    private val artifact =
        ReviewedLibraryArtifact.admit(
                selected,
                DependencyArtifactDigest.parse("887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1")
                    .componentValue(),
                DependencyArtifactDigest.parse("326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531")
                    .componentValue(),
            )
            .componentValue()
    val receiverType = CanonicalCompilerType.fromCompiler("kotlin/String").componentValue()
    val body = body()
    val binding =
        ReviewedCallbackValueBinding.admit(owner, body, occurrence, supplier, selected, artifact).componentValue()
    val declared =
        DeclaredCallbackValueBinding.admit(owner, body, contract(), DeclaredCallbackResultEffect.UNSPECIFIED)
            .componentValue()

    fun input(whole: Boolean = false): ReviewedValueTransfer =
        ReviewedValueTransfer.callbackReceiverInput(
                actual(whole),
                ReviewedCallbackReceiver.fromCompiler(
                        binding,
                        declared,
                        body,
                        ValueReceiverKind.EXTENSION,
                        receiverType,
                    )
                    .componentValue(),
            )
            .componentValue()

    fun returned(): ReviewedValueTransfer {
        val source =
            ValueSite.fromCompiler(
                    owner,
                    componentRange(50, 60),
                    ValueRole.Return,
                    ValueExecutionOwner.declaredCallback(declared),
                    valuePath = components.suffix,
                )
                .componentValue()
        return ReviewedValueTransfer.callbackResult(source, binding).componentValue()
    }

    fun applyResult(): ReviewedValueTransfer =
        ReviewedValueTransfer.receiverResult(actual(whole = true), binding, declared).componentValue()

    private fun actual(whole: Boolean): ValueSite {
        val callee = RelationEndpoint.resolve(owner.lease, owner.scope, target).componentValue()
        val invocation = ValueInvocation.fromCompiler(owner, occurrence.range, callee).componentValue()
        val role =
            if (family == ReceiverScopeFamily.WITH)
                ValueRole.Argument(invocation, ValueArgumentPosition.parse(0).componentValue())
            else ValueRole.Receiver(invocation, ValueReceiverKind.EXTENSION)
        return ValueSite.fromCompiler(
                owner,
                componentRange(30, 35),
                role,
                valuePath = if (whole) ValueAccessPath.Whole else components.suffix,
            )
            .componentValue()
    }

    private fun body(): RelationCallableBody.Anonymous {
        val range = componentRange(40, 90)
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(owner.file, range),
                    if (family == ReceiverScopeFamily.TOP_RUN) null else receiverType.value,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .componentValue() as CanonicalCompilerSignature.Function
        return RelationCallableBody.Anonymous.fromCompiler(owner.file, range, signature).componentValue()
    }

    private fun contract() =
        CallbackDependencyContract.fromCompiler(
                owner.lease.identity,
                occurrence,
                supplier,
                target,
                position,
                DependencyClassDigest.parse(artifact.facadeSha256).componentValue(),
                CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                CallbackDependencyInvocationKind.EXACTLY_ONCE,
            )
            .componentValue()

    private fun target(): CompilerGroundedSymbolEvidence {
        val name =
            when (family) {
                ReceiverScopeFamily.TOP_RUN,
                ReceiverScopeFamily.RECEIVER_RUN -> "run"
                ReceiverScopeFamily.WITH -> "with"
                ReceiverScopeFamily.APPLY -> "apply"
            }
        val parameters =
            when (family) {
                ReceiverScopeFamily.TOP_RUN -> listOf("()->R")
                ReceiverScopeFamily.RECEIVER_RUN -> listOf("T.()->R")
                ReceiverScopeFamily.WITH -> listOf("T", "T.()->R")
                ReceiverScopeFamily.APPLY -> listOf("T.()->kotlin/Unit")
            }
        val signature =
            CanonicalCompilerSignature.function(
                    "kotlin.$name",
                    if (family == ReceiverScopeFamily.TOP_RUN || family == ReceiverScopeFamily.WITH) null else "T",
                    emptyList(),
                    parameters,
                    if (family == ReceiverScopeFamily.TOP_RUN || family == ReceiverScopeFamily.APPLY) 1 else 2,
                )
                .componentValue()
        return CompilerGroundedSymbolEvidence.fromBoundary(
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/StandardKt.class").componentValue()
                ),
                1000,
                1100,
                name,
                "kotlin.$name",
                CompilerSymbolKind.FUNCTION,
                signature,
            )
            .componentValue()
    }
}
