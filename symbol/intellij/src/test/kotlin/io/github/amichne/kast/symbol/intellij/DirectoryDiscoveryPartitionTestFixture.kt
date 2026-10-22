package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.SemanticFilePartition
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
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

internal object DirectoryDiscoveryPartitionTestFixture {
    class Case(val project: Project, requestedRoot: Path) {
        private val fixture = fixture(project, requestedRoot, DirectoryDiscoveryTree.sources)
        val root = Path.of(fixture.scope.lease.workspaceRoot.value)

        fun request(
            traversal: SymbolDiscoveryContainment,
            pattern: String,
            match: SymbolDiscoveryMatch = SymbolDiscoveryMatch.EXACT_NAME,
        ): SymbolDiscoveryRequest {
            val base = fixture.request(20, 10_000, 100_000, null)
            return SymbolDiscoveryRequest(
                scope =
                    SymbolSearchScopeRequest(
                        base.scope.lease,
                        SymbolSearchScope.Workspace(
                            SymbolSourceKindPolicy.PRODUCTION_ONLY,
                            SymbolGeneratedSourcePolicy.EXCLUDE,
                            SymbolLibraryPolicy.EXCLUDE,
                        ),
                    ),
                target =
                    SymbolDiscoveryTarget.Name(
                        SymbolNameDiscoveryKind.SYMBOL,
                        SymbolDiscoveryPattern.parse(pattern).refined(),
                        match,
                    ),
                budget = base.budget,
                constraints =
                    base.constraints.copy(
                        directory =
                            SymbolDiscoveryDirectoryConstraint(
                                SymbolDiscoveryDirectory.parse(DirectoryDiscoveryTree.SELECTED).refined(),
                                traversal,
                            )
                    ),
            )
        }

        fun rootBoundary(relative: String, kind: WorkspaceSourceRootKind = WorkspaceSourceRootKind.PRODUCTION) =
            WorkspaceSourceRootBoundary(
                ideaModuleName = "fixture",
                linkedBuildRoot = root,
                gradleProjectPath = ":app",
                sourceSetName = if (kind == WorkspaceSourceRootKind.TEST) "test" else "main",
                sourceRoot = root.resolve(relative),
                sourceKind = kind,
                provenance = WorkspaceSourceRootProvenance.AUTHORED,
            )

        fun scope(
            request: SymbolDiscoveryRequest,
            observation: IntellijReadObservation,
            roots: List<WorkspaceSourceRootBoundary> =
                listOf(
                    rootBoundary(DirectoryDiscoveryTree.MAIN),
                    rootBoundary(DirectoryDiscoveryTree.TEST, WorkspaceSourceRootKind.TEST),
                ),
        ): CompiledIntellijSearchScope {
            val model =
                WorkspaceSearchScopeModel.compile(
                    fixture.scope.lease.workspaceRoot,
                    ImportedWorkspaceModelState.COMPLETE,
                    roots,
                )
            val policy =
                SymbolSearchScope.Workspace(
                    SymbolSourceKindPolicy.PRODUCTION_ONLY,
                    SymbolGeneratedSourcePolicy.EXCLUDE,
                    SymbolLibraryPolicy.EXCLUDE,
                )
            val scoped = SymbolSearchScopeRequest(request.scope.lease, policy)
            return (IntellijSearchScopeCompiler(request.constraints, observation = observation)
                    .compile(
                        request = scoped,
                        modelCompilation = model,
                        baseScope = GlobalSearchScope.allScope(project),
                        nativePath = ::nativePath,
                        libraryMembership = { IntellijLibraryMembership.NOT_LIBRARY },
                        sourceMembership = { true },
                    ) as IntellijSearchScopeCompilation.Compiled)
                .capability
        }

        fun query(observation: IntellijReadObservation) =
            IntellijNativeDiscoveryQuery(
                environmentState = { IntellijDiscoveryEnvironmentState.READY },
                cancellationCheck = {},
                clock = IntellijReadNanoClock { 0L },
                observation = observation,
            )
    }

    class StrictPartitions(private val case: Case, expected: List<String>) : IntellijDeclarationPartitionFiles {
        private val requests = ArrayDeque(expected)

        override fun find(partition: SemanticFilePartition): VirtualFile? {
            assertEquals("find:${case.root.relativize(Path.of(partition.orderingPath))}", requests.removeFirst())
            return NativeIntellijDeclarationPartitionFiles.find(partition)
        }

        override fun children(directory: VirtualFile): List<VirtualFile> {
            assertEquals("children:${case.root.relativize(directory.toNioPath())}", requests.removeFirst())
            return NativeIntellijDeclarationPartitionFiles.children(directory)
        }

        fun assertConsumed() = assertTrue(requests.isEmpty(), "Unused expected requests: $requests")
    }

    fun expectedPartitions(traversal: SymbolDiscoveryContainment): List<String> =
        listOf(
            "find:${DirectoryDiscoveryTree.SELECTED}",
            "children:${DirectoryDiscoveryTree.SELECTED}",
            "find:${DirectoryDiscoveryTree.SELECTED}/Helpers.kt",
            "find:${DirectoryDiscoveryTree.SELECTED}/Target.kt",
        ) +
            if (traversal == SymbolDiscoveryContainment.DIRECT) emptyList()
            else
                listOf(
                    "find:${DirectoryDiscoveryTree.SELECTED}/nested",
                    "children:${DirectoryDiscoveryTree.SELECTED}/nested",
                    "find:${DirectoryDiscoveryTree.SELECTED}/nested/TargetChild.kt",
                )

    fun <V, F> Refinement<V, F>.refined(): V =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Fixture rejected: $failure")
        }

    fun IntellijNativeDiscoveryQuery.discoverPartitions(
        project: Project,
        scope: CompiledIntellijSearchScope,
        request: SymbolDiscoveryRequest,
        partitions: IntellijDeclarationPartitionFiles = NativeIntellijDeclarationPartitionFiles,
    ): IntellijNativeDiscoveryExecution =
        when (val route = IntellijDirectoryDiscoveryPlan.select(scope, request)) {
            is IntellijDirectoryDiscoveryRoute.Selected ->
                discoverDirectory(
                    project = project,
                    plan = route.plan,
                    allowance = IntellijDeclarationDiscoveryAllowance(request, IntellijReadNanoClock { 0L }),
                    partitions = partitions,
                )
            IntellijDirectoryDiscoveryRoute.Existing,
            IntellijDirectoryDiscoveryRoute.Rejected -> throw AssertionError("A partition case needs a directory plan")
        }
}
