package io.github.amichne.kast.relation.intellij

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RelationSourceDomainMembershipTest {
    @Test
    fun `relation source policy requires a deepest readable owner`() {
        val parent = Path.of("/workspace/src")
        val nested = parent.resolve("test")
        val authored = parent.resolve("Main.kt")
        val foreign = nested.resolve("Test.kt")
        val parentOnly = RelationPathPolicy.SourceRoots(listOf(parent), listOf(parent, nested))
        val both = RelationPathPolicy.SourceRoots(listOf(parent, nested), listOf(parent, nested))
        assertEquals(listOf(true, false), listOf(authored, foreign).map(parentOnly::contains))
        assertEquals(listOf(true, true), listOf(authored, foreign).map(both::contains))
    }

    @Test
    fun `native rejection proves exclusion only with an unambiguous imported owner outside the domain`() {
        val production = Path.of("/workspace/src/main")
        val tests = Path.of("/workspace/src/test")
        val membership =
            RelationSourceDomainMembership(
                RelationPathPolicy.SourceRoots(listOf(production), listOf(production, tests)),
                listOf(production, tests),
                directoryAdmission = { true },
            )
        assertEquals(
            RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED,
            membership.classifyExcluded(IntellijRelationNativePath.classify(tests.resolve("Test.kt"))),
        )
        assertEquals(
            RelationProviderScopeAdmission.UNAVAILABLE,
            membership.classifyExcluded(IntellijRelationNativePath.classify(production.resolve("Main.kt"))),
        )
        assertEquals(
            RelationProviderScopeAdmission.UNAVAILABLE,
            membership.classifyExcluded(IntellijRelationNativePath.classify(Path.of("/unowned/Other.kt"))),
        )
        assertEquals(
            RelationProviderScopeAdmission.UNAVAILABLE,
            membership.classifyExcluded(IntellijRelationNativePath.Unavailable),
        )
        val ambiguous =
            RelationSourceDomainMembership(
                RelationPathPolicy.SourceRoots(listOf(production), listOf(production, tests)),
                listOf(production, tests, tests),
                directoryAdmission = { true },
            )
        assertEquals(
            RelationProviderScopeAdmission.UNAVAILABLE,
            ambiguous.classifyExcluded(IntellijRelationNativePath.classify(tests.resolve("Test.kt"))),
        )
        val directory =
            RelationSourceDomainMembership(
                RelationPathPolicy.SourceRoots(listOf(production), listOf(production)),
                listOf(production),
                directoryAdmission = { it.startsWith(production.resolve("eligible")) },
            )
        assertEquals(
            RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED,
            directory.classifyExcluded(IntellijRelationNativePath.classify(production.resolve("Other.kt"))),
        )
    }
}
