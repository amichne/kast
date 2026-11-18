package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrimitiveValueTransferTest {
    @Test
    fun `arithmetic applies once to its own scalar input and result on the selected basis`() {
        val owner = flowEndpoint("transform", 0, 80)
        val input = site(owner, 11, 14)
        val result = site(owner, 10, 20)
        val proof = proof(input)
        assertEquals(IntArithmeticRule.INT_MINUS_V1, proof.rule)
        val transfer =
            ValueTransfer.fromCompiler(input, result, ValueTransferKind.PRIMITIVE_DERIVATION, proof).flowRefined()
        assertTrue(transfer.evidence.retainedBytes > 0)
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(input, result, ValueTransferKind.PRIMITIVE_DERIVATION),
        )
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(site(owner, 12, 14), result, ValueTransferKind.PRIMITIVE_DERIVATION, proof),
        )
        val stale = flowEndpoint("transform", 0, 80, generation = 2)
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                site(stale, 11, 14),
                site(stale, 10, 20),
                ValueTransferKind.PRIMITIVE_DERIVATION,
                proof,
            ),
        )
        val other = flowEndpoint("otherTransform", 0, 80)
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(
                site(other, 11, 14),
                site(other, 10, 20),
                ValueTransferKind.PRIMITIVE_DERIVATION,
                proof,
            ),
        )
    }

    @Test
    fun `builtin identity cannot come from an overload or mismatched module kind`() {
        val source = site(flowEndpoint("transform", 0, 80), 11, 14)
        val rejected = Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH)
        for (different in
            listOf(
                callable("kotlin.Int.minus", "kotlin/Long"),
                callable("kotlin.Int.minus", "kotlin/Int?"),
                callable("fixture.Custom.minus", "kotlin/Int"),
                callable("kotlin.Int.div", "kotlin/Int"),
                callable("kotlin.Int.minus", "kotlin/Int", SourceLessCallableModuleKind.BUILTINS),
            )) {
            assertEquals(rejected, arithmetic(source, different))
        }
    }

    @Test
    fun `repeated reads share selected callable and owner proof`() {
        val owner = flowEndpoint("transform", 0, 80)
        val input = site(owner, 11, 14)
        val target = site(owner, 10, 20)
        val first =
            ValueTransfer.fromCompiler(input, target, ValueTransferKind.PRIMITIVE_DERIVATION, proof(input))
                .flowRefined()
        val second =
            ValueTransfer.fromCompiler(input, target, ValueTransferKind.PRIMITIVE_DERIVATION, proof(input))
                .flowRefined()
        val sharing = ValueFlowProofSharing()
        val firstProof = first.shareCallableEvidence(ValueFlowSiteSharing(sharing)).evidence as ValueIntArithmetic
        val secondProof = second.shareCallableEvidence(ValueFlowSiteSharing(sharing)).evidence as ValueIntArithmetic
        assertSame(firstProof.callable, secondProof.callable)
        assertSame(firstProof.execution, secondProof.execution)
    }

    @Test
    fun `reviewed math admission binds exact Int signature and paired artifacts`() {
        val fixture = ReviewedValueFixture()
        val mathFile =
            SymbolDiscoveryFileIdentity.External(
                DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/math/MathKt.class").flowRefined()
            )
        val parameters = listOf("kotlin/Int", "kotlin/Int")
        val int = CanonicalCompilerType.fromCompiler("kotlin/Int").flowRefined()
        val long = CanonicalCompilerType.fromCompiler("kotlin/Long").flowRefined()
        val min =
            fixture.target(
                qualified = "kotlin.math.min",
                receiver = null,
                parameters = parameters,
                typeParameters = 0,
                file = mathFile,
            )
        val max =
            fixture.target(
                qualified = "kotlin.math.max",
                receiver = null,
                parameters = parameters,
                typeParameters = 0,
                file = mathFile,
            )
        val minRule = ReviewedOrdinaryLibraryCallable.admitIntExtremum(min, int).flowRefined()
        val maxRule = ReviewedOrdinaryLibraryCallable.admitIntExtremum(max, int).flowRefined()
        val binary = fixture.digest("887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1")
        val sources = fixture.digest("326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531")
        val minArtifact = ReviewedLibraryArtifact.admit(minRule, binary, sources).flowRefined()
        val maxArtifact = ReviewedLibraryArtifact.admit(maxRule, binary, sources).flowRefined()
        assertEquals(ReviewedLibraryRule.KOTLIN_2_3_0_INT_MIN_V1, minArtifact.rule)
        assertEquals(ReviewedLibraryRule.KOTLIN_2_3_0_INT_MAX_V1, maxArtifact.rule)
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH),
            ReviewedLibraryArtifact.admit(minRule, binary, fixture.digest("0".repeat(64))),
        )
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
            ReviewedOrdinaryLibraryCallable.admitIntExtremum(min, long),
        )
        val endpoint = RelationEndpoint.resolve(fixture.enclosing.lease, fixture.enclosing.scope, min).flowRefined()
        val invocation =
            ValueInvocation.fromCompiler(fixture.enclosing, fixture.occurrence.range, endpoint).flowRefined()
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
            ReviewedCallValueBinding.admit(invocation, minRule, maxArtifact),
        )
    }

    private fun proof(source: ValueSite): ValueIntArithmetic =
        arithmetic(source, callable("kotlin.Int.minus", "kotlin/Int")).flowRefined()

    private fun arithmetic(source: ValueSite, callable: SourceLessCallable) =
        ValueIntArithmetic.fromCompiler(
            callable = callable,
            authority = IntBuiltinEvidence.K2_BUILTINS_METADATA,
            input = IntArithmeticInput.ARGUMENT_0,
            inputRange = source.range,
            resultRange = flowRange(10, 20),
            execution = source.executionOwner,
        )

    private fun callable(
        name: String,
        parameter: String,
        module: SourceLessCallableModuleKind = SourceLessCallableModuleKind.LIBRARY,
    ): SourceLessCallable =
        SourceLessCallable.fromCompiler(
                signature =
                    CanonicalCompilerSignature.function(name, null, emptyList(), listOf(parameter), 0).flowRefined(),
                kind = CompilerSymbolKind.FUNCTION,
                origin = SourceLessCallableOrigin.LIBRARY,
                moduleKind = module,
                moduleName = SourceLessCallableModuleName.parse("fixture stdlib").flowRefined(),
            )
            .flowRefined()

    private fun site(owner: RelationEndpoint, start: Int, end: Int) =
        ValueSite.fromCompiler(owner, flowRange(start, end), ValueRole.ExpressionResult).flowRefined()
}
