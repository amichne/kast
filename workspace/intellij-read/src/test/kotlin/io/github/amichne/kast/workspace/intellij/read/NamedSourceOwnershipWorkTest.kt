package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceSets
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceSourceSetName
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Production detached admission; no filesystem, clock dependency, IDE, or index is virtualized by the oracle. */
class NamedSourceOwnershipWorkTest {
    private val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    @Test
    fun `root admission and repeated file admission do not scan unrelated ownership roots`() {
        val failures = mutableListOf<String>()
        for (count in listOf(128, 1024, 4096)) {
            val entries = (0 until count).map { entry("main", "module$it/src") }
            val scope = admit(entries)
            val paths = entries.map { it.sourceRoot }
            // Same result obligation as unrelated imported roots grow: this file is always in module0.
            val file = Path.of("/workspace/module0/src/CacheManager.kt")
            for (sample in 0..5) {
                val observation = Counts()
                val start = System.nanoTime()
                val admittedRoots = paths.count { scope.contains(it, SymbolDiscoverySourceSets.All, observation) }
                val rootNanos = System.nanoTime() - start
                val probeStart = System.nanoTime()
                repeat(2048) { assertTrue(scope.contains(file, SymbolDiscoverySourceSets.All, observation)) }
                val probeNanos = System.nanoTime() - probeStart
                assertEquals(count, admittedRoots)
                assertEquals(count + 2048L, observation[IntellijReadCounter.NAMED_SOURCE_MEMBERSHIP_CHECKS])
                println(
                    "ownership roots=$count sample=$sample rootAdmissionNs=$rootNanos " +
                        "fileAdmissionNs=$probeNanos counters=${observation.values}"
                )
                // Each lookup can inspect at most the input path's ancestors, independent of imported-root count.
                val maximumProbes = paths.sumOf { it.nameCount + 1L } + 2048L * (file.nameCount + 1)
                if (observation[IntellijReadCounter.NAMED_SOURCE_OWNERSHIP_PROBES] > maximumProbes)
                    failures +=
                        "roots=$count sample=$sample probes=${observation[IntellijReadCounter.NAMED_SOURCE_OWNERSHIP_PROBES]} bound=$maximumProbes"
                if (observation[IntellijReadCounter.NAMED_SOURCE_ROOT_PATH_CONVERSIONS] != 0L)
                    failures += "membership reparsed roots=$count sample=$sample"
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun `deepest tied ownership matches independent scan oracle including exclusions and absent IDE roots`() {
        val entries =
            listOf(
                entry("main", "src"),
                entry("test", "src/tests", WorkspaceSourceRootKind.TEST),
                entry("integrationTest", "src/tests", WorkspaceSourceRootKind.TEST),
                entry("main", "src/generated", provenance = WorkspaceSourceRootProvenance.GENERATED),
                entry("main", "src/absent"),
                entry("main", "src/excluded/reincluded"),
            )
        val idePaths = entries.map { it.sourceRoot }.toSet() - Path.of("/workspace/src/absent")
        val excluded = setOf(Path.of("/workspace/src/excluded"), Path.of("/workspace/src/tests/hidden"))
        val scope = admit(entries, idePaths, excluded)
        val paths =
            listOf(
                    "/workspace/src",
                    "/workspace/src/CacheManager.kt",
                    "/workspace/src-other/CacheManager.kt",
                    "/workspace/src/tests/Test.kt",
                    "/workspace/src/tests/hidden/Hidden.kt",
                    "/workspace/src/generated/Generated.kt",
                    "/workspace/src/absent/Absent.kt",
                    "/workspace/src/excluded/Hidden.kt",
                    "/workspace/src/excluded/reincluded/Visible.kt",
                    "/workspace/src/../src/NonCanonical.kt",
                    "src/Relative.kt",
                    "/unknown/File.kt",
                )
                .map(Path::of)
        val policies = listOf(SymbolDiscoverySourceSets.All, names("main"), names("integrationTest"), names("missing"))
        for (policy in policies) for (path in paths) {
            val observation = Counts()
            assertEquals(
                reference(entries, idePaths, excluded, path, policy),
                scope.contains(path, policy, observation),
                "$path $policy",
            )
            assertEquals(1L, observation[IntellijReadCounter.NAMED_SOURCE_MEMBERSHIP_CHECKS])
        }
        assertEquals(
            listOf(false, true),
            listOf("main", "integrationTest").map {
                scope.contains(Path.of("/workspace/src/tests/Test.kt"), names(it))
            },
        )
        assertEquals(false, scope.contains(Path.of("/workspace/src/CacheManager.kt"), names("missing")))
    }

    @Test
    fun `prepared ownership belongs to its captured model and records conversions once`() {
        val entries = mutableListOf(entry("main", "src"))
        val excluded = mutableSetOf(Path.of("/workspace/src/hidden"))
        val observation = Counts()
        val captured =
            NamedGradleSourceScope.admit(
                    root,
                    NamedGradleModelObservation.Captured(entries, excluded),
                    listOf(
                        IdeCodeSourceRoot(
                            entries.single().sourceRoot,
                            WorkspaceSourceRootKind.PRODUCTION,
                            WorkspaceSourceRootProvenance.AUTHORED,
                            IdeSourceRootEvidence(
                                BoundedModuleName.observe("module"),
                                BoundedSourceRootIdentity.observe("file:///workspace/src"),
                            ),
                        )
                    ),
                    observation,
                )
                .refined()
        assertEquals(1L, observation[IntellijReadCounter.NAMED_SOURCE_ROOT_PATH_CONVERSIONS])
        entries.clear()
        excluded.clear()
        assertTrue(captured.contains(Path.of("/workspace/src/Main.kt"), names("main"), observation))
        assertEquals(false, captured.contains(Path.of("/workspace/src/hidden/Hidden.kt"), names("main"), observation))
        assertEquals(1L, observation[IntellijReadCounter.NAMED_SOURCE_ROOT_PATH_CONVERSIONS])
        val nextModel = admit(listOf(entry("test", "src", WorkspaceSourceRootKind.TEST)))
        assertEquals(false, nextModel.contains(Path.of("/workspace/src/Main.kt"), names("main")))
        assertTrue(nextModel.contains(Path.of("/workspace/src/Main.kt"), names("test")))
    }

    // Independent retained linear rule over boundary inputs, not model lookup or production helper output.
    private fun reference(
        entries: List<WorkspaceSourceRootBoundary>,
        ide: Set<Path>,
        excluded: Set<Path>,
        file: Path,
        policy: SymbolDiscoverySourceSets,
    ): Boolean {
        if (!file.isAbsolute || file.normalize() != file) return false
        val candidates = entries.filter { file.startsWith(it.sourceRoot) }
        val depth = candidates.maxOfOrNull { it.sourceRoot.nameCount } ?: return false
        if (excluded.any { file.startsWith(it) && it.nameCount >= depth }) return false
        return candidates.any {
            it.sourceRoot.nameCount == depth &&
                it.sourceRoot in ide &&
                it.provenance == WorkspaceSourceRootProvenance.AUTHORED &&
                when (policy) {
                    SymbolDiscoverySourceSets.All -> true
                    is SymbolDiscoverySourceSets.Exact -> policy.values.any { name -> name.value == it.sourceSetName }
                }
        }
    }

    private fun admit(
        entries: List<WorkspaceSourceRootBoundary>,
        ide: Set<Path> = entries.map { it.sourceRoot }.toSet(),
        excluded: Set<Path> = emptySet(),
    ) =
        NamedGradleSourceScope.admit(
                root,
                NamedGradleModelObservation.Captured(entries, excluded),
                entries
                    .filter { it.sourceRoot in ide }
                    .map {
                        IdeCodeSourceRoot(
                            it.sourceRoot,
                            it.sourceKind,
                            it.provenance,
                            IdeSourceRootEvidence(
                                BoundedModuleName.observe("module"),
                                BoundedSourceRootIdentity.observe("file://${it.sourceRoot}"),
                            ),
                        )
                    },
            )
            .refined()

    private fun names(name: String) =
        SymbolDiscoverySourceSets.Exact.from(setOf(WorkspaceSourceSetName.parse(name).refined())).refined()

    private fun entry(
        name: String,
        path: String,
        kind: WorkspaceSourceRootKind = WorkspaceSourceRootKind.PRODUCTION,
        provenance: WorkspaceSourceRootProvenance = WorkspaceSourceRootProvenance.AUTHORED,
    ) =
        WorkspaceSourceRootBoundary(
            "module",
            Path.of("/workspace"),
            ":",
            name,
            Path.of("/workspace/$path"),
            kind,
            provenance,
        )

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }

    private class Counts : IntellijReadObservation {
        val values = mutableMapOf<IntellijReadCounter, Long>()

        operator fun get(counter: IntellijReadCounter) = values[counter] ?: 0L

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            values[counter] = get(counter) + amount
        }

        override fun terminated(reason: IntellijReadTermination, contributor: IntellijReadContributor) = Unit
    }
}
