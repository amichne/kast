package io.github.amichne.kast.symbol.intellij

import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBlockCause
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pure directory selection uses explicit model facts without files, PSI, clocks or native effects. */
class DiscoveryDirectoryPlanTest {
    @Test
    fun `source roots intersect the requested directory before supply`() {
        for (traversal in SymbolDiscoveryContainment.entries) {
            val plan = plan(traversal)
            assertEquals(
                Path.of("/repo/${DirectoryDiscoveryTree.SELECTED}"),
                plan.intersection(Path.of("/repo/${DirectoryDiscoveryTree.MAIN}")),
            )
            assertNull(plan.intersection(Path.of("/repo/${DirectoryDiscoveryTree.TEST}")))
            assertNull(plan.intersection(Path.of("/repo/${DirectoryDiscoveryTree.SELECTED}-other")))
            assertEquals(
                if (traversal == SymbolDiscoveryContainment.DIRECT) null
                else Path.of("/repo/${DirectoryDiscoveryTree.SELECTED}/nested"),
                plan.intersection(Path.of("/repo/${DirectoryDiscoveryTree.SELECTED}/nested")),
            )
            val oracle =
                if (traversal == SymbolDiscoveryContainment.DIRECT) DirectoryDiscoveryTree.direct
                else DirectoryDiscoveryTree.descendants
            assertTrue(oracle.all { plan.admitsFile(Path.of("/repo/${it.path}")) })
            assertFalse(plan.admitsFile(Path.of("/repo/${DirectoryDiscoveryTree.MAIN}/unrelated/Target.kt")))
        }
    }

    @Test
    fun `library and unrestricted requests select existing routes`() {
        for (libraries in SymbolLibraryPolicy.entries) {
            val base = request(SymbolDiscoveryContainment.DESCENDANTS, libraries)
            val native =
                CompiledIntellijSearchScope(
                    base.scope.lease,
                    base.scope.scope,
                    emptyList(),
                    GlobalSearchScope.EMPTY_SCOPE,
                )
            val route = IntellijDirectoryDiscoveryPlan.select(native, base)
            assertEquals(libraries == SymbolLibraryPolicy.EXCLUDE, route is IntellijDirectoryDiscoveryRoute.Selected)
            val unrestricted =
                SymbolDiscoveryRequest(base.scope, base.target as SymbolDiscoveryTarget.Name, base.budget)
            assertSame(
                IntellijDirectoryDiscoveryRoute.Existing,
                IntellijDirectoryDiscoveryPlan.select(native, unrestricted),
            )
        }
    }

    @Test
    fun `retained candidate bytes reject preparation before source roots consume work`() {
        val request = request(SymbolDiscoveryContainment.DESCENDANTS)
        val model =
            WorkspaceSearchScopeModel.compile(
                workspaceRoot = request.scope.lease.workspaceRoot,
                modelState = ImportedWorkspaceModelState.COMPLETE,
                boundaries =
                    listOf(
                        WorkspaceSourceRootBoundary(
                            ideaModuleName = "fixture",
                            linkedBuildRoot = Path.of("/repo"),
                            gradleProjectPath = ":app",
                            sourceSetName = "main",
                            sourceRoot = Path.of("/repo/${DirectoryDiscoveryTree.MAIN}"),
                            sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                            provenance = WorkspaceSourceRootProvenance.AUTHORED,
                        )
                    ),
            ) as WorkspaceSearchScopeModelCompilation.Compiled
        val plan = plan(SymbolDiscoveryContainment.DESCENDANTS, model.model.sourceRoots)
        val limits = ReadLimits.resolve(environment = mapOf("KAST_READ_QUERY_CHECKPOINT_BYTES" to "4096")).refined()
        for (retained in listOf(0L, 4096L)) {
            var consumed = 0
            val observation = RecordingDiscoveryObservation { 0L }
            val result =
                IntellijDirectoryDiscoveryPlan.Prepared.prepare(
                    selection = plan,
                    consume = {
                        consumed++
                        true
                    },
                    observation = observation,
                    limits = limits,
                    retainedBytes = retained,
                )
            if (retained == 0L) {
                assertTrue(result is Refinement.Refined)
                assertEquals(2, consumed)
                assertEquals(2, observation.counters[IntellijReadCounter.DISCOVERY_ROOT_PATH_CONVERSIONS])
            } else {
                assertEquals(Refinement.Rejected(SymbolDiscoveryBlockCause.RETENTION_LIMIT), result)
                assertEquals(0, consumed)
                assertEquals(0, observation.counters[IntellijReadCounter.DISCOVERY_ROOT_PATH_CONVERSIONS] ?: 0)
            }
        }
    }

    private fun plan(
        traversal: SymbolDiscoveryContainment,
        roots: List<ModelOwnedSourceRoot> = emptyList(),
    ): IntellijDirectoryDiscoveryPlan {
        val request = request(traversal)
        val scope =
            CompiledIntellijSearchScope(
                request.scope.lease,
                request.scope.scope,
                roots,
                GlobalSearchScope.EMPTY_SCOPE,
            )
        return (IntellijDirectoryDiscoveryPlan.select(scope, request) as IntellijDirectoryDiscoveryRoute.Selected).plan
    }

    private fun request(
        traversal: SymbolDiscoveryContainment,
        libraries: SymbolLibraryPolicy = SymbolLibraryPolicy.EXCLUDE,
    ): SymbolDiscoveryRequest {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/repo")).refined()
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(1).refined())
        return SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(
                lease,
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_ONLY,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    libraries,
                ),
            ),
            SymbolDiscoveryTarget.Name(
                SymbolNameDiscoveryKind.SYMBOL,
                SymbolDiscoveryPattern.parse("Target").refined(),
                SymbolDiscoveryMatch.EXACT_NAME,
            ),
            SymbolDiscoveryBudget(
                io.github.amichne.kast.kernel.ResourceBudget(
                    io.github.amichne.kast.kernel.ResultLimit.parse(20).refined(),
                    io.github.amichne.kast.kernel.WorkUnitLimit.parse(10_000).refined(),
                    io.github.amichne.kast.kernel.ElapsedTimeLimitMillis.parse(10_000).refined(),
                ),
                SymbolDiscoveryByteLimit.parse(100_000).refined(),
            ),
            SymbolDiscoveryConstraints.None.copy(
                directory =
                    SymbolDiscoveryDirectoryConstraint(
                        SymbolDiscoveryDirectory.parse(DirectoryDiscoveryTree.SELECTED).refined(),
                        traversal,
                    )
            ),
        )
    }

    private fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }
}
