package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.impl.VirtualFileEnumeration
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationLimitation
import io.github.amichne.kast.relation.contract.RelationProviderLocator
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Production scope/allowance rules with case-owned SDK observations; no native-index cancellation claim. */
internal class NativeRelationSearchScopeTest : RelationFileEnumerationFixture() {
    @Test
    fun `expired excluded files stop before delegate access and retain finite time coverage`() {
        var now = 0L
        var delegateCalls = 0
        val observation = Observation()
        val allowance = IntellijRelationAllowance { now }
        val collector = IntellijRelationCollector(request, observation = observation, allowance = allowance)
        val admission = NativeRelationScopeAdmission(observation) { collector.admitProviderCallback {} }
        val scope =
            admission.wrap(
                nativeScope {
                    delegateCalls++
                    false
                }
            )
        now = request.budget.resources.elapsedTimeLimit.value * 1_000_000L
        assertThrows<NativeRelationScopeStopped> { scope.contains(a) }
        assertEquals(0, delegateCalls)
        assertEquals(0L, allowance.examined)
        val inventory =
            IntellijRelationInventory<RelationProviderLocator.Reference>(collector, ReadLimits.Default, observation)
        assertSame(
            RelationInventoryPreparation.Unavailable,
            inventory.finish(false) { error("Stopped native search cannot publish an inventory") },
        )
        val result = collector.finish(IntellijRelationTermination.Terminal) as RelationCompilation.Qualified
        assertTrue(RelationLimitation.TIME_LIMIT_REACHED in result.coverage.limitations)
        assertTrue(RelationLimitation.PARTITION_INVENTORY_UNAVAILABLE in result.coverage.limitations)
        assertEquals(1, observation.halted)
        assertEquals(IntellijReadCallOutcome.CANCELLED, observation.finished.last().second)
        assertEquals(
            listOf(IntellijReadTermination.TIME_LIMIT, IntellijReadTermination.RELATION_PROVIDER_INCOMPLETE),
            observation.terminations,
        )
    }

    @Test
    fun `module library and ordering filters retain their delegate decisions until expiry`() {
        var now = 0L
        val observation = Observation()
        val collector = IntellijRelationCollector(request, { now }, observation)
        val module = RelationScopeSdkFixture().module("app.main")
        val calls = mutableListOf<String>()
        val base =
            object : GlobalSearchScope() {
                override fun contains(file: VirtualFile): Boolean = error("Unexpected file access")

                override fun isSearchInModuleContent(value: Module): Boolean {
                    assertSame(module, value)
                    calls += "module"
                    return true
                }

                override fun isSearchInModuleContent(value: Module, testSources: Boolean): Boolean {
                    assertSame(module, value)
                    assertTrue(testSources)
                    calls += "module-test"
                    return false
                }

                override fun isSearchInLibraries(): Boolean {
                    calls += "library"
                    return true
                }

                override fun isForceSearchingInLibrarySources(): Boolean {
                    calls += "library-source"
                    return false
                }

                override fun compare(first: VirtualFile, second: VirtualFile): Int {
                    assertSame(a, first)
                    assertSame(b, second)
                    calls += "compare"
                    return -1
                }
            }
        val scope = NativeRelationScopeAdmission(observation) { collector.admitProviderCallback {} }.wrap(base)
        val filters =
            listOf<() -> Unit>(
                { assertTrue(scope.isSearchInModuleContent(module)) },
                { assertFalse(scope.isSearchInModuleContent(module, true)) },
                { assertTrue(scope.isSearchInLibraries) },
                { assertFalse(scope.isForceSearchingInLibrarySources) },
                { assertEquals(-1, scope.compare(a, b)) },
            )
        filters.forEach { it() }
        assertEquals(listOf("module", "module-test", "library", "library-source", "compare"), calls)
        assertEquals(6, observation.admitted)
        assertTrue(observation.finished.all { it.second == IntellijReadCallOutcome.RETURNED })
        now = request.budget.resources.elapsedTimeLimit.value * 1_000_000L
        filters.forEach { assertThrows<NativeRelationScopeStopped> { it() } }
        assertEquals(5, calls.size)
        assertEquals(5, observation.halted)
        assertEquals(listOf(IntellijReadTermination.TIME_LIMIT), observation.terminations)
    }

    @Test
    fun `unrelated exclusions do not consume eligible work or candidate capacity`() {
        val allowance = IntellijRelationAllowance { 0L }
        val collector = IntellijRelationCollector(request, allowance = allowance)
        var delegateCalls = 0
        val admission =
            NativeRelationScopeAdmission(IntellijReadObservation.None) { collector.admitProviderCallback {} }
        val scope =
            admission.wrap(
                nativeScope {
                    delegateCalls++
                    it === a
                }
            )
        repeat(256) { assertFalse(scope.contains(b)) }
        assertTrue(scope.contains(a))
        assertEquals(257, delegateCalls)
        assertEquals(0L, allowance.examined)
        assertEquals(0, allowance.nativeCandidates)
        assertSame(IntellijRelationProviderEnumerationAdmission.READY, collector.admitProviderCandidate())
    }

