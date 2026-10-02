package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SemanticFilePartition
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryProgress
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.exhaust
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.outcome
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real VFS observations prove scoped root, capacity and cooperative deadline admission. */
class IntellijDeclarationSourceAdapterTest {
    @Test
    fun `the native VFS adapter resumes a broad admitted source root beyond one bounded work grant`(
        @TempDir home: Path
    ) {
        withParser(home) { project ->
            val names = List(600) { "Declaration$it" }
            val fixture =
                fixture(
                    project,
                    home.resolve("native-broad"),
                    names
                        .mapIndexed { index, name ->
                            "File${index.toString().padStart(3, '0')}.kt" to "class $name"
                        }
                        .toMap(),
                )
            val exhausted = exhaust(fixture, 20, 512, nativeBoundary = true)
            assertEquals(names, exhausted.names)
            assertEquals(
                600L,
                exhausted.pages.sumOf { it.batch().measurements.inventoryFiles.value },
                "each VFS file is detached once",
            )
            assertEquals(fixture.leafCount, exhausted.leaves)
            assertTrue(exhausted.pages.first().batch().candidates.isNotEmpty())
            assertTrue(exhausted.pages.all { it.batch().examinedWorkUnits.value <= 512 })
        }
    }

    @Test
    fun `excluded native children cannot exhaust eligible partition capacity`(@TempDir home: Path) {
        withParser(home) { project ->
            val root = home.resolve("filtered-capacity")
            val fixture = fixture(project, root, mapOf("Selected.kt" to "class Selected"))
            repeat(20) { Files.writeString(root.resolve("unrelated$it.txt"), "unrelated") }
            val limits = ReadLimits.resolve(environment = mapOf("KAST_READ_DISCOVERY_FILES" to "1")).refined()
            val result = fixture.page(20, 100, null, nativeBoundary = true, limits = limits).outcome()
            assertEquals(SymbolDiscoveryProgress.Exhausted, result.progress)
            assertEquals(listOf("Selected"), result.batch().candidates.map { it.name.value })
            assertEquals(1, result.batch().measurements.inventoryFiles.value)
        }
    }

    @Test
    fun `deadline during native inventory stops before the next child and preserves the unopened partition`(
        @TempDir home: Path
    ) {
        withParser(home) { project ->
            var elapsed = 0L
            var admissions = 0
            val fixture =
                fixture(
                    project,
                    home.resolve("inventory-deadline"),
                    mapOf("A.kt" to "class A", "B.kt" to "class B"),
                    onNativeFileAdmission = {
                        admissions++
                        elapsed = 10_000_000_000L
                    },
                )
            val result =
                fixture.page(20, 100, null, nativeBoundary = true, clock = IntellijReadNanoClock { elapsed }).outcome()
            assertEquals(1, admissions, "expiration must prevent another native child admission")
            assertEquals(
                SymbolDiscoveryProgress.Blocked(SymbolDiscoveryBlockCause.INSUFFICIENT_EXECUTION_GRANT),
                result.progress,
            )
            assertEquals(
                0,
                result.batch().measurements.inventoryFiles.value,
                "a partial directory cannot publish a complete snapshot",
            )
            assertTrue(result.batch().candidates.isEmpty())
            assertTrue(
                SymbolDiscoveryQualification.TIME_LIMIT_REACHED in
                    (result as SymbolDiscoveryOutcome.Qualified).qualifications.values
            )
            assertTrue(IntellijReadTermination.TIME_LIMIT in fixture.terminations)
        }
    }

    @Test
    fun `native partition observations retain successful and missing outcomes`(@TempDir home: Path) {
        withParser(home) { project ->
            val fixture = fixture(project, home.resolve("partition-observation"), mapOf("A.kt" to "class A"))
            val adapter = fixture.adapter(project)
            val present =
                SemanticFilePartition.File(
                    SymbolDiscoveryFileIdentity.Workspace(
                        CanonicalWorkspaceFilePath.fromCanonicalPath(
                                fixture.scope.lease.workspaceRoot,
                                Path.of(fixture.files.single().virtualFile.path),
                            )
                            .refined()
                    )
                )
            val missing =
                SemanticFilePartition.Directory(
                    SymbolDiscoveryFileIdentity.Workspace(
                        CanonicalWorkspaceFilePath.fromCanonicalPath(
                                fixture.scope.lease.workspaceRoot,
                                Path.of(fixture.scope.lease.workspaceRoot.value).resolve("missing"),
                            )
                            .refined()
                    )
                )
            ApplicationManager.getApplication().runReadAction {
                assertTrue(adapter.observe(present) is IntellijDeclarationPartitionObservation.Source)
                assertEquals(
                    IntellijDeclarationPartitionObservation.Rejected(SymbolDiscoveryBlockCause.PARTITION_UNAVAILABLE),
                    adapter.observe(missing),
                )
            }
            assertEquals(2, fixture.counters[IntellijReadCounter.DISCOVERY_PARTITIONS_OBSERVED])
            assertEquals(1, fixture.counters[IntellijReadCounter.DISCOVERY_PARTITIONS_ACCEPTED])
            assertEquals(listOf(IntellijReadTermination.DISCOVERY_PARTITION_NOT_FOUND), fixture.terminations)
        }
    }

    @Test
    fun `root inventory stops when its execution allowance expires without publishing a partial frontier`(
        @TempDir home: Path
    ) {
        withParser(home) { project ->
            val fixture = fixture(project, home.resolve("root-deadline"), mapOf("A.kt" to "class A"))
            val instants = ArrayDeque(listOf(0L, 0L, 10_000_000_000L))
            val adapter =
                fixture.adapter(project, IntellijReadNanoClock { instants.removeFirstOrNull() ?: 10_000_000_000L })
            assertEquals(IntellijDeclarationInitialInventory.TimeLimit, adapter.initialPartitions())
            assertTrue(instants.isEmpty(), "the root planner observes its allowance during inventory")
            assertEquals(listOf(IntellijReadTermination.TIME_LIMIT), fixture.terminations)
        }
    }

    @Test
    fun `directory disjoint from all admitted source roots exhausts without native partition work`(
        @TempDir home: Path
    ) {
        withParser(home) { project ->
            val fixture =
                fixture(
                    project,
                    home.resolve("disjoint"),
                    mapOf("selected/A.kt" to "class A"),
                    sourceRootDirectory = "selected",
                    directory = "other",
                )
            val result = fixture.page(20, 100, null, nativeBoundary = true).outcome()
            assertTrue(result is SymbolDiscoveryOutcome.Complete)
            assertEquals(SymbolDiscoveryProgress.Exhausted, result.progress)
            assertTrue(result.batch().candidates.isEmpty())
            assertEquals(null, fixture.counters[IntellijReadCounter.DISCOVERY_PARTITIONS_OBSERVED])
        }
    }

    private companion object {
        fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
            when (this) {
                is Refinement.Refined -> value
                is Refinement.Rejected -> error(failure.toString())
            }
    }
}
