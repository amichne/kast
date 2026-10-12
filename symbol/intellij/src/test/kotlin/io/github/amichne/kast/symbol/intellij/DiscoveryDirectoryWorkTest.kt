package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.outcome
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DiscoveryDirectoryWorkTest {
    @Test
    fun `directory rejects before detached admission and normalizes only during scope construction`(
        @TempDir home: Path
    ) =
        withParser(home) { project ->
            val fixture = ScopedDiscoveryDispatcherFixture(project, home.resolve("source"))
            var expected: SymbolDiscoveryOutcome? = null
            for (excludedCount in listOf(0, 100, 100_000)) {
                val observation = RecordingDiscoveryObservation { 0L }
                var detachedAdmissions = 0
                val scope =
                    fixture.scope(observation, unrelatedRoots = 4095) {
                        detachedAdmissions++
                        true
                    }
                val indexes =
                    fixture.Indexes(
                        scope.nativeScope,
                        { native, accept ->
                            repeat(excludedCount) { assertFalse(native.contains(fixture.excludedFile.virtualFile)) }
                            fixture.admittedFiles.forEach { assertTrue(accept(it.virtualFile)) }
                            true
                        },
                    )
                val outcome =
                    fixture
                        .query(observation)
                        .discoverNative(project, scope, fixture.request, indexes = indexes)
                        .outcome()
                indexes.assertConsumed()
                assertTarget(outcome, home, fixture)
                assertEquals(
                    excludedCount,
                    observation.counters[IntellijReadCounter.DISCOVERY_SCOPE_FILES_EXCLUDED] ?: 0,
                )
                assertEquals(14, observation.calls[IntellijReadCall.SCOPED_FILE_CALLBACK])
                assertEquals(14, observation.calls[IntellijReadCall.PSI_FIND_FILE])
                // The named target has three admissions, followed by fourteen file admissions.
                assertEquals(17, detachedAdmissions)
                assertEquals(17, observation.calls[IntellijReadCall.DISCOVERY_SCOPE_SOURCE_MEMBERSHIP])
                assertEquals(17, observation.calls[IntellijReadCall.DISCOVERY_SCOPE_PATH_OWNERSHIP])
                assertEquals(excludedCount + 17, observation.calls[IntellijReadCall.DISCOVERY_SCOPE_FILE_MEMBERSHIP])
                assertEquals(1, observation.counters[IntellijReadCounter.DISCOVERY_DIRECTORY_PATH_CONVERSIONS])
                assertEquals(1, observation.counters[IntellijReadCounter.CANDIDATES_PROJECTED])
                // Full equality preserves authority, location, range, scope, and coverage across excluded inputs.
                if (expected == null) expected = outcome else assertEquals(expected, outcome)
            }
        }

    private fun assertTarget(outcome: SymbolDiscoveryOutcome, home: Path, fixture: ScopedDiscoveryDispatcherFixture) {
        assertTrue(outcome is SymbolDiscoveryOutcome.Complete)
        assertEquals(listOf("Target"), outcome.batch().candidates.map { it.name.value })
        val candidate = outcome.batch().candidates.single()
        assertEquals(
            home.resolve("source/src/narrow/File0.kt").toRealPath().toString(),
            candidate.location.file.stableValue,
        )
        assertEquals(0, (candidate.location as SymbolDiscoveryCandidateLocation.Declaration).offset.value)
        assertEquals(fixture.request.scope.lease, candidate.lease)
        assertEquals(1L, outcome.batch().examinedWorkUnits.value)
    }

    @Test
    fun `absent exact name is complete only after all scoped files drain`(@TempDir home: Path) =
        withParser(home) { project ->
            val fixture = ScopedDiscoveryDispatcherFixture(project, home.resolve("empty"))
            val scope = fixture.scope(unrelatedRoots = 4095)
            val target = fixture.request.target as SymbolDiscoveryTarget.Name
            val request =
                SymbolDiscoveryRequest(
                    scope = fixture.request.scope,
                    budget = fixture.request.budget,
                    constraints = fixture.request.constraints,
                    target =
                        target.copy(
                            pattern = (SymbolDiscoveryPattern.parse("MissingCacheManager") as Refinement.Refined).value
                        ),
                )
            var namedCalls = 0
            var fileCalls = 0
            val indexes =
                object : IntellijDeclarationIndexes {
                    override fun named(
                        index: IntellijNamedDeclarationIndex,
                        name: String,
                        scope: GlobalSearchScope,
                        accept: (NavigationItem) -> Boolean,
                    ): Boolean {
                        assertEquals(0, namedCalls++)
                        assertEquals(IntellijNamedDeclarationIndex.CLASS, index)
                        assertEquals("MissingCacheManager", name)
                        return true
                    }

                    override fun kotlinFiles(scope: GlobalSearchScope, accept: (VirtualFile) -> Boolean): Boolean {
                        assertEquals(1, namedCalls)
                        assertEquals(0, fileCalls++)
                        return fixture.admittedFiles.all { accept(it.virtualFile) }
                    }

                    override fun packageFiles(
                        packageName: String,
                        scope: GlobalSearchScope,
                        accept: (VirtualFile) -> Boolean,
                    ): Boolean = throw AssertionError("Unexpected package index")
                }
            val outcome = fixture.query().discoverNative(project, scope, request, indexes = indexes).outcome()
            assertEquals(1, fileCalls)
            assertTrue(outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(emptyList<String>(), outcome.batch().candidates.map { it.name.value })
            assertEquals(14, fixture.observation.calls[IntellijReadCall.SCOPED_FILE_CALLBACK])
            assertEquals(14, fixture.observation.calls[IntellijReadCall.PSI_FIND_FILE])
        }
}
