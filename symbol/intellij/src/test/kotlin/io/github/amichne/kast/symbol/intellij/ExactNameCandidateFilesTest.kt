package io.github.amichne.kast.symbol.intellij

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryWordFailure
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.outcome
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExactNameCandidateFilesTest {
    @Test
    fun `indexed declaration names preserve ASCII words through one hundred characters`() {
        for (raw in listOf("Target", "_Target9", "A".repeat(100))) {
            assertEquals(raw, IntellijIndexedDeclarationName.parse(raw).refined().word.value)
        }
        for ((raw, failure) in
            listOf(
                "" to SymbolDiscoveryWordFailure.BLANK,
                " " to SymbolDiscoveryWordFailure.BLANK,
                "Target Name" to SymbolDiscoveryWordFailure.UNSUPPORTED_GRAMMAR,
                "Target.Name" to SymbolDiscoveryWordFailure.UNSUPPORTED_GRAMMAR,
                "9Target" to SymbolDiscoveryWordFailure.UNSUPPORTED_GRAMMAR,
                "Tárget" to SymbolDiscoveryWordFailure.UNSUPPORTED_GRAMMAR,
                "Companion" to SymbolDiscoveryWordFailure.UNSUPPORTED_GRAMMAR,
                "A".repeat(101) to SymbolDiscoveryWordFailure.TOO_LONG,
            )) {
            assertEquals(Refinement.Rejected(failure), IntellijIndexedDeclarationName.parse(raw))
        }
    }

    @Test
    fun `provider failure discards the collected file prefix before PSI projection`(@TempDir home: Path) =
        withParser(home) { project ->
            val case = Case(project, home)
            val result = case.collect { accept ->
                assertTrue(accept(case.target))
                false
            }
            assertFalse(result.complete)
            assertEquals(listOf(SymbolDiscoveryQualification.PROVIDER_FAILURE), result.qualifications)
            assertEquals(emptyList<SymbolDiscoveryBlockCause>(), result.blocks)
            assertEquals(emptyList<String>(), result.names)
            assertEquals(0, result.observation.calls[IntellijReadCall.PSI_FIND_FILE] ?: 0)
        }

    @Test
    fun `excluded and duplicate files consume no eligible file capacity`(@TempDir home: Path) =
        withParser(home) { project ->
            val case = Case(project, home)
            val result =
                case.collect(limits = limits("KAST_READ_DISCOVERY_FILES" to "1")) { accept ->
                    assertTrue(accept(case.excluded))
                    assertTrue(accept(LightVirtualFile("Target.txt", "Target")))
                    assertTrue(accept(case.target))
                    assertTrue(accept(case.target))
                    assertTrue(accept(case.excluded))
                    true
                }
            assertTrue(result.complete)
            assertEquals(listOf("Target"), result.names)
            assertEquals(emptyList<SymbolDiscoveryQualification>(), result.qualifications)
            assertEquals(emptyList<SymbolDiscoveryBlockCause>(), result.blocks)
            assertEquals(5, result.observation.counters[IntellijReadCounter.DISCOVERY_FILE_ENTRIES_SUPPLIED])
            assertEquals(1, result.observation.counters[IntellijReadCounter.DISCOVERY_FILE_CANDIDATES])
            assertEquals(1, result.observation.calls[IntellijReadCall.PSI_FIND_FILE])
        }

    @Test
    fun `file capacity preserves an incomplete result when the provider ignores callback stop`(@TempDir home: Path) =
        withParser(home) { project ->
            val case = Case(project, home)
            val result =
                case.collect(limits = limits("KAST_READ_DISCOVERY_FILES" to "1")) { accept ->
                    assertTrue(accept(case.target))
                    assertFalse(accept(case.second))
                    true
                }
            assertFalse(result.complete)
            assertEquals(listOf(SymbolDiscoveryQualification.WORK_LIMIT_REACHED), result.qualifications)
            assertEquals(listOf(SymbolDiscoveryBlockCause.PARTITION_CAPACITY), result.blocks)
            assertEquals(emptyList<String>(), result.names)
            assertEquals(0, result.observation.counters[IntellijReadCounter.DISCOVERY_FILE_CANDIDATES] ?: 0)
            assertEquals(0, result.observation.calls[IntellijReadCall.PSI_FIND_FILE] ?: 0)
        }

    @Test
    fun `retention capacity rejects the next file before work and PSI projection`(@TempDir home: Path) =
        withParser(home) { project ->
            val case = Case(project, home)
            val bytes = 768L + 2L * case.target.path.length
            var consumed = 0
            val result =
                case.collect(
                    limits = limits("KAST_READ_QUERY_CHECKPOINT_BYTES" to (bytes - 1).toString()),
                    consume = {
                        consumed++
                        true
                    },
                ) { accept ->
                    assertFalse(accept(case.target))
                    false
                }
            assertFalse(result.complete)
            assertEquals(listOf(SymbolDiscoveryQualification.BYTE_LIMIT_REACHED), result.qualifications)
            assertEquals(listOf(SymbolDiscoveryBlockCause.RETENTION_LIMIT), result.blocks)
            assertEquals(0, consumed)
            assertEquals(emptyList<String>(), result.names)
        }

    @Test
    fun `work rejection cannot become complete when the provider ignores callback stop`(@TempDir home: Path) =
        withParser(home) { project ->
            val case = Case(project, home)
            var consumed = 0
            val result =
                case.collect(
                    consume = {
                        consumed++
                        false
                    }
                ) { accept ->
                    assertFalse(accept(case.target))
                    true
                }
            assertFalse(result.complete)
            assertEquals(1, consumed)
            assertEquals(emptyList<String>(), result.names)
            assertEquals(0, result.observation.counters[IntellijReadCounter.DISCOVERY_FILE_CANDIDATES] ?: 0)
            assertEquals(0, result.observation.calls[IntellijReadCall.PSI_FIND_FILE] ?: 0)
        }

    @Test
    fun `dispatcher preserves the work limit after candidate collection`(@TempDir home: Path) =
        withParser(home) { project ->
            val case = Case(project, home)
            val supply =
                StrictIndexes(case.scope.nativeScope) { accept ->
                    assertTrue(accept(case.target))
                    true
                }
            val outcome =
                case.fixture
                    .query()
                    .discoverNative(
                        project = project,
                        scope = case.scope,
                        request = case.fixture.request,
                        indexes = supply,
                    )
                    .outcome()
            supply.assertConsumed()
            assertTrue(outcome is SymbolDiscoveryOutcome.Qualified)
            assertTrue(
                SymbolDiscoveryQualification.WORK_LIMIT_REACHED in
                    (outcome as SymbolDiscoveryOutcome.Qualified).qualifications.values
            )
            assertEquals(1L, outcome.batch().examinedWorkUnits.value)
            assertEquals(emptyList<String>(), outcome.batch().candidates.map { it.name.value })
        }

    @Test
    fun `dispatcher preserves cancellation during the native callback`(@TempDir home: Path) =
        withParser(home) { project ->
            val case = Case(project, home)
            var cancelled = false
            val supply =
                StrictIndexes(case.scope.nativeScope) { accept ->
                    cancelled = true
                    accept(case.target)
                }
            val query = case.fixture.query(cancellationCheck = { if (cancelled) throw ProcessCanceledException() })
            assertThrows(ProcessCanceledException::class.java) {
                query.discoverNative(
                    project = project,
                    scope = case.scope,
                    request = case.fixture.request,
                    indexes = supply,
                )
            }
            supply.assertConsumed()
        }

    private class Case(val project: Project, home: Path) {
        val fixture = ScopedDiscoveryDispatcherFixture(project, home.resolve("source"))
        val scope = fixture.scope()
        val target: VirtualFile = fixture.target.containingFile.virtualFile
        val second: VirtualFile = fixture.admittedFiles.single { it.name == "File1.kt" }.virtualFile
        val excluded: VirtualFile = fixture.excludedFile.virtualFile
        private val plan =
            when (val route = IntellijDirectoryDiscoveryPlan.select(scope, fixture.request)) {
                is IntellijDirectoryDiscoveryRoute.Selected -> route.plan
                IntellijDirectoryDiscoveryRoute.Existing,
                IntellijDirectoryDiscoveryRoute.Rejected ->
                    throw AssertionError("A candidate case needs a directory plan")
            }

        fun collect(
            limits: ReadLimits = ReadLimits.Default,
            consume: () -> Boolean = { true },
            supply: ((VirtualFile) -> Boolean) -> Boolean,
        ): Result {
            val names = mutableListOf<String>()
            val qualifications = mutableListOf<SymbolDiscoveryQualification>()
            val blocks = mutableListOf<SymbolDiscoveryBlockCause>()
            val observation = RecordingDiscoveryObservation { 0L }
            val indexes = StrictIndexes(scope.nativeScope, supply)
            val complete =
                collectExactNameCandidateDeclarations(
                    project = project,
                    plan = plan,
                    name = IntellijIndexedDeclarationName.parse("Target").refined(),
                    indexes = indexes,
                    limits = limits,
                    callbacks =
                        ScopedDeclarationCallbacks(
                            observe = { true },
                            qualify = { qualifications += it },
                            accept = { item ->
                                names += requireNotNull(item.name)
                                true
                            },
                            observation = observation,
                            consume = consume,
                            block = { blocks += it },
                        ),
                )
            indexes.assertConsumed()
            return Result(complete, names, qualifications, blocks, observation)
        }
    }

    private data class Result(
        val complete: Boolean,
        val names: List<String>,
        val qualifications: List<SymbolDiscoveryQualification>,
        val blocks: List<SymbolDiscoveryBlockCause>,
        val observation: RecordingDiscoveryObservation,
    )

    private class StrictIndexes(
        private val expectedScope: GlobalSearchScope,
        private val supply: ((VirtualFile) -> Boolean) -> Boolean,
    ) : IntellijDeclarationIndexes {
        private var calls = 0

        override fun candidateFiles(
            name: IntellijIndexedDeclarationName,
            scope: GlobalSearchScope,
            accept: (VirtualFile) -> Boolean,
        ): Boolean {
            assertEquals(0, calls++)
            assertEquals("Target", name.word.value)
            assertSame(expectedScope, scope)
            return supply(accept)
        }

        override fun named(
            index: IntellijNamedDeclarationIndex,
            name: String,
            scope: GlobalSearchScope,
            accept: (NavigationItem) -> Boolean,
        ): Boolean = throw AssertionError("Unexpected named index")

        override fun kotlinFiles(scope: GlobalSearchScope, accept: (VirtualFile) -> Boolean): Boolean =
            throw AssertionError("Unexpected Kotlin file index")

        override fun packageFiles(
            packageName: String,
            scope: GlobalSearchScope,
            accept: (VirtualFile) -> Boolean,
        ): Boolean = throw AssertionError("Unexpected package index")

        fun assertConsumed() = assertEquals(1, calls)
    }

    private companion object {
        fun limits(vararg entries: Pair<String, String>): ReadLimits =
            ReadLimits.resolve(environment = mapOf(*entries)).refined()

        fun <V, F> Refinement<V, F>.refined(): V =
            when (this) {
                is Refinement.Refined -> value
                is Refinement.Rejected -> throw AssertionError("Fixture rejected: $failure")
            }
    }
}
