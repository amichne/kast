package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fixed workspace relation membership; no checkout, native index, PSI, compiler, bytes, or clock effects. */
class RelationPathOwnershipGrowthTest {
    @Test
    fun `irrelevant module growth cannot grow fixed file ownership work`() {
        val target = Path.of("/workspace/target/src")
        val excluded = target.resolve("generated")
        val queries =
            listOf(target.resolve("Target.kt"), excluded.resolve("Generated.kt"), Path.of("/outside/Other.kt"))
        val oracle = listOf(true, false, false)
        val bounds = queries.sumOf { it.nameCount + 1 }
        val violations = mutableListOf<String>()
        for (irrelevant in listOf(0, 8, 32, 128)) {
            val unrelated = (0 until irrelevant).map { Path.of("/workspace/other$it/src") }
            val roots = ObservedRoots(listOf(target, excluded) + unrelated)
            val policy = RelationPathPolicy.SourceRoots(listOf(target) + unrelated, roots)
            // Preparation can examine the complete model once; membership work starts after construction.
            roots.visits = 0
            repeat(2) { pass ->
                val observation = ProbeObservation()
                assertEquals(oracle, queries.map { policy.contains(it, observation) })
                assertEquals(7, observation.probes, "Two deepest-owner probes per owned file, three for the outsider")
                val visits = roots.visits
                println(
                    "OWNERSHIP_WORK irrelevant=$irrelevant pass=$pass rootVisits=$visits ancestorProbes=${observation.probes} bound=$bounds"
                )
                if (visits > bounds) violations += "$irrelevant:$pass:$visits"
                roots.visits = 0
            }
        }
        assertTrue(violations.isEmpty(), "Fixed paths exceeded their ancestor bound: $violations")
    }

    @Test
    fun `deepest owner exclusion retains ambiguity and read-boundary rejection`() {
        val outer = Path.of("/workspace/src")
        val nested = outer.resolve("test")
        val queries = listOf(outer.resolve("Main.kt"), nested.resolve("Test.kt"), Path.of("/unowned/Other.kt"))
        val oracle =
            listOf(
                RelationProviderScopeAdmission.UNAVAILABLE,
                RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED,
                RelationProviderScopeAdmission.UNAVAILABLE,
            )
        for (irrelevant in listOf(0, 8, 32, 128)) {
            val unrelated = (0 until irrelevant).map { Path.of("/workspace/other$it/src") }
            val roots = ObservedRoots(listOf(outer, nested) + unrelated)
            val policy = RelationPathPolicy.SourceRoots(listOf(outer) + unrelated, roots)
            val observation = ProbeObservation()
            val membership = RelationSourceDomainMembership(policy, roots, { true }, observation)
            roots.visits = 0
            assertEquals(oracle, queries.map { membership.classifyExcluded(IntellijRelationNativePath.classify(it)) })
            assertEquals(0, roots.visits)
            assertEquals(11, observation.probes)
            val ambiguous = RelationSourceDomainMembership(policy, listOf(outer, nested, nested), { true })
            assertEquals(
                RelationProviderScopeAdmission.UNAVAILABLE,
                ambiguous.classifyExcluded(IntellijRelationNativePath.classify(queries[1])),
            )
            assertEquals(
                RelationProviderScopeAdmission.UNAVAILABLE,
                membership.classifyExcluded(IntellijRelationNativePath.Relative),
            )
        }
    }

    private class ProbeObservation : IntellijReadObservation {
        var probes = 0

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            assertEquals(IntellijReadCounter.RELATION_PATH_OWNERSHIP_PROBES, counter)
            assertEquals(IntellijReadContributor.NONE, contributor)
            probes += amount
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
            error("Membership cannot manufacture a termination: $reason")
        }
    }

    private class ObservedRoots(private val values: List<Path>) : AbstractList<Path>() {
        var visits = 0
        override val size: Int
            get() = values.size

        override fun get(index: Int): Path {
            visits++
            return values[index]
        }
    }
}
