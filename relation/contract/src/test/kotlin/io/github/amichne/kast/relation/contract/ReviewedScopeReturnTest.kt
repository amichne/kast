package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedScopeReturnTest {
    @Test
    fun `also callback result never becomes the receiver result`() {
        val f = scopeFixture(ReviewedLibraryOperation.ALSO)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedValueTransfer.callbackResult(f.source, f.reviewed),
        )
    }

    @Test
    fun `let composes declared invocation with reviewed return without changing the field path`() {
        val f = scopeFixture(ReviewedLibraryOperation.LET)
        assertEquals(DeclaredCallbackResultEffect.UNSPECIFIED, f.declared.resultEffect)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ValueTransfer.declaredCallbackResult(f.source, f.declared),
        )
        val result = ReviewedValueTransfer.callbackResult(f.source, f.reviewed)
        assertTrue(result is Refinement.Refined, "Missing reviewed let return: $result")
        val transfer = (result as Refinement.Refined).value
        assertEquals(f.source.valuePath, transfer.target.valuePath)
        assertEquals(listOf(ValuePathSegment.Field(f.field)), transfer.target.valuePath.segments)
        assertEquals(f.reviewed.occurrence.range, transfer.target.range)
        assertEquals(f.source.executionOwner, transfer.source.executionOwner)
        assertTrue(transfer.source.executionOwner.context is ValueExecutionContext.DeclaredCallback)
        assertEquals(ValueExecutionContext.Declaration, transfer.target.executionOwner.context)
    }

    @Test
    fun `another call and a changed artifact do not compose`() {
        val f = scopeFixture(ReviewedLibraryOperation.LET)
        val other = ReviewedValueFixture()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedValueTransfer.callbackResult(f.source, other.binding),
        )
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH),
            ReviewedLibraryArtifact.admit(
                f.reviewed.selected,
                other.digest("a".repeat(64)),
                f.reviewed.artifact.sourceArtifact,
            ),
        )
    }

    @Test
    fun `also keeps only the exact whole receiver identity`() {
        val f = scopeFixture(ReviewedLibraryOperation.ALSO)
        val source = receiver(f)
        val result = ReviewedValueTransfer.receiverResult(source, f.reviewed, f.declared).flowRefined()
        assertEquals(ValueAccessPath.Whole, result.target.valuePath)
        assertEquals(f.reviewed.occurrence.range, result.target.range)
        assertEquals(source.executionOwner, result.target.executionOwner)
        assertEquals(source.callStack, result.target.callStack)
        assertEquals(source.conditions.facts, result.target.conditions.facts.dropLast(1))
        assertEquals(ReviewedValueSemantics.CONDITIONAL_RECEIVER_TO_CALL_RESULT, result.semantics)
        assertEquals(ReviewedCallbackInvocationEvidence.Declared(f.declared), result.binding.invocationEvidence)
    }

    @Test
    fun `also rejects callback contents wrong receiver slot and different invocation`() {
        val f = scopeFixture(ReviewedLibraryOperation.ALSO)
        val forbidden =
            listOf(
                f.source,
                receiver(f, path = flowPath(ValuePathSegment.Field(f.field))),
                receiver(f, kind = ValueReceiverKind.DISPATCH),
                receiver(f, callRange = flowRange(420, 459)),
            )
        for (source in forbidden) {
            assertEquals(
                Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
                ReviewedValueTransfer.receiverResult(source, f.reviewed, f.declared),
            )
        }
    }

    @Test
    fun `receiver identity rejects another epoch and another reviewed operation`() {
        val f = scopeFixture(ReviewedLibraryOperation.ALSO)
        val newer = scopeFixture(ReviewedLibraryOperation.ALSO, generation = 2)
        val let = scopeFixture(ReviewedLibraryOperation.LET)
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.receiverResult(receiver(newer), f.reviewed, f.declared),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.receiverResult(receiver(let), let.reviewed, let.declared),
        )
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.NESTED_EXECUTION),
            ReviewedValueTransfer.receiverResult(receiver(f), f.reviewed, let.declared),
        )
    }

    @Test
    fun `also result needs an explicit premise for normal callback return`() {
        val f = scopeFixture(ReviewedLibraryOperation.ALSO)
        val receiver = receiver(f)
        val cast =
            ValueCondition.CastSuccess.fromCompiler(
                    source = receiver,
                    useRange = receiver.range,
                    kind = ValueCastKind.UNSAFE,
                    originalType =
                        io.github.amichne.kast.symbol.contract.CanonicalCompilerType.fromCompiler("kotlin/Any")
                            .flowRefined(),
                    targetType =
                        io.github.amichne.kast.symbol.contract.CanonicalCompilerType.fromCompiler("fixture/Payload")
                            .flowRefined(),
                )
                .flowRefined()
        val source = receiver.withState(conditions = receiver.conditions.requiring(cast).flowRefined()).flowRefined()
        val transfer = ReviewedValueTransfer.receiverResult(source, f.reviewed, f.declared).flowRefined()
        assertEquals(source.conditions.facts.size + 1, transfer.target.conditions.facts.size)
        val premise = transfer.target.conditions.facts.last()
        assertEquals(f.reviewed.occurrence.range, premise.useRange)
        assertTrue(premise.origin.matches(source))
        assertTrue(premise is ValueCondition.ReviewedCallbackNormalReturn)
        assertEquals(transfer.binding, (premise as ValueCondition.ReviewedCallbackNormalReturn).binding)
        assertEquals(source.conditions.facts, transfer.target.conditions.facts.dropLast(1))
        assertEquals(false, source.conditions.admits(source, transfer.target, ValueTransferKind.WRAPPER_RETURN))
        val repeated = source.withState(conditions = transfer.target.conditions).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueFlowUnsupportedCause.UNSUPPORTED_RETURN),
            ReviewedValueTransfer.receiverResult(repeated, f.reviewed, f.declared),
        )
    }

    @Test
    fun `normal return premise rejects missing invocation evidence and another owner`() {
        val f = scopeFixture(ReviewedLibraryOperation.ALSO)
        val source = receiver(f)
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH),
            ValueCondition.ReviewedCallbackNormalReturn.fromReviewed(source, f.reviewed),
        )
        val transfer = ReviewedValueTransfer.receiverResult(source, f.reviewed, f.declared).flowRefined()
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH),
            ValueCondition.ReviewedCallbackNormalReturn.fromReviewed(f.source, transfer.binding),
        )
        val anotherCall = receiver(f, callRange = flowRange(420, 459))
        assertEquals(
            Refinement.Rejected(ValueSiteFailure.INVOCATION_OWNER_MISMATCH),
            ValueCondition.ReviewedCallbackNormalReturn.fromReviewed(anotherCall, transfer.binding),
        )
    }

    @Test
    fun `receiver transfer accounts for evidence absent from its value sites`() {
        val f = scopeFixture(ReviewedLibraryOperation.ALSO)
        val transfer = ReviewedValueTransfer.receiverResult(receiver(f), f.reviewed, f.declared).flowRefined()
        assertTrue(
            transfer.retainedBytes >=
                transfer.source.retainedBytes + transfer.target.retainedBytes + transfer.binding.retainedBytes,
            "The receiver sites do not retain the separate callback evidence",
        )
    }

    private fun receiver(
        fixture: ScopeFixture,
        path: ValueAccessPath = ValueAccessPath.Whole,
        kind: ValueReceiverKind = ValueReceiverKind.EXTENSION,
        callRange: io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange = fixture.reviewed.occurrence.range,
    ): ValueSite {
        val binding = fixture.reviewed
        val target =
            RelationEndpoint.resolve(binding.enclosing.lease, binding.enclosing.scope, binding.target).flowRefined()
        val call = ValueInvocation.fromCompiler(binding.enclosing, callRange, target).flowRefined()
        return ValueSite.fromCompiler(
                enclosing = binding.enclosing,
                range = flowRange(424, 433),
                role = ValueRole.Receiver(call, kind),
                valuePath = path,
            )
            .flowRefined()
    }

    private fun scopeFixture(operation: ReviewedLibraryOperation, generation: Long = 1): ScopeFixture {
        val f = ReviewedValueFixture(generation)
        val target = f.scopeTarget(operation)
        val selected = ReviewedLibraryCallable.admit(target, f.position(0)).flowRefined()
        val artifact =
            ReviewedLibraryArtifact.admit(
                    selected,
                    f.digest("887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1"),
                    f.digest("326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531"),
                )
                .flowRefined()
        val reviewed =
            ReviewedCallbackValueBinding.admit(
                    enclosing = f.enclosing,
                    body = f.body,
                    occurrence = f.occurrence,
                    supplier = f.supplier,
                    selected = selected,
                    artifact = artifact,
                )
                .flowRefined()
        val contract =
            CallbackDependencyContract.fromCompiler(
                    basis = f.enclosing.lease.identity,
                    occurrence = f.occurrence,
                    owner = f.supplier,
                    target = target,
                    position = f.position(0),
                    classDigest = DependencyClassDigest.parse(artifact.facadeSha256).flowRefined(),
                    provenance = CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                    invocationKind = CallbackDependencyInvocationKind.EXACTLY_ONCE,
                )
                .flowRefined()
        val declared =
            DeclaredCallbackValueBinding.admit(
                    enclosing = f.enclosing,
                    body = f.body,
                    contract = contract,
                    resultEffect = DeclaredCallbackResultEffect.UNSPECIFIED,
                )
                .flowRefined()
        return ScopeFixture(
            reviewed = reviewed,
            declared = declared,
            field = f.field,
            source = f.source(owner = ValueExecutionOwner.declaredCallback(declared)),
        )
    }

    private fun ReviewedValueFixture.scopeTarget(operation: ReviewedLibraryOperation): CompilerGroundedSymbolEvidence {
        val name = if (operation == ReviewedLibraryOperation.LET) "let" else "also"
        return target(
            qualified = "kotlin.$name",
            receiver = "T",
            parameters = listOf(if (operation == ReviewedLibraryOperation.LET) "(T)->R" else "(T)->kotlin/Unit"),
            typeParameters = if (operation == ReviewedLibraryOperation.LET) 2 else 1,
            file =
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/StandardKt.class").flowRefined()
                ),
        )
    }

    private data class ScopeFixture(
        val reviewed: ReviewedCallbackValueBinding,
        val declared: DeclaredCallbackValueBinding,
        val field: ValueField,
        val source: ValueSite,
    )
}
