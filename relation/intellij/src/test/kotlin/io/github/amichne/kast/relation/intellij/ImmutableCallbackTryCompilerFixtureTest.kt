package io.github.amichne.kast.relation.intellij

import fixture.immutable.suppliers.directTryEntry
import fixture.immutable.suppliers.factoryTryEntry
import fixture.immutable.suppliers.finallyFactoryEntry
import fixture.immutable.suppliers.localTryEntry
import fixture.immutable.suppliers.nestedTryEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Real Kotlin compiler/runtime control; this does not establish Kast's native K2 query behavior. */
class ImmutableCallbackTryCompilerFixtureTest {
    @Test
    fun `authored normal branch fixtures compile and finally counterexample replaces its return`() {
        assertEquals("alpha", directTryEntry())
        assertEquals("alpha", localTryEntry())
        assertEquals("alpha", nestedTryEntry())
        assertEquals("alpha", factoryTryEntry())
        assertEquals("beta", finallyFactoryEntry())
    }
}
