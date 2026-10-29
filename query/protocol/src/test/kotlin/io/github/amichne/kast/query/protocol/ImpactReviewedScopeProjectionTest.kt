package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactActualExecutionDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclaredCallbackResultEffectDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationCountKnowledgeDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedInvocationEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedScopeArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueRuleDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueSemanticsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackDependencyInvocationKindDocument
import io.github.amichne.kast.protocol.contract.admitsEvidence
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
import io.github.amichne.kast.relation.contract.ReviewedCallbackValueBinding
import io.github.amichne.kast.relation.contract.ReviewedLibraryArtifact
import io.github.amichne.kast.relation.contract.ReviewedLibraryCallable
import io.github.amichne.kast.relation.contract.ReviewedValueTransfer
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactReviewedScopeProjectionTest {
    @Test
    fun `reviewed let projects invocation authority without declaring a compiler return effect`() {
        val transfer = ReviewedScopeProjectionFixture().transfer()
        val document = transfer.impactDocument().scopeProjection()
        assertTrue(document.admitsEvidence())
        val reviewed =
            document.binding.authority as io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument
        val invocation =
            assertInstanceOf(ImpactReviewedInvocationEvidenceDocument.Declared::class.java, reviewed.invocationEvidence)
                .binding
        assertEquals(ImpactExecutionOwnerDocument.DeclaredCallback(invocation), document.source.executionOwner)
        assertEquals(ImpactExecutionOwnerDocument.Declaration, document.target.executionOwner)
        assertEquals(ImpactDeclaredCallbackResultEffectDocument.UNSPECIFIED, invocation.resultEffect)
        assertEquals(QueryCallbackDependencyInvocationKindDocument.EXACTLY_ONCE, invocation.contract.invocationKind)
        assertEquals(ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_LET_V1, reviewed.rule)
        assertEquals(
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_CALL_RESULT,
            (document.binding.relation
                    as
                    io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument.CallbackCorrespondence)
                .semantics,
        )
        assertEquals(ImpactActualExecutionDocument.NOT_ESTABLISHED, reviewed.actualExecution)
        assertEquals(ImpactInvocationCountKnowledgeDocument.NOT_ESTABLISHED, reviewed.invocationCount)
        assertEquals(invocation.contract.target.declaration, reviewed.selected.declaration)
        assertEquals(invocation.body, reviewed.body)
        assertEquals(invocation.contract.occurrenceRange, reviewed.supplierRange)
        val artifacts = assertInstanceOf(ImpactReviewedScopeArtifactsDocument::class.java, reviewed.artifacts)
        assertEquals(114, artifacts.functionLines.first.value)
        assertEquals(119, artifacts.functionLines.last.value)
        assertEquals(invocation.contract.classDigest, artifacts.facadeSha256)
        assertEquals(document.source.valuePath, document.target.valuePath)
        val encoded = Json.encodeToString(document)
        assertFalse(encoded.contains("RETURNS_RESULT_OF"))
        assertFalse(encoded.contains("CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT"))
        val restored = Json.decodeFromString<ImpactReviewedValueTransferDocument>(encoded)
        assertEquals(document, restored)
        assertTrue(restored.admitsEvidence())
    }

    @Test
    fun `reviewed also projects receiver identity without callback result correspondence`() {
        val transfer = ReviewedScopeProjectionFixture().transfer(receiver = true)
        val document = transfer.impactDocument().scopeProjection()
        assertTrue(document.admitsEvidence())
        assertEquals(ImpactExecutionOwnerDocument.Declaration, document.source.executionOwner)
        assertEquals(ImpactExecutionOwnerDocument.Declaration, document.target.executionOwner)
        assertEquals(
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_RECEIVER_TO_CALL_RESULT,
            (document.binding.relation
                    as
                    io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument.CallbackCorrespondence)
                .semantics,
        )
        assertEquals(ImpactReviewedValueRuleDocument.KOTLIN_2_3_0_ALSO_V1, document.binding.authority.rule)
        assertTrue(document.source.valuePath.values.isEmpty())
        assertTrue(document.target.valuePath.values.isEmpty())
        val premise =
            assertInstanceOf(
                ImpactValueConditionDocument.ReviewedCallbackNormalReturn::class.java,
                document.target.conditions.values.single(),
            )
        assertTrue(document.source.conditions.values.isEmpty())
        assertEquals(transfer.binding.impactDocument().scopeProjection(), premise.binding)
        assertEquals(document.source.range, premise.origin.range)
        assertEquals(document.source.role, premise.origin.role)
        assertEquals(document.target.range, premise.useRange)

        val artifact =
            assertInstanceOf(ImpactReviewedScopeArtifactsDocument::class.java, document.binding.authority.artifacts)
        assertEquals(99, artifact.functionLines.first.value)
        assertEquals(105, artifact.functionLines.last.value)
        assertEquals(
            ImpactActualExecutionDocument.NOT_ESTABLISHED,
            (document.binding.authority as io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument)
                .actualExecution,
        )
        assertEquals(
            document,
            Json.decodeFromString<ImpactReviewedValueTransferDocument>(Json.encodeToString(document)),
        )
    }
}

