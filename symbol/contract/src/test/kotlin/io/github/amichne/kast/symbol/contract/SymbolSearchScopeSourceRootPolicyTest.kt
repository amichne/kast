package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.CurrentWorkspaceEpoch
import io.github.amichne.kast.workspace.contract.CurrentWorkspaceReadLease
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.nio.file.Path

class SymbolSearchScopeSourceRootPolicyTest {
    @Test
    fun `scope target and read policies are current-epoch-bound and independent from edit authority`() {
        val root = workspaceRoot()
        val model = compiledModel(root)
        val ownedRoot = model.sourceRoots.single()
        val lease = CurrentWorkspaceReadLease(root, epoch(11))
        val exactFile = CanonicalWorkspaceFilePath.fromCanonicalPath(
            root,
            Path.of("/workspace/app/src/main/kotlin/App.kt"),
        ).refined()
        val exact = SymbolSearchScopeRequest(
            lease = lease,
            scope = SymbolSearchScope.ExactFile(
                file = exactFile,
                sourceKinds = SymbolSourceKindPolicy.PRODUCTION_ONLY,
                generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE,
            ),
        )
        val workspace = exact.copy(
            scope = SymbolSearchScope.Workspace(
                sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                generatedSources = SymbolGeneratedSourcePolicy.INCLUDE,
                libraries = SymbolLibraryPolicy.INCLUDE,
            ),
        )
        val module = SymbolSearchScope.Module(
            module = ownedRoot.module,
            sourceKinds = SymbolSourceKindPolicy.PRODUCTION_ONLY,
            generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE,
        )

        assertEquals(lease, exact.lease)
        assertEquals(exactFile, assertInstanceOf<SymbolSearchScope.ExactFile>(exact.scope).file)
        assertEquals(ownedRoot.module, module.module)
        assertEquals(
            SymbolLibraryPolicy.INCLUDE,
            assertInstanceOf<SymbolSearchScope.Workspace>(workspace.scope).libraries,
        )
    }

    @Test
    fun `exact file path rejects invalid and out-of-workspace boundary paths`() {
        val root = workspaceRoot()

        assertEquals(
            CanonicalWorkspaceFilePathFailure.INVALID_FILE_PATH,
            CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of("relative.kt")).rejected(),
        )
        assertEquals(
            CanonicalWorkspaceFilePathFailure.FILE_OUTSIDE_WORKSPACE,
            CanonicalWorkspaceFilePath.fromCanonicalPath(root, Path.of("/other/App.kt")).rejected(),
        )
    }

    private fun compiledModel(root: CanonicalWorkspaceRoot): WorkspaceSearchScopeModel {
        val compilation = WorkspaceSearchScopeModel.compile(
            root,
            ImportedWorkspaceModelState.COMPLETE,
            listOf(
                WorkspaceSourceRootBoundary(
                    ideaModuleName = "app.main",
                    linkedBuildRoot = Path.of("/workspace"),
                    gradleProjectPath = ":app",
                    sourceSetName = "main",
                    sourceRoot = Path.of("/workspace/app/src/main/kotlin"),
                    sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                    provenance = WorkspaceSourceRootProvenance.AUTHORED,
                ),
            ),
        )
        return assertInstanceOf<WorkspaceSearchScopeModelCompilation.Compiled>(compilation).model
    }

    private fun workspaceRoot(): CanonicalWorkspaceRoot =
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined()

    private fun epoch(value: Long): CurrentWorkspaceEpoch =
        CurrentWorkspaceEpoch.parse(value).refined()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.rejected(): Failure = when (this) {
        is Refinement.Refined -> error(value.toString())
        is Refinement.Rejected -> failure
    }
}
