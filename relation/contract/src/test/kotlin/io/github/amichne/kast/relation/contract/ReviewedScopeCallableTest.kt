package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewedScopeCallableTest {
    @Test
    fun `same named source and wrong receiver never establish let correspondence`() {
        val fixture = ReviewedValueFixture()
        val source = fixture.target(qualified = "kotlin.let", receiver = "T", file = fixture.enclosing.file)
        val wrongReceiver =
            fixture.target(qualified = "kotlin.let", receiver = "kotlin/collections/Iterable<T>", file = standardFile())
        for (target in listOf(source, wrongReceiver)) {
            assertEquals(
                Refinement.Rejected(ReviewedCallbackValueFailure.CALLABLE_MISMATCH),
                ReviewedLibraryCallable.admit(target, fixture.position(0)),
            )
        }
    }

    @Test
    fun `selected standard let has a separate reviewed return rule`() {
        val fixture = ReviewedValueFixture()
        val target = fixture.target(qualified = "kotlin.let", receiver = "T", file = standardFile())
        val result = ReviewedLibraryCallable.admit(target, fixture.position(0))
        assertTrue(
            result is Refinement.Refined,
            "The exact let overload must admit reviewed return correspondence: $result",
        )
    }

    private fun standardFile() =
        SymbolDiscoveryFileIdentity.External(
            DetachedVirtualFileUrl.parse("jar:///kotlin-stdlib-2.3.0.jar!/kotlin/StandardKt.class").flowRefined()
        )
}