internal class ReviewedScopeProjectionFixture {
    fun transfer(receiver: Boolean = false): ReviewedValueTransfer {
        val owner = ImpactPathProjectionFixture().owner as RelationEndpoint.Resolved
        val body = body(owner)
        val target = target(receiver)
        val position = ValueArgumentPosition.parse(0).scopeProjection()
        val selected = ReviewedLibraryCallable.admit(target, position).scopeProjection()
        val artifact =
            ReviewedLibraryArtifact.admit(
                    selected,
                    DependencyArtifactDigest.parse("887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1")
                        .scopeProjection(),
                    DependencyArtifactDigest.parse("326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531")
                        .scopeProjection(),
                )
                .scopeProjection()
        val occurrence = RelationOccurrence.fromBoundary(owner.file, 30, 100).scopeProjection()
        val supplier = RelationCallableBody.Named.fromCompiler(owner.evidence).scopeProjection()
        val reviewed =
            ReviewedCallbackValueBinding.admit(owner, body, occurrence, supplier, selected, artifact).scopeProjection()
        val contract =
            CallbackDependencyContract.fromCompiler(
                    owner.lease.identity,
                    occurrence,
                    supplier,
                    target,
                    position,
                    DependencyClassDigest.parse("f7d71cc1a37a29843d8e3a0b660afa358bdf0f8ac3ffb566a0e79c004f7ffd05")
                        .scopeProjection(),
                    CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                    CallbackDependencyInvocationKind.EXACTLY_ONCE,
                )
                .scopeProjection()
        val declared =
            DeclaredCallbackValueBinding.admit(owner, body, contract, DeclaredCallbackResultEffect.UNSPECIFIED)
                .scopeProjection()
        return if (receiver) {
            ReviewedValueTransfer.receiverResult(receiverSite(owner, target, occurrence), reviewed, declared)
                .scopeProjection()
        } else {
            val source =
                ValueSite.fromCompiler(
                        owner,
                        range(50, 60),
                        ValueRole.Return,
                        ValueExecutionOwner.declaredCallback(declared),
                    )
                    .scopeProjection()
            ReviewedValueTransfer.callbackResult(source, reviewed).scopeProjection()
        }
    }

    private fun receiverSite(
        owner: RelationEndpoint,
        target: CompilerGroundedSymbolEvidence,
        occurrence: RelationOccurrence,
    ): ValueSite {
        val endpoint = RelationEndpoint.resolve(owner.lease, owner.scope, target).scopeProjection()
        val invocation = ValueInvocation.fromCompiler(owner, occurrence.range, endpoint).scopeProjection()
        return ValueSite.fromCompiler(owner, range(30, 35), ValueRole.Receiver(invocation, ValueReceiverKind.EXTENSION))
            .scopeProjection()
    }

    private fun body(owner: RelationEndpoint): RelationCallableBody.Anonymous {
        val bodyRange = range(40, 90)
        val bodySignature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(owner.file, bodyRange),
                    null,
                    emptyList(),
                    listOf("T"),
                    0,
                )
                .scopeProjection() as CanonicalCompilerSignature.Function
        return RelationCallableBody.Anonymous.fromCompiler(owner.file, bodyRange, bodySignature).scopeProjection()
    }

    private fun target(receiver: Boolean): CompilerGroundedSymbolEvidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/StandardKt.class").scopeProjection()
                ),
                1000,
                1100,
                if (receiver) "also" else "let",
                if (receiver) "kotlin.also" else "kotlin.let",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function(
                        if (receiver) "kotlin.also" else "kotlin.let",
                        "T",
                        emptyList(),
                        listOf(if (receiver) "(T)->kotlin/Unit" else "(T)->R"),
                        if (receiver) 1 else 2,
                    )
                    .scopeProjection(),
            )
            .scopeProjection()

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).scopeProjection()
}

private fun <T, F> Refinement<T, F>.scopeProjection(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid scope projection fixture: $failure")
    }
