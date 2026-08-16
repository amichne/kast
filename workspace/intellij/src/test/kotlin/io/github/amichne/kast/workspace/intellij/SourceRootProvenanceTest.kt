package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.externalSystem.model.project.ContentRootData
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProvenanceFailure
import io.github.amichne.kast.workspace.contract.SourceRootProvenance
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SourceRootProvenanceTest {
    @TempDir
    lateinit var workspaceDirectory: Path

    @Test
    fun `Gradle source type establishes provenance without path inference`() {
        val authored = admit(
            sourceRoot = physicalWorkspace().resolve("build/generated/path-name.kt"),
            sourceType = ExternalSystemSourceType.SOURCE,
        )
        val generated = admit(
            sourceRoot = physicalWorkspace().resolve("src/main/authored-path-name.kt"),
            sourceType = ExternalSystemSourceType.SOURCE_GENERATED,
        )

        assertEquals(SourceRootProvenance.Authored, authored.provenance)
        assertEquals(SourceRootProvenance.Generated, generated.provenance)
    }

    @Test
    fun `source-set ownership survives the Gradle bridge`() {
        val root = admit(
            sourceRoot = physicalWorkspace().resolve("app/src/main/kotlin"),
            sourceType = ExternalSystemSourceType.SOURCE,
        )

        assertEquals("app.main", root.owner.module.value)
        assertEquals(".", root.owner.project.buildRoot.value)
        assertEquals(":app", root.owner.project.projectPath.value)
        assertEquals("main", root.owner.sourceSet.value)
    }

    @Test
    fun `unclassified model roots remain explicitly unknown regardless of path`() {
        val root = admit(
            sourceRoot = physicalWorkspace().resolve("src/main/kotlin"),
            sourceType = ExternalSystemSourceType.EXCLUDED,
        )

        assertEquals(
            SourceRootProvenance.Unknown(ProvenanceFailure.ExcludedFromSourceModel),
            root.provenance,
        )
        assertFalse(root.provenance is SourceRootProvenance.Authored)
        assertFalse(root.provenance is SourceRootProvenance.Generated)
    }

    @Test
    fun `raw Paths do not escape the Gradle bridge`() {
        val root = admit(
            sourceRoot = physicalWorkspace().resolve("app/src/main/kotlin"),
            sourceType = ExternalSystemSourceType.SOURCE,
        )

        assertTrue(
            root.javaClass.methods.none { method ->
                Path::class.java.isAssignableFrom(method.returnType)
            },
        )
    }

    private fun admit(
        sourceRoot: Path,
        sourceType: ExternalSystemSourceType,
    ) = assertInstanceOf<GradleSourceRootAdmission.Admitted>(
        GradleSourceRootBridge.admit(
            workspaceRoot = workspaceRoot(),
            sourceSet = GradleSourceSetData(
                "JAVA_MODULE",
                ":app:main",
                ":app:main",
                "app.main",
                physicalWorkspace().resolve("app").toString(),
                physicalWorkspace().toString(),
            ),
            sourceRoot = ContentRootData.SourceRoot(sourceRoot.toString(), ""),
            sourceType = sourceType,
        ),
    ).root

    private fun workspaceRoot(): CanonicalWorkspaceRoot = when (
        val result = CanonicalWorkspaceRoot.fromCanonicalPath(physicalWorkspace())
    ) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> error(result.failure)
    }

    private fun physicalWorkspace(): Path = workspaceDirectory.toRealPath()
}
