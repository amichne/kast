package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Assumed metadata proves pure admission only. Native K2 and safe navigation need their own fixtures. */
class ReviewedLetBindingOracleTest {
    @Test
    fun `exact let binding keeps its distinct overload and reviewed artifact proof`() {
        val fixture = ReviewedModelOracleCase()
        val invocation = invocation(fixture)
        val binding =
            ReviewedLibraryBinding.fromCompiler(invocation, ReviewedValueModel.Let, fixture.artifact).flowRefined()
        assertSame(invocation, binding.invocation)
        assertSame(fixture.artifact, binding.artifact)
        assertEquals(signature(), binding.model.callable)
        assertEquals(ReviewedModelOracleCase.BINARY_SHA, binding.model.artifact.binaryDigest.value)
        assertEquals(ReviewedModelOracleCase.SOURCE_SHA, binding.model.artifact.sourceDigest.value)
        assertEquals(ReviewedModelOracleCase.VERSION, binding.model.artifact.version.value)
        assertEquals("scope.let.invoke-block-value", binding.model.reference.rule.value)
        assertEquals(
            "source-sha256:" + ReviewedModelOracleCase.SOURCE_SHA,
            binding.model.reference.model.provenance.value,
        )
    }

    @Test
    fun `a reviewed let receiver cannot acquire collection selection`() {
        val fixture = ReviewedModelOracleCase()
        val invocation = invocation(fixture)
        val binding =
            ReviewedLibraryBinding.fromCompiler(invocation, ReviewedValueModel.Let, fixture.artifact).flowRefined()
        val receiver = fixture.receiver(call = invocation)
        assertEquals(
            Refinement.Rejected(ValueModelApplicationFailure.RULE_MISMATCH),
            ValueModelApplication.CollectionSelection.selectFirst(receiver, binding),
        )
    }

    private fun invocation(fixture: ReviewedModelOracleCase): ValueInvocation {
        val evidence =
            CompilerGroundedSymbolEvidence.fromBoundary(
                    file =
                        SymbolDiscoveryFileIdentity.External(
                            DetachedVirtualFileUrl.parse(ReviewedModelOracleCase.ROOT + "kotlin/StandardKt.class")
                                .flowRefined()
                        ),
                    rawStartInclusive = DECLARATION_START,
                    rawEndExclusive = DECLARATION_END,
                    rawName = "let",
                    rawQualifiedIdentity = "kotlin.let",
                    kind = CompilerSymbolKind.FUNCTION,
                    signature = signature(),
                )
                .flowRefined()
        val endpoint = RelationEndpoint.resolve(fixture.host.lease, fixture.callable.scope, evidence).flowRefined()
        return fixture.call(endpoint = endpoint)
    }

    private fun signature() =
        CanonicalCompilerSignature.function(
                rawQualifiedIdentity = "kotlin.let",
                rawReceiverType = "T",
                rawContextReceiverTypes = emptyList(),
                rawValueParameterTypes = listOf("(T)->R"),
                rawTypeParameterCount = TYPE_PARAMETERS,
            )
            .flowRefined()

    private companion object {
        const val DECLARATION_START = 100
        const val DECLARATION_END = 200
        const val TYPE_PARAMETERS = 2
    }
}
