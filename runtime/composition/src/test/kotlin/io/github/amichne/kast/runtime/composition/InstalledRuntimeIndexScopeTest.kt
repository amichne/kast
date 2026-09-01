package io.github.amichne.kast.runtime.composition

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.runtime.composition.platform.InstalledGradleModelBoundary
import io.github.amichne.kast.runtime.composition.platform.InstalledGradleModelRead
import io.github.amichne.kast.runtime.composition.platform.projectInstalledGradleModel
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class InstalledRuntimeIndexScopeTest {
    @Test
    fun `runtime reports only the exact workspace and Gradle-owned source roots`() {
        val workspace = Path.of("/workspace")
        val root = (CanonicalWorkspaceRoot.fromCanonicalPath(workspace) as Refinement.Refined).value
        val identity = (WorkspaceStateIdentity.parse("state-7") as Refinement.Refined).value
        val model = projectInstalledGradleModel(
            InstalledGradleModelBoundary(
                root = root,
                importedModelComplete = true,
                sourceRoots = listOf(
                    sourceRoot(workspace.resolve("app/src/main/kotlin"), "main"),
                    sourceRoot(workspace.resolve("app/src/test/kotlin"), "test"),
                ),
                identity = identity,
            ),
        ) as InstalledGradleModelRead.Captured
        val observations = mutableListOf<InstalledRuntimeIndexScope>()
        val observer = object : InstalledRuntimeBootstrapObserver {
            override fun observe(phase: InstalledRuntimeBootstrapPhase) = Unit

            override fun observeIndexScope(scope: InstalledRuntimeIndexScope) {
                observations += scope
            }
        }

        publishInstalledRuntimeIndexScope(model.model, observer)

        assertEquals(root, observations.single().workspaceRoot)
        assertEquals(
            listOf("app/src/main/kotlin", "app/src/test/kotlin"),
            observations.single().sourceRoots.map { it.location.value },
        )
    }

    private fun sourceRoot(path: Path, sourceSet: String): WorkspaceSourceRootBoundary =
        WorkspaceSourceRootBoundary(
            ideaModuleName = "app.$sourceSet",
            linkedBuildRoot = Path.of("/workspace"),
            gradleProjectPath = ":app",
            sourceSetName = sourceSet,
            sourceRoot = path,
            sourceKind = if (sourceSet == "test") {
                WorkspaceSourceRootKind.TEST
            } else {
                WorkspaceSourceRootKind.PRODUCTION
            },
            provenance = WorkspaceSourceRootProvenance.AUTHORED,
        )
}
