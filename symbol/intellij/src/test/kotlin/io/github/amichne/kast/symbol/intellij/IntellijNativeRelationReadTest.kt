package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.symbol.contract.*
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.CurrentWorkspaceEpoch
import io.github.amichne.kast.workspace.contract.CurrentWorkspaceReadLease
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

    private fun assertQualified(
        fixture: Fixture,
        limitation: NativeRelationLimitation,
        knownMinimum: Int,
    ) {
        val qualified = fixture.query.read(fixture.compiledScope, fixture.request).outcome()
            as NativeRelationOutcome.Qualified
        assertEquals(knownMinimum, qualified.knownMinimumCount.value)
        assertTrue(limitation in qualified.limitations.values)
    }

    private fun fixture(
        family: NativeRelationFamily = NativeRelationFamily.REFERENCES,
        resultLimit: Int = 10,
        returnedBytes: Long = 10_000L,
        workLimit: Long = 100L,
        elapsedMillis: Long = 1_000L,
        eventCount: Int = 0,
        terminal: Boolean = true,
        providerLimitations: Set<NativeRelationLimitation> = emptySet(),
        providerFails: Boolean = false,
        searchRejection: IntellijNativeRelationSearchRejection? = null,
        environmentState: () -> IntellijDiscoveryEnvironmentState = {
            IntellijDiscoveryEnvironmentState.READY
        },
        cancellationCheck: () -> Unit = {},
        clock: IntellijRelationNanoClock = StepClock(),
    ): Fixture {
        val request = request(family, resultLimit, returnedBytes, workLimit, elapsedMillis)
        val scope = object : GlobalSearchScope() {
            override fun contains(file: VirtualFile): Boolean = true

            override fun isSearchInModuleContent(aModule: Module): Boolean = true

            override fun isSearchInLibraries(): Boolean = false
        }
        val compiledScope = CompiledIntellijSearchScope(
            lease = request.selector.lease,
            scope = request.selector.scope,
            sourceRoots = emptyList(),
            nativeScope = scope,
        )
        val observedScopes = mutableListOf<CompiledIntellijSearchScope>()
        val observedFamilies = mutableListOf<NativeRelationFamily>()
        val search = IntellijNativeRelationSearch { compiled, relationRequest, consumer ->
            observedScopes += compiled
            observedFamilies += relationRequest.family
            if (providerFails) {
                error("provider failed")
            }
            if (searchRejection != null) {
                IntellijNativeRelationSearchResult.Rejected(searchRejection)
            } else {
                var providerTerminal = terminal
                for (index in 0 until eventCount) {
                    if (!consumer(FakeEvent(index))) {
                        providerTerminal = false
                        break
                    }
                }
                if (providerTerminal) {
                    IntellijNativeRelationSearchResult.Terminal(providerLimitations)
                } else {
                    IntellijNativeRelationSearchResult.Halted(providerLimitations)
                }
            }
        }
        val projector = IntellijRelationFactProjector { relationRequest, event ->
            val fake = event as FakeEvent
            val related = evidence(
                path = "/workspace/src/Related" + fake.index + ".kt",
                name = "sameName",
                start = 100 + fake.index * 20,
            )
            NativeRelationFact.create(
                subject = relationRequest.selector,
                family = relationRequest.family,
                related = ExactRelationEndpoint.bind(relationRequest.selector, related),
                occurrence = NativeRelationOccurrence.fromBoundary(
                    file = fileIdentity("/workspace/src/Usage" + fake.index + ".kt"),
                    rawStartInclusive = 200 + fake.index * 20,
                    rawEndExclusive = 205 + fake.index * 20,
                ).refined(),
            ).mapFailure { IntellijRelationProjectionFailure.UNSUPPORTED_ITEM }
        }
        return Fixture(
            request,
            compiledScope,
            observedScopes,
            observedFamilies,
            IntellijNativeRelationQuery(
                search,
                projector,
                environmentState,
                cancellationCheck,
                clock,
            ),
        )
    }

    private fun request(
        family: NativeRelationFamily,
        resultLimit: Int,
        returnedBytes: Long,
        workLimit: Long,
        elapsedMillis: Long,
    ): NativeRelationRequest = NativeRelationRequest(
        selector = selector(),
        family = family,
        budget = NativeRelationBudget(
            resources = ResourceBudget(
                resultLimit = ResultLimit.parse(resultLimit).refined(),
                workUnitLimit = WorkUnitLimit.parse(workLimit).refined(),
                elapsedTimeLimit = ElapsedTimeLimitMillis.parse(elapsedMillis).refined(),
            ),
            returnedBytes = NativeRelationByteLimit.parse(returnedBytes).refined(),
        ),
    )

    private fun selector(): ExactDeclarationSelector {
        val scope = SymbolSearchScope.Workspace(
            SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.INCLUDE,
            SymbolLibraryPolicy.EXCLUDE,
        )
        val candidate = SymbolDiscoveryCandidate.fromBoundary(
            SymbolDiscoveryKind.SYMBOL,
            "service",
            lease(),
            Path.of("/workspace/src/Service.kt"),
            "file:///workspace/src/Service.kt",
            7,
        ).refined()
        val discoveryRequest = SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(lease(), scope),
            SymbolDiscoveryKind.SYMBOL,
            SymbolDiscoveryPattern.parse("service").refined(),
            SymbolDiscoveryBudget(
                ResourceBudget(
                    ResultLimit.parse(1).refined(),
                    WorkUnitLimit.parse(10L).refined(),
                    ElapsedTimeLimitMillis.parse(100L).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(1_000L).refined(),
            ),
        )
        val batch = SymbolDiscoveryBatch.create(
            discoveryRequest,
            listOf(candidate),
            candidate.projectedUtf8Size(),
            SymbolDiscoveryWorkCount.parse(1L).refined(),
            SymbolDiscoveryTimings(
                SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
                SymbolDiscoveryElapsedNanoseconds.parse(1L).refined(),
            ),
        ).refined()
        return ExactDeclarationSelector.issue(
            SymbolDiscoverySelection.select(batch, 0).refined(),
            evidence("/workspace/src/Service.kt", "service", 7),
        ).refined()
    }

    private fun evidence(
        path: String,
        name: String,
        start: Int,
    ): ExactDeclarationEvidence = ExactDeclarationEvidence.fromBoundary(
        fileIdentity(path),
        start,
        start + 10,
        name,
        "sample.$name",
        "sample.FakeDeclaration",
    ).refined()

    private fun fileIdentity(path: String) =
        SymbolDiscoveryFileIdentity.fromBoundary(
            root(),
            Path.of(path),
            "file://$path",
        ).refined()

    private fun root(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    private fun lease(): CurrentWorkspaceReadLease =
        CurrentWorkspaceReadLease(root(), CurrentWorkspaceEpoch.parse(21L).refined())

    private data class FakeEvent(val index: Int) : IntellijNativeRelationEvent

    private data class Fixture(
        val request: NativeRelationRequest,
        val compiledScope: CompiledIntellijSearchScope,
        val observedScopes: List<CompiledIntellijSearchScope>,
        val observedFamilies: List<NativeRelationFamily>,
        val query: IntellijNativeRelationQuery,
    )

    private class StepClock(
        private val step: Long = 100L,
    ) : IntellijRelationNanoClock {
        private var current = 0L

        override fun now(): Long = current.also { current += step }
    }

    private fun IntellijNativeRelationExecution.outcome(): NativeRelationOutcome =
        (this as IntellijNativeRelationExecution.Produced).outcome

    private fun <Strong, Failure, OtherFailure> Refinement<Strong, Failure>.mapFailure(
        transform: (Failure) -> OtherFailure,
    ): Refinement<Strong, OtherFailure> = when (this) {
        is Refinement.Refined -> Refinement.Refined(value)
        is Refinement.Rejected -> Refinement.Rejected(transform(failure))
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