    @Test
    fun `complete file identity enumeration survives wrapping and every accessor checks expiry`() {
        for (accessor in 0..2) {
            var now = 0L
            val collector = IntellijRelationCollector(request, clockNanoseconds = { now })
            val original =
                EnumeratedRelationScope(nativeScope { true }, prepare(plan()).complete(), IntellijReadObservation.None)
            val scope =
                NativeRelationScopeAdmission(IntellijReadObservation.None) { collector.admitProviderCallback {} }
                    .wrap(original)
            val enumeration = checkNotNull(VirtualFileEnumeration.extract(scope))
            assertArrayEquals(intArrayOf(11, 12), enumeration.asArray().sortedArray())
            val files = checkNotNull(enumeration.filesIfCollection)
            assertEquals(2, files.size)
            assertTrue(files.any { it === a })
            assertTrue(files.any { it === b })
            assertTrue(enumeration.contains(11))
            assertFalse(enumeration.contains(99))
            now = request.budget.resources.elapsedTimeLimit.value * 1_000_000L
            assertThrows<NativeRelationScopeStopped> {
                when (accessor) {
                    0 -> enumeration.contains(99)
                    1 -> enumeration.asArray()
                    2 -> enumeration.filesIfCollection
                }
            }
        }
    }

    @Test
    fun `file collection materialization checks expiry between excluded memberships`() {
        var now = 0L
        var memberships = 0
        val collector = IntellijRelationCollector(request, clockNanoseconds = { now })
        val original =
            EnumeratedRelationScope(
                nativeScope {
                    memberships++
                    now = request.budget.resources.elapsedTimeLimit.value * 1_000_000L
                    false
                },
                prepare(plan()).complete(),
                IntellijReadObservation.None,
            )
        val scope =
            NativeRelationScopeAdmission(IntellijReadObservation.None) { collector.admitProviderCallback {} }
                .wrap(original)
        val enumeration = checkNotNull(VirtualFileEnumeration.extract(scope))
        assertThrows<NativeRelationScopeStopped> { enumeration.filesIfCollection }
        assertEquals(1, memberships)
        val result = collector.finish(IntellijRelationTermination.Terminal) as RelationCompilation.Qualified
        assertTrue(RelationLimitation.TIME_LIMIT_REACHED in result.coverage.limitations)
        assertEquals(0L, result.batch.examinedWorkUnits.value)
    }

    @Test
    fun `SDK intersections preserve the complete identity superset and the original admission`() {
        var now = 0L
        val collector = IntellijRelationCollector(request, clockNanoseconds = { now })
        val original =
            EnumeratedRelationScope(nativeScope { true }, prepare(plan()).complete(), IntellijReadObservation.None)
        val admission =
            NativeRelationScopeAdmission(IntellijReadObservation.None) { collector.admitProviderCallback {} }
        val narrowed = admission.wrap(original).intersectWith(nativeScope { false })
        val enumeration = checkNotNull(VirtualFileEnumeration.extract(narrowed))
        assertArrayEquals(intArrayOf(11, 12), enumeration.asArray().sortedArray())
        assertTrue(checkNotNull(enumeration.filesIfCollection).isEmpty())
        assertFalse(narrowed.contains(a))
        now = request.budget.resources.elapsedTimeLimit.value * 1_000_000L
        assertThrows<NativeRelationScopeStopped> { enumeration.asArray() }
        assertThrows<NativeRelationScopeStopped> { narrowed.intersectWith(nativeScope { true }) }
    }

    @Test
    fun `external cancellation and fixture failure propagate without becoming owned scope stops`() {
        for (failure in listOf(ProcessCanceledException(), IllegalStateException("Unexpected fixture access"))) {
            val admission = NativeRelationScopeAdmission(IntellijReadObservation.None) { throw failure }
            val caught = assertThrows<RuntimeException> { admission.wrap(nativeScope { true }) }
            assertSame(failure, caught)
        }
    }

    private class Observation : IntellijReadObservation {
        var admitted = 0
        var halted = 0
        val finished = mutableListOf<Pair<IntellijReadCall, IntellijReadCallOutcome>>()
        val terminations = mutableListOf<IntellijReadTermination>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            if (counter == IntellijReadCounter.RELATION_SCOPE_ADMISSIONS_HALTED) halted += amount
            if (counter == IntellijReadCounter.RELATION_SCOPE_ADMISSIONS_READY) admitted += amount
        }

        override fun enterCall(call: IntellijReadCall): IntellijReadCallScope =
            object : IntellijReadCallScope {
                private var closed = false

                override fun finish(outcome: IntellijReadCallOutcome) {
                    check(!closed)
                    closed = true
                    finished += call to outcome
                }
            }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) {
            terminations += reason
        }
    }
}
