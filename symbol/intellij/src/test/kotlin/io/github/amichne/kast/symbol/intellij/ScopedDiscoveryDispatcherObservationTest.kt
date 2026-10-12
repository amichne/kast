package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.progress.ProcessCanceledException
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.outcome
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.IntellijReadSearch
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ScopedDiscoveryDispatcherObservationTest {
    @Test
    fun `indexed providers expose excluded scope probes before callbacks without spending candidate capacity`(
        @TempDir home: Path
    ) =
        withParser(home) { project ->
            val fixture = ScopedDiscoveryDispatcherFixture(project, home.resolve("source"))
            val scope = fixture.scope()
            val observation = fixture.observation
            var probesBeforeFirstCallback = 0
            var probesInsideIndex = 0
            val indexes =
                fixture.Indexes(
                    scope.nativeScope,
                    { native, accept ->
                        val initialProbes = observation.calls[IntellijReadCall.DISCOVERY_SCOPE_FILE_MEMBERSHIP] ?: 0
                        repeat(100_000) { assertFalse(native.contains(fixture.excludedFile.virtualFile)) }
                        fixture.now = 25L
                        fixture.admittedFiles.forEachIndexed { index, file ->
                            assertTrue(native.contains(file.virtualFile))
                            if (index == 0)
                                probesBeforeFirstCallback =
                                    observation.calls.getValue(IntellijReadCall.DISCOVERY_SCOPE_FILE_MEMBERSHIP) -
                                        initialProbes
                            assertTrue(accept(file.virtualFile))
                        }
                        probesInsideIndex =
                            observation.calls.getValue(IntellijReadCall.DISCOVERY_SCOPE_FILE_MEMBERSHIP) - initialProbes
                        true
                    },
                )
            val outcome =
                fixture
                    .query()
                    .discoverExactIndexedDeclarations(project, scope, fixture.request, indexes = indexes)
                    .outcome()
            indexes.assertConsumed()
            assertTrue(outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(listOf("Target"), outcome.batch().candidates.map { it.name.value })
            assertEquals(1L, outcome.batch().examinedWorkUnits.value)
            assertEquals(100_001, probesBeforeFirstCallback)
            assertEquals(100_028, probesInsideIndex)
            assertEquals(100_000, observation.counters[IntellijReadCounter.DISCOVERY_SCOPE_FILES_EXCLUDED])
            assertEquals(1, observation.calls[IntellijReadCall.NAMED_DECLARATION_INDEX])
            assertEquals(1, observation.calls[IntellijReadCall.SCOPED_FILE_INDEX])
            assertEquals(14, observation.calls[IntellijReadCall.SCOPED_FILE_CALLBACK])
            assertEquals(14, observation.calls[IntellijReadCall.PSI_FIND_FILE])
            val search = observation.searches.single()
            assertEquals(IntellijReadSearch.SCOPED_FILES, search.kind)
            assertEquals(14, search.callbacks)
            assertEquals(25L, search.firstCallback!! - search.started)
            assertEquals(IntellijReadCallOutcome.RETURNED, search.outcome)
            assertEquals(listOf("named", "named-return", "files", "files-return"), indexes.effects)
        }

    @Test
    fun `observations preserve exact indexed and scoped duplicate identities and complete coverage`(
        @TempDir home: Path
    ) =
        withParser(home) { project ->
            val fixture = ScopedDiscoveryDispatcherFixture(project, home.resolve("source"))
            fun execute(observation: IntellijReadObservation): SymbolDiscoveryOutcome {
                val scope = fixture.scope(observation)
                val indexes =
                    fixture.Indexes(
                        scope.nativeScope,
                        { _, accept ->
                            fixture.admittedFiles.all { accept(it.virtualFile) }
                        },
                    )
                return fixture
                    .query(observation)
                    .discoverExactIndexedDeclarations(project, scope, fixture.request, indexes = indexes)
                    .outcome()
                    .also { indexes.assertConsumed() }
            }
            assertEquals(execute(IntellijReadObservation.None), execute(fixture.observation))
        }

    @Test
    fun `empty scoped supply reports returned without a callback and retains indexed evidence`(@TempDir home: Path) =
        withParser(home) { project ->
            val fixture = ScopedDiscoveryDispatcherFixture(project, home.resolve("source"))
            val scope = fixture.scope()
            val indexes = fixture.Indexes(scope.nativeScope, { _, _ -> true })
            val outcome =
                fixture
                    .query()
                    .discoverExactIndexedDeclarations(project, scope, fixture.request, indexes = indexes)
                    .outcome()
            indexes.assertConsumed()
            assertTrue(outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(listOf("Target"), outcome.batch().candidates.map { it.name.value })
            assertNoCallback(fixture, IntellijReadCallOutcome.RETURNED)
        }

    @Test
    fun `pre-callback cancellation propagates original identity and closes cancelled search`(@TempDir home: Path) =
        withParser(home) { project ->
            val fixture = ScopedDiscoveryDispatcherFixture(project, home.resolve("source"))
            val scope = fixture.scope()
            val cancelled = ProcessCanceledException()
            val indexes = fixture.Indexes(scope.nativeScope, { _, _ -> throw cancelled })
            assertSame(
                cancelled,
                assertThrows<ProcessCanceledException> {
                    fixture.query().discoverExactIndexedDeclarations(project, scope, fixture.request, indexes = indexes)
                },
            )
            indexes.assertConsumed()
            assertNoCallback(fixture, IntellijReadCallOutcome.CANCELLED)
        }

    @Test
    fun `first excluded callback is observed before cancellation or classification`(@TempDir home: Path) =
        withParser(home) { project ->
            val fixture = ScopedDiscoveryDispatcherFixture(project, home.resolve("source"))
            val scope = fixture.scope()
            val cancelled = ProcessCanceledException()
            var cancel = false
            val indexes =
                fixture.Indexes(
                    scope.nativeScope,
                    { _, accept ->
                        fixture.now = 31L
                        cancel = true
                        accept(fixture.excludedFile.virtualFile)
                    },
                )
            val query = fixture.query(cancellationCheck = { if (cancel) throw cancelled })
            assertSame(
                cancelled,
                assertThrows<ProcessCanceledException> {
                    query.discoverExactIndexedDeclarations(project, scope, fixture.request, indexes = indexes)
                },
            )
            indexes.assertConsumed()
            val search = fixture.observation.searches.single()
            assertEquals(1, search.callbacks)
            assertEquals(31L, search.firstCallback!! - search.started)
            assertEquals(IntellijReadCallOutcome.CANCELLED, search.outcome)
            assertEquals(1, fixture.observation.calls[IntellijReadCall.SCOPED_FILE_CALLBACK])
        }

    @Test
    fun `pre-callback provider failure preserves indexed partial evidence and records failed search`(
        @TempDir home: Path
    ) =
        withParser(home) { project ->
            val fixture = ScopedDiscoveryDispatcherFixture(project, home.resolve("source"))
            val scope = fixture.scope()
            val indexes =
                fixture.Indexes(scope.nativeScope, { _, _ -> throw IllegalStateException("scripted index failure") })
            val outcome =
                fixture
                    .query()
                    .discoverExactIndexedDeclarations(project, scope, fixture.request, indexes = indexes)
                    .outcome()
            indexes.assertConsumed()
            assertTrue(outcome is SymbolDiscoveryOutcome.Qualified)
            assertEquals(listOf("Target"), outcome.batch().candidates.map { it.name.value })
            assertEquals(
                setOf(SymbolDiscoveryQualification.PROVIDER_FAILURE),
                (outcome as SymbolDiscoveryOutcome.Qualified).qualifications.values.toSet(),
            )
            assertNoCallback(fixture, IntellijReadCallOutcome.FAILED)
        }

    @Test
    fun `incomplete named index prevents scoped index entry`(@TempDir home: Path) =
        withParser(home) { project ->
            val fixture = ScopedDiscoveryDispatcherFixture(project, home.resolve("source"))
            val scope = fixture.scope()
            val indexes =
                fixture.Indexes(
                    scope.nativeScope,
                    { _, _ -> throw AssertionError("Unexpected fallback") },
                    completeNamed = false,
                )
            val outcome =
                fixture
                    .query()
                    .discoverExactIndexedDeclarations(project, scope, fixture.request, indexes = indexes)
                    .outcome()
            indexes.assertConsumed(files = 0)
            assertTrue(outcome is SymbolDiscoveryOutcome.Qualified)
            assertTrue(fixture.observation.searches.isEmpty())
            assertEquals(0, fixture.observation.calls[IntellijReadCall.SCOPED_FILE_INDEX] ?: 0)
        }

    private fun assertNoCallback(fixture: ScopedDiscoveryDispatcherFixture, outcome: IntellijReadCallOutcome) {
        val search = fixture.observation.searches.single()
        assertEquals(0, search.callbacks)
        assertEquals(null, search.firstCallback)
        assertEquals(outcome, search.outcome)
    }
}
