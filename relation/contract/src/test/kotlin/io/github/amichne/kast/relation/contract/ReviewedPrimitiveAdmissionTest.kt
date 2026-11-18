package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalCompilerType
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReviewedPrimitiveAdmissionTest {
    private val fixture = ReviewedValueFixture()
    private val intResult = CanonicalCompilerType.fromCompiler("kotlin/Int").flowRefined()
    private val binary = fixture.digest("887587c91713250ad52fe14ad9166d042c33835049890e9437f355ffc5a195b1")
    private val sources = fixture.digest("326423198a2ad721c9c075e20db7537ad655f21bfe342a5241d874987e434531")

    @Test
    fun `only the selected Int overload admits its reviewed rule`() {
        val selected =
            ReviewedOrdinaryLibraryCallable.admitPrimitive(member("minus", "kotlin/Int"), intResult).flowRefined()
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
            ReviewedOrdinaryLibraryCallable.admitPrimitive(
                member("minus", "kotlin/Int"),
                CanonicalCompilerType.fromCompiler("kotlin/Int?").flowRefined(),
            ),
        )
        assertEquals(ReviewedLibraryOperation.INT_MINUS, selected.operation)
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
            ReviewedOrdinaryLibraryCallable.admitPrimitive(member("minus", "kotlin/Long"), intResult),
        )
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
            ReviewedOrdinaryLibraryCallable.admitPrimitive(member("div", "kotlin/Int"), intResult),
        )
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.ARTIFACT_MISMATCH),
            ReviewedLibraryArtifact.admit(selected, binary, fixture.digest("ab".repeat(32))),
        )
    }

    @Test
    fun `another reviewed operation cannot supply subtraction evidence`() {
        val minus =
            ReviewedOrdinaryLibraryCallable.admitPrimitive(member("minus", "kotlin/Int"), intResult).flowRefined()
        val plus = ReviewedOrdinaryLibraryCallable.admitPrimitive(member("plus", "kotlin/Int"), intResult).flowRefined()
        val endpoint =
            RelationEndpoint.resolve(fixture.enclosing.lease, fixture.enclosing.scope, minus.target).flowRefined()
        val call = ValueInvocation.fromCompiler(fixture.enclosing, fixture.occurrence.range, endpoint).flowRefined()
        val artifact = ReviewedLibraryArtifact.admit(plus, binary, sources).flowRefined()
        assertEquals(
            Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
            ReviewedCallValueBinding.admit(call, minus, artifact),
        )
    }

    private fun member(name: String, parameter: String) =
        fixture.target(
            qualified = "kotlin.Int.$name",
            receiver = null,
            typeParameters = 0,
            parameters = listOf(parameter),
            file =
                SymbolDiscoveryFileIdentity.External(
                    DetachedVirtualFileUrl.parse("jar:///stdlib.jar!/kotlin/kotlin.kotlin_builtins").flowRefined()
                ),
        )
}
