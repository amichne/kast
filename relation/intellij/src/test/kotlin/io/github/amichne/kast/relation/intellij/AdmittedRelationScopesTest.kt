package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationCompilerRejection
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows

/** Real production selection and call scopes, with detached input observations; no native compiler-success claim. */
class AdmittedRelationScopesTest {
    @Test
    fun `identical scope obligations execute once and retain the same proof for every relation meaning`() {
        for (meaning in RelationMeaning.all) {
            val base = RelationReadTest().request(meaning)
            for (boundary in
                listOf(
                    RelationSearchBoundary.RETAINED_SUBJECT,
                    RelationSearchBoundary.WORKSPACE_EXPANSION,
                    RelationSearchBoundary.Explicit((base.subject.scope as SymbolSearchScope.Workspace).copy()),
                )) {
                val request = request(base, boundary)
                val observation = Observation()
                val inputs = mutableListOf<ScopeInputObservation>()
                val result =
                    assertInstanceOf<Refinement.Refined<AdmittedRelationScopes<ScopeInputObservation>>>(
                        AdmittedRelationScopes.compile(request, observation) { scope, constraints ->
                            check(inputs.isEmpty()) { "A second native compilation would violate exact input reuse" }
                            Refinement.Refined(ScopeInputObservation(scope, constraints).also(inputs::add))
                        }
                    )
                assertEquals(listOf(ScopeInputObservation(base.subject.scope, base.subject.constraints)), inputs)
                assertSame(inputs.single(), result.value.search)
                assertSame(result.value.search, result.value.subject)
                assertEquals(1, observation.entered)
                assertEquals(listOf(IntellijReadCallOutcome.RETURNED), observation.outcomes)
            }
        }
    }

    @Test
    fun `different source generated and library policies require two compilations`() {
        val base = RelationReadTest().request(RelationMeaning.Callers)
        val scope = base.subject.scope as SymbolSearchScope.Workspace
        for (selected in
            listOf(
                scope.copy(sourceKinds = SymbolSourceKindPolicy.TEST_ONLY),
                scope.copy(generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE),
                scope.copy(libraries = SymbolLibraryPolicy.INCLUDE),
            )) {
            assertDistinct(base, RelationSearchBoundary.Explicit(selected))
        }
    }

    @Test
    fun `equal scopes with different directory or source set constraints require two compilations`() {
        val base = RelationReadTest().request(RelationMeaning.References)
        val directory =
            SymbolDiscoveryDirectoryConstraint(
                (SymbolDiscoveryDirectory.parse("src/main") as Refinement.Refined).value,
                SymbolDiscoveryContainment.DESCENDANTS,
            )
        val sourceSets =
            (SymbolDiscoverySourceSets.Exact.from(
                    setOf((WorkspaceSourceSetName.parse("main") as Refinement.Refined).value)
                ) as Refinement.Refined)
                .value
        assertDistinct(base, RelationSearchBoundary.Explicit(base.subject.scope, directory = directory))
        assertDistinct(base, RelationSearchBoundary.Explicit(base.subject.scope, sourceSets = sourceSets))
    }

    @Test
    fun `a rejected compilation preserves its failure and never enters an unused compilation`() {
        val base = RelationReadTest().request(RelationMeaning.References)
        val request =
            request(
                base,
                RelationSearchBoundary.Explicit(
                    (base.subject.scope as SymbolSearchScope.Workspace).copy(libraries = SymbolLibraryPolicy.INCLUDE)
                ),
            )
        for (rejectAt in listOf(1, 2)) {
            val observation = Observation()
            val rejection = Refinement.Rejected(RelationCompilerRejection.SCOPE_REJECTED)
            var invocations = 0
            val result =
                AdmittedRelationScopes.compile(request, observation) { scope, constraints ->
                    invocations++
                    check(invocations <= rejectAt)
                    if (invocations == rejectAt) rejection
                    else Refinement.Refined(ScopeInputObservation(scope, constraints))
                }
            assertSame(rejection, result)
            assertEquals(rejectAt, observation.entered)
            assertEquals(List(rejectAt) { IntellijReadCallOutcome.RETURNED }, observation.outcomes)
        }
    }

    @Test
    fun `cancellation remains one cancelled call and cannot manufacture admitted scopes`() {
        val observation = Observation()
        val cancellation = CancellationException("owned cancellation")
        assertSame(
            cancellation,
            assertThrows<CancellationException> {
                AdmittedRelationScopes.compile<Unit>(
                    RelationReadTest().request(RelationMeaning.References),
                    observation,
                ) { _, _ ->
                    throw cancellation
                }
            },
        )
        assertEquals(1, observation.entered)
        assertEquals(listOf(IntellijReadCallOutcome.CANCELLED), observation.outcomes)
    }

    private fun assertDistinct(base: RelationRequest, boundary: RelationSearchBoundary) {
        val request = request(base, boundary)
        val observation = Observation()
        val inputs = mutableListOf<ScopeInputObservation>()
        val result =
            assertInstanceOf<Refinement.Refined<AdmittedRelationScopes<ScopeInputObservation>>>(
                AdmittedRelationScopes.compile(request, observation) { scope, constraints ->
                    check(inputs.size < 2)
                    Refinement.Refined(ScopeInputObservation(scope, constraints).also(inputs::add))
                }
            )
        assertEquals(
            listOf(
                ScopeInputObservation(request.searchScope, request.searchConstraints),
                ScopeInputObservation(base.subject.scope, base.subject.constraints),
            ),
            inputs,
        )
        assertNotSame(result.value.search, result.value.subject)
        assertEquals(2, observation.entered)
        assertEquals(List(2) { IntellijReadCallOutcome.RETURNED }, observation.outcomes)
    }

    private fun request(base: RelationRequest, boundary: RelationSearchBoundary) =
        RelationRequest.start(base.subject, base.meaning, base.budget, boundary)

    private data class ScopeInputObservation(val scope: SymbolSearchScope, val constraints: SymbolDiscoveryConstraints)

    private class Observation : IntellijReadObservation {
        var entered = 0
        val outcomes = mutableListOf<IntellijReadCallOutcome>()

        override fun enterCall(call: IntellijReadCall): IntellijReadCallScope {
            assertEquals(IntellijReadCall.RELATION_SCOPE_COMPILE, call)
            entered++
            return object : IntellijReadCallScope {
                override fun finish(outcome: IntellijReadCallOutcome) {
                    outcomes += outcome
                }
            }
        }

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) =
            error("Unexpected counter at scope compilation")

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) =
            error("Unexpected termination at scope compilation")
    }
}
