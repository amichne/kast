package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.ExactDeclarationEvidence
import io.github.amichne.kast.symbol.contract.ExactDeclarationSelector
import io.github.amichne.kast.symbol.contract.ExactRelationEndpoint
import io.github.amichne.kast.symbol.contract.NativeRelationBudget
import io.github.amichne.kast.symbol.contract.NativeRelationByteLimit
import io.github.amichne.kast.symbol.contract.NativeRelationFact
import io.github.amichne.kast.symbol.contract.NativeRelationFamily
import io.github.amichne.kast.symbol.contract.NativeRelationLimitation
import io.github.amichne.kast.symbol.contract.NativeRelationOccurrence
import io.github.amichne.kast.symbol.contract.NativeRelationOutcome
import io.github.amichne.kast.symbol.contract.NativeRelationRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryElapsedNanoseconds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTimings
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWorkCount
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

class IntellijNativeRelationReadTest {
    @Test
    fun `all one hop families retain exact scope endpoints occurrences and terminal counts`() {
        NativeRelationFamily.entries.forEach { family ->
            val fixture = fixture(family = family, eventCount = 2)
            val outcome = fixture.query.read(fixture.compiledScope, fixture.request).outcome()
            val complete = outcome as NativeRelationOutcome.Complete

            assertEquals(2, complete.exactCount.value)
            assertEquals(2, complete.batch.facts.size)
            assertTrue(complete.batch.facts.all { it.family == family })
            assertTrue(complete.batch.facts.all { it.related.lease == fixture.request.selector.lease })
            assertTrue(complete.batch.facts.all { it.related.scope == fixture.request.selector.scope })
            assertTrue(complete.batch.facts.all { it.occurrence.range.startInclusive >= 200 })
            assertSame(fixture.compiledScope, fixture.observedScopes.single())
            assertEquals(listOf(family), fixture.observedFamilies)
        }
    }

    @Test
    fun `compiled scope cannot diverge from the exact selector scope`() {
        val fixture = fixture()
        val divergent = CompiledIntellijSearchScope(
            lease = fixture.compiledScope.lease,
            scope = (fixture.request.selector.scope as SymbolSearchScope.Workspace).copy(
                libraries = SymbolLibraryPolicy.INCLUDE,
            ),
            sourceRoots = fixture.compiledScope.sourceRoots,
            nativeScope = fixture.compiledScope.nativeScope,
        )

        val rejected = fixture.query.read(divergent, fixture.request)
            as IntellijNativeRelationExecution.Rejected
        assertEquals(IntellijNativeRelationRejection.INTERNAL_INVARIANT, rejected.reason)
        assertTrue(fixture.observedScopes.isEmpty())
    }

    @Test
    fun `record byte work and time limits produce known minimum qualified coverage`() {
        assertQualified(
            fixture(resultLimit = 1, eventCount = 2),
            NativeRelationLimitation.RESULT_LIMIT_REACHED,
            knownMinimum = 1,
        )
        assertQualified(
            fixture(returnedBytes = 1L, eventCount = 1),
            NativeRelationLimitation.BYTE_LIMIT_REACHED,
            knownMinimum = 0,
        )
        assertQualified(
            fixture(workLimit = 1L, eventCount = 2),
            NativeRelationLimitation.WORK_LIMIT_REACHED,
            knownMinimum = 1,
        )
        assertQualified(
            fixture(
                elapsedMillis = 1L,
                eventCount = 1,
                clock = StepClock(1_000_000L),
            ),
            NativeRelationLimitation.TIME_LIMIT_REACHED,
            knownMinimum = 0,
        )
    }

    @Test
    fun `unresolved targets and nonterminal providers never claim absence`() {
        assertQualified(
            fixture(
                eventCount = 0,
                providerLimitations = setOf(NativeRelationLimitation.UNRESOLVED_TARGET),
            ),
            NativeRelationLimitation.UNRESOLVED_TARGET,
            knownMinimum = 0,
        )
        assertQualified(
            fixture(eventCount = 0, terminal = false),
            NativeRelationLimitation.PROVIDER_INCOMPLETE,
            knownMinimum = 0,
        )
    }

    @Test
    fun `dumb transitions provider failure and cancellation remain truthful`() {
        val initialDumb = fixture(
            environmentState = { IntellijDiscoveryEnvironmentState.DUMB },
        )
        assertEquals(
            IntellijNativeRelationRejection.DUMB_MODE,
            (
                initialDumb.query.read(initialDumb.compiledScope, initialDumb.request)
                    as IntellijNativeRelationExecution.Rejected
            ).reason,
        )

        var observations = 0
        val transition = fixture(
            eventCount = 1,
            environmentState = {
                observations += 1
                if (observations == 1) {
                    IntellijDiscoveryEnvironmentState.READY
                } else {
                    IntellijDiscoveryEnvironmentState.DUMB
                }
            },
        )
        assertQualified(
            transition,
            NativeRelationLimitation.DUMB_MODE_TRANSITION,
            knownMinimum = 0,
        )

        assertQualified(
            fixture(providerFails = true),
            NativeRelationLimitation.PROVIDER_FAILURE,
            knownMinimum = 0,
        )
        val cancelled = fixture(
            cancellationCheck = { throw ProcessCanceledException() },
            eventCount = 1,
        )
        assertThrows<ProcessCanceledException> {
            cancelled.query.read(cancelled.compiledScope, cancelled.request)
        }
    }

    @Test
    fun `subject identity rejections remain distinct and produce no facts`() {
        val expected = mapOf(
            IntellijNativeRelationSearchRejection.STALE_SELECTOR to
                IntellijNativeRelationRejection.STALE_SELECTOR,
            IntellijNativeRelationSearchRejection.OUTSIDE_SCOPE to
                IntellijNativeRelationRejection.OUTSIDE_SCOPE,
            IntellijNativeRelationSearchRejection.AMBIGUOUS_SUBJECT to
                IntellijNativeRelationRejection.AMBIGUOUS_SUBJECT,
            IntellijNativeRelationSearchRejection.UNSUPPORTED_SUBJECT to
                IntellijNativeRelationRejection.UNSUPPORTED_SUBJECT,
            IntellijNativeRelationSearchRejection.SELECTOR_CHANGED to
                IntellijNativeRelationRejection.SELECTOR_CHANGED,
        )
        expected.forEach { (native, public) ->
            val fixture = fixture(searchRejection = native)
            assertEquals(
                public,
                (
                    fixture.query.read(fixture.compiledScope, fixture.request)
                        as IntellijNativeRelationExecution.Rejected
                ).reason,
            )
        }
    }

}
