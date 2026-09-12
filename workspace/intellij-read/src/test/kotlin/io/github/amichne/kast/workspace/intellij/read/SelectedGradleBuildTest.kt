package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class SelectedGradleBuildTest {
    private val selected =
        GradleBuildIdentity.selected(
            (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value
        )

    private val index =
        gradleProjectIndex(
            (CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")) as Refinement.Refined).value,
            listOf(
                org.jetbrains.plugins.gradle.model.DefaultExternalProject().apply {
                    projectDir = Path.of("/workspace/app").toFile()
                    path = ":app"
                }
            ),
        )

    @Test
    fun `build identity normalizes metadata once before exact comparison`() {
        for (path in listOf("/workspace", "/workspace/", "/workspace/./", "/workspace/child/..")) {
            val candidate = (GradleBuildIdentity.observe(path, ReadLimits.Default) as Refinement.Refined).value
            val proof =
                assertInstanceOf(
                    GradleBuildMembership.SelectedBuild::class.java,
                    GradleBuildMembership.SelectedBuild.classify(selected, candidate),
                )
            assertSame(selected, proof.build)
        }
    }

    @Test
    fun `included and independent builds are different authorities regardless of directory spelling`() {
        for (path in listOf("/workspace/build-logic", "/workspace/app", "/independent", "/workspace-other")) {
            val candidate = (GradleBuildIdentity.observe(path, ReadLimits.Default) as Refinement.Refined).value
            assertEquals(
                GradleBuildMembership.ForeignBuild,
                GradleBuildMembership.SelectedBuild.classify(selected, candidate),
            )
        }
    }

    @Test
    fun `missing and malformed build metadata cannot classify as foreign or selected`() {
        for (path in listOf(null, "", " ", "relative", "/bad\u0000root", "/" + "x".repeat(4097))) {
            assertInstanceOf(Refinement.Rejected::class.java, GradleBuildIdentity.observe(path, ReadLimits.Default))
        }
    }

    @Test
    fun `platform admission excludes foreign modules before project ownership lookup`() {
        val foreign = gradleModule("foreign", "/independent", null)
        assertEquals(
            Refinement.Refined(SelectedGradleModuleAdmission.ForeignBuild),
            AdmittedSelectedBuildModule.admit(foreign, index, ReadLimits.Default),
        )
        val main = gradleModule("main", "/workspace", "/workspace/app")
        val admission = (AdmittedSelectedBuildModule.admit(main, index, ReadLimits.Default) as Refinement.Refined).value
        val proof = assertInstanceOf(SelectedGradleModuleAdmission.SelectedBuild::class.java, admission).module
        assertSame(main, proof.module)
        assertSame(index.selected, proof.ownership.build)
        assertEquals("main", proof.identity.value)
    }

    @Test
    fun `unknown Gradle ownership fails closed without observing folders`() {
        for (module in
            listOf(
                gradleModule("unknown", null, "/workspace/app"),
                gradleModule(name = "other", build = "/workspace", project = "/workspace/app", system = "MAVEN"),
            )) {
            assertInstanceOf(
                Refinement.Rejected::class.java,
                AdmittedSelectedBuildModule.admit(module, index, ReadLimits.Default),
            )
        }
    }
}
