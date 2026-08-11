package io.github.amichne.kast.indexer.gradle.bootstrap

import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity
import io.github.amichne.kast.indexer.project.ProjectModelBootstrapResult
import io.github.amichne.kast.indexer.project.WorkspaceKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GradleProjectBootstrapBuildIdentityTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `Gradle bootstrap rejects model reuse when build definitions move during import`() {
        val workspace = tempDir.resolve("moving-build")
        val before = BuildSemanticInputIdentity("before")
        val after = BuildSemanticInputIdentity("after")
        val observedDefinitions = ArrayDeque(listOf(before, after))
        val bootstrap = GradleProjectBootstrap(
            configureGradleImport = {},
            waitForProjectModel = { settlementEvidence() },
            inspectProjectModel = { modelReadiness(moduleNames = listOf(":app")) },
            canLinkGradleProject = { _, _ -> true },
            hasLinkedGradleProject = { _, _ -> true },
            captureBuildSemanticInputIdentity = { _, _ -> observedDefinitions.removeFirst() },
        )

        val bootstrapped = bootstrap.bootstrapProject(projectStub(), workspace, WorkspaceKind.GRADLE)

        assertEquals(
            ProjectModelBootstrapResult.Ready(moduleNames = listOf(":app"), linkedGradleProject = true),
            bootstrapped.result,
        )
        assertEquals(InitialProjectModelAuthority.Unverified, bootstrapped.initialProjectModelAuthority)
    }

    @Test
    fun `ready Gradle bootstrap carries its single after-bootstrap identity`() {
        val workspace = tempDir.resolve("stable-build")
        val stable = BuildSemanticInputIdentity("stable")
        val bootstrap = GradleProjectBootstrap(
            configureGradleImport = {},
            waitForProjectModel = { settlementEvidence() },
            inspectProjectModel = { modelReadiness(moduleNames = listOf(":app")) },
            canLinkGradleProject = { _, _ -> true },
            hasLinkedGradleProject = { _, _ -> true },
            captureBuildSemanticInputIdentity = { _, _ -> stable },
        )

        val bootstrapped = bootstrap.bootstrapProject(projectStub(), workspace, WorkspaceKind.GRADLE)
        val carried = bootstrapped.initialProjectModelAuthority.fold(
            onUnverified = { error("stable ready bootstrap was not admitted") },
            onImported = { identity -> identity },
        )

        assertEquals(stable, carried)
    }

    @Test
    fun `plain bootstrap cannot grant imported Gradle model authority`() {
        val workspace = tempDir.resolve("plain")
        val stable = BuildSemanticInputIdentity("stable")
        val bootstrap = GradleProjectBootstrap(
            captureBuildSemanticInputIdentity = { _, _ -> stable },
        )

        val bootstrapped = bootstrap.bootstrapProject(projectStub(), workspace, WorkspaceKind.PLAIN)

        assertEquals(ProjectModelBootstrapResult.Skipped("not a Gradle project"), bootstrapped.result)
        assertEquals(InitialProjectModelAuthority.Unverified, bootstrapped.initialProjectModelAuthority)
    }
}
