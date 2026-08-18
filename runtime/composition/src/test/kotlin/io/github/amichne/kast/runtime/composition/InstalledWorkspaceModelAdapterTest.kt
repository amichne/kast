package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.runtime.composition.platform.InstalledGradleModelBoundary
import io.github.amichne.kast.runtime.composition.platform.InstalledGradleModelRead
import io.github.amichne.kast.runtime.composition.platform.InstalledWorkspaceModelAdapter
import io.github.amichne.kast.runtime.composition.platform.projectInstalledGradleModel
import io.github.amichne.kast.workspace.contract.WorkspaceCandidate
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
        val boundary = WorkspaceSourceRootBoundary(
            sourceRoot.owner.module.value,
            root.resolve(sourceRoot.owner.project.buildRoot.value).normalize(),
            sourceRoot.owner.project.projectPath.value,
            sourceRoot.owner.sourceSet.value,
            root.resolve(sourceRoot.location.value).normalize(),
            WorkspaceSourceRootKind.PRODUCTION,
            WorkspaceSourceRootProvenance.AUTHORED,
        )
        val read = projectInstalledGradleModel(
            InstalledGradleModelBoundary(
                published.root,
                true,
                listOf(boundary),
                listOf("classpath:file:///fixture.jar", "source:fixture"),
            ),
        ) as InstalledGradleModelRead.Captured
        val model = read.model
        val scope = model.searchScope
        val adapter = InstalledWorkspaceModelAdapter { read }

        assertEquals(
            GradleWorkspaceModelCapture.Captured(model.state),
            adapter.capture(published.root, setOf(WorkspaceSignal.InitialProjectModel)),
        )
        assertEquals(
            IntellijWorkspaceReconciliationResult.Reconciled(
                io.github.amichne.kast.workspace.contract.WorkspaceEvidenceKind.entries.toSet(),
                published.sourceRoots,
            ),
            adapter.reconcile(WorkspaceCandidate(published.root, model.state)),
        )
        assertSame(scope, adapter.searchScope(published.readLease))
    }
}
