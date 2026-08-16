package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.runtime.composition.platform.InstalledGradleModelRead
import io.github.amichne.kast.runtime.composition.platform.InstalledGradleModelReadOperations
import io.github.amichne.kast.runtime.composition.platform.InstalledGradleWorkspaceModel
import io.github.amichne.kast.runtime.composition.platform.InstalledWorkspaceModelAdapter
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSignal
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.GradleWorkspaceModelCapture
import io.github.amichne.kast.workspace.intellij.IntellijWorkspaceReconciliationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InstalledWorkspaceModelAdapterTest {
    @Test
    fun `one installed model snapshot supplies capture reconciliation and semantic scope`(
        @TempDir temporary: Path,
    ) {
        val root = Files.createDirectories(temporary.resolve("repo")).toRealPath()
        val fixture = InstalledChangeProtocolFixture.create(root)
        val published = fixture.published
        val sourceRoot = published.sourceRoots.single()
        val scope = WorkspaceSearchScopeModel.compile(
            published.root,
            ImportedWorkspaceModelState.COMPLETE,
            listOf(
                WorkspaceSourceRootBoundary(
                    sourceRoot.owner.module.value,
                    root.resolve(sourceRoot.owner.project.buildRoot.value).normalize(),
                    sourceRoot.owner.project.projectPath.value,
                    sourceRoot.owner.sourceSet.value,
                    root.resolve(sourceRoot.location.value).normalize(),
                    WorkspaceSourceRootKind.PRODUCTION,
                    WorkspaceSourceRootProvenance.AUTHORED,
                ),
            ),
        ) as WorkspaceSearchScopeModelCompilation.Compiled
        val model = InstalledGradleWorkspaceModel.admit(
            published.root,
            published.sourceState,
            published.sourceRoots,
            scope,
        ).requiredModel()
        val adapter = InstalledWorkspaceModelAdapter(
            InstalledGradleModelReadOperations { InstalledGradleModelRead.Captured(model) },
        )

        assertEquals(
            GradleWorkspaceModelCapture.Captured(published.sourceState),
            adapter.capture(published.root, setOf(WorkspaceSignal.InitialProjectModel)),
        )
        assertEquals(
            IntellijWorkspaceReconciliationResult.Reconciled(
                io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind.entries.toSet(),
                published.sourceRoots,
            ),
            adapter.reconcile(WorkspaceCandidate(published.root, published.sourceState)),
        )
        assertSame(scope, adapter.searchScope(published.readLease))
    }
}

private fun <Value, Failure> io.github.amichne.kast.kernel.Refinement<Value, Failure>.requiredModel():
    Value = when (this) {
        is io.github.amichne.kast.kernel.Refinement.Refined -> value
        is io.github.amichne.kast.kernel.Refinement.Rejected -> error(failure.toString())
    }
