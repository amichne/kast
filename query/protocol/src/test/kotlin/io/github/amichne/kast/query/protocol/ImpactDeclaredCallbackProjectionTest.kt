package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclaredCallbackResultEffectDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactValueConditionDocument
import io.github.amichne.kast.protocol.contract.QueryCallbackDependencyInvocationKindDocument
import io.github.amichne.kast.protocol.contract.hasSameValueIdentity
import io.github.amichne.kast.relation.contract.CallbackDependencyContract
import io.github.amichne.kast.relation.contract.CallbackDependencyContractProvenance
import io.github.amichne.kast.relation.contract.CallbackDependencyInvocationKind
import io.github.amichne.kast.relation.contract.DeclaredCallbackResultEffect
import io.github.amichne.kast.relation.contract.DeclaredCallbackValueBinding
import io.github.amichne.kast.relation.contract.DependencyClassDigest
import io.github.amichne.kast.relation.contract.RelationCallableBody
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactDeclaredCallbackProjectionTest {
    @Test
    fun `compiler transfer projects and restores exact selected binary result and conditional origin`() {
        val f = ImpactPathProjectionFixture()
        val owner = f.owner as RelationEndpoint.Resolved
        val target = binaryTarget()
        for ((kind, expected) in
            listOf(
                CallbackDependencyInvocationKind.EXACTLY_ONCE to
                    QueryCallbackDependencyInvocationKindDocument.EXACTLY_ONCE,
                CallbackDependencyInvocationKind.AT_MOST_ONCE to
                    QueryCallbackDependencyInvocationKindDocument.AT_MOST_ONCE,
            )) {
            val transfer = declaredTransfer(owner, target, kind)
            val document = transfer.impactDocument().declaredProjection()
            assertTrue(document.admitsEvidence())
            val projected = (document.source.executionOwner as ImpactExecutionOwnerDocument.DeclaredCallback).binding
            val condition =
                document.target.conditions.values.single() as ImpactValueConditionDocument.DeclaredCallbackResult
            val operation =
                (document.evidence as ImpactTransferEvidenceDocument.Source).operation
                    as ImpactSourceOperationDocument.DeclaredCallbackResult
            assertEquals(projected, operation.binding)
            assertEquals(projected, condition.binding)
            assertEquals(ImpactDeclaredCallbackResultEffectDocument.RETURNS_RESULT_OF, projected.resultEffect)
            assertEquals(expected, projected.contract.invocationKind)
            assertEquals("ab".repeat(32), projected.contract.classDigest.value)
            assertEquals(target.compilerIdentity.value, projected.contract.target.declaration.compilerIdentity.value)
            assertEquals("fixture.select", projected.contract.target.qualifiedIdentity.value)
            assertEquals(listOf("()->T"), projected.contract.target.valueParameters.values.map { it.value })
            assertEquals(range(50, 60).impactDocument().declaredProjection(), condition.origin.range)
            assertEquals(range(30, 100).impactDocument().declaredProjection(), condition.useRange)
            assertEquals(document.target, transfer.target.identity.impactDocument().declaredProjection())
            val restored = Json.decodeFromString<ImpactCompilerTransferDocument>(Json.encodeToString(document))
            assertTrue(restored.admitsEvidence())
            assertTrue(restored.target.hasSameValueIdentity(document.target))
            assertEquals(document, restored)
        }
    }

    private fun declaredTransfer(
        owner: RelationEndpoint.Resolved,
        target: CompilerGroundedSymbolEvidence,
        kind: CallbackDependencyInvocationKind,
    ): ValueTransfer {
        val contract =
            CallbackDependencyContract.fromCompiler(
                    owner.lease.identity,
                    RelationOccurrence.fromBoundary(owner.file, 30, 100).declaredProjection(),
                    RelationCallableBody.Named.fromCompiler(owner.evidence).declaredProjection(),
                    target,
                    ValueArgumentPosition.parse(0).declaredProjection(),
                    DependencyClassDigest.parse("ab".repeat(32)).declaredProjection(),
                    CallbackDependencyContractProvenance.KOTLIN_BINARY_CONTRACT,
                    kind,
                )
                .declaredProjection()
        val binding =
            DeclaredCallbackValueBinding.admit(
                    owner,
                    body(owner),
                    contract,
                    DeclaredCallbackResultEffect.RETURNS_RESULT_OF,
                )
                .declaredProjection()
        val source =
            ValueSite.fromCompiler(
                    owner,
                    range(50, 60),
                    ValueRole.Return,
                    ValueExecutionOwner.declaredCallback(binding),
                )
                .declaredProjection()
        return ValueTransfer.declaredCallbackResult(source, binding).declaredProjection()
    }

    private fun body(owner: RelationEndpoint): RelationCallableBody.Anonymous {
        val bodyRange = range(40, 90)
        val signature =
            CanonicalCompilerSignature.function(
                    RelationCallableBody.Anonymous.sourceIdentity(owner.file, bodyRange),
                    null,
                    emptyList(),
                    emptyList(),
                    0,
                )
                .declaredProjection() as CanonicalCompilerSignature.Function
        return RelationCallableBody.Anonymous.fromCompiler(owner.file, bodyRange, signature).declaredProjection()
    }

    private fun binaryTarget(): CompilerGroundedSymbolEvidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///effects.jar!/fixture/EffectsKt.class").declaredProjection()
                ),
                1000,
                1100,
                "select",
                "fixture.select",
                CompilerSymbolKind.FUNCTION,
                CanonicalCompilerSignature.function("fixture.select", null, emptyList(), listOf("()->T"), 1)
                    .declaredProjection(),
            )
            .declaredProjection()

    private fun range(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).declaredProjection()
}

private fun <T, F> Refinement<T, F>.declaredProjection(): T =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid declared projection fixture: $failure")
    }
