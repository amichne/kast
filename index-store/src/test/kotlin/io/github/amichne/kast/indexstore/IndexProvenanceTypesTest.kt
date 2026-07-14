package io.github.amichne.kast.indexstore

import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleProjectIdentity
import io.github.amichne.kast.indexstore.api.index.BuildQualifiedGradleSourceSetIdentity
import io.github.amichne.kast.indexstore.api.index.FileIndexUpdate
import io.github.amichne.kast.indexstore.api.index.GradleProjectPath
import io.github.amichne.kast.indexstore.api.index.GradleSourceSetName
import io.github.amichne.kast.indexstore.api.index.IndexedPackageEvidence
import io.github.amichne.kast.indexstore.api.index.IndexedPackageUnprovenReason
import io.github.amichne.kast.indexstore.api.index.WorkspaceRelativeGradleBuildRoot
import io.github.amichne.kast.indexstore.api.index.parseSourceFileIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class IndexProvenanceTypesTest {
    @Test
    fun `file update preserves typed provenance and rejects source sets without their project owner`() {
        val project = BuildQualifiedGradleProjectIdentity(
            buildRoot = WorkspaceRelativeGradleBuildRoot.parse("."),
            projectPath = GradleProjectPath.parse(":app"),
        )
        val sourceSet = BuildQualifiedGradleSourceSetIdentity(
            project = project,
            sourceSet = GradleSourceSetName.parse("integrationTest"),
        )

        val update = FileIndexUpdate(
            path = "src/App.kt",
            identifiers = emptySet(),
            packageName = "legacy.label",
            modulePath = ":app",
            sourceSet = "legacyTest",
            imports = emptySet(),
            wildcardImports = emptySet(),
            gradleProjects = setOf(project),
            gradleSourceSets = setOf(sourceSet),
            packageEvidence = IndexedPackageEvidence.ProvenNamed(
                IndexedPackageEvidence.CanonicalName.parse("com.example.`when`"),
            ),
        )

        assertEquals(setOf(project), update.gradleProjects)
        assertEquals(setOf(sourceSet), update.gradleSourceSets)
        assertThrows(IllegalArgumentException::class.java) {
            update.copy(gradleProjects = emptySet())
        }
    }

    @Test
    fun `legacy text parser marks package output unproven`() {
        val update = parseSourceFileIndex(
            path = "src/Legacy.kt",
            content = "package legacy.label\nclass Legacy",
        )

        assertEquals(
            IndexedPackageEvidence.Unproven(IndexedPackageUnprovenReason.LEGACY_TEXT_ONLY),
            update.packageEvidence,
        )
    }

    @Test
    fun `build-qualified project identity keeps root and included builds distinct`() {
        val rootProject = BuildQualifiedGradleProjectIdentity(
            buildRoot = WorkspaceRelativeGradleBuildRoot.parse("."),
            projectPath = GradleProjectPath.parse(":app"),
        )
        val includedProject = BuildQualifiedGradleProjectIdentity(
            buildRoot = WorkspaceRelativeGradleBuildRoot.parse("tools/build-logic"),
            projectPath = GradleProjectPath.parse(":app"),
        )

        assertNotEquals(rootProject, includedProject)
        assertEquals(":app", rootProject.projectPath.value)
    }

    @Test
    fun `build root canonicalizes platform separators but rejects absolute and escaping paths`() {
        assertEquals(
            "tools/build-logic",
            WorkspaceRelativeGradleBuildRoot.parse("tools\\build-logic").value,
        )
        listOf("", "/absolute", "C:\\absolute", "C:relative", "//server/share", "../outside", "tools/../outside")
            .forEach { raw ->
                assertThrows(IllegalArgumentException::class.java) {
                    WorkspaceRelativeGradleBuildRoot.parse(raw)
                }
            }
    }

    @Test
    fun `Gradle project path requires an absolute nonempty segment path`() {
        assertEquals(":", GradleProjectPath.parse(":").value)
        assertEquals(":feature:api", GradleProjectPath.parse(":feature:api").value)
        listOf("", "app", "::app", ":app:", ":app/api", ":app\\api").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { GradleProjectPath.parse(raw) }
        }
    }

    @Test
    fun `source-set identity carries its build-qualified project proof`() {
        val project = BuildQualifiedGradleProjectIdentity(
            buildRoot = WorkspaceRelativeGradleBuildRoot.parse("included"),
            projectPath = GradleProjectPath.parse(":quality"),
        )
        val identity = BuildQualifiedGradleSourceSetIdentity(
            project = project,
            sourceSet = GradleSourceSetName.parse("integrationTest"),
        )

        assertEquals("integrationTest", identity.sourceSet.value)
        assertThrows(IllegalArgumentException::class.java) { GradleSourceSetName.parse(" ") }
        assertThrows(IllegalArgumentException::class.java) { GradleSourceSetName.parse("main/test") }
    }

    @Test
    fun `package evidence cannot collapse missing semantics into the root package`() {
        val root = IndexedPackageEvidence.ProvenRoot
        val named = IndexedPackageEvidence.ProvenNamed(
            IndexedPackageEvidence.CanonicalName.parse("com.example.`when`"),
        )
        val unproven = IndexedPackageEvidence.Unproven(
            IndexedPackageUnprovenReason.SEMANTIC_ANALYSIS_UNAVAILABLE,
        )

        assertNotEquals(root, unproven)
        assertEquals("com.example.`when`", named.canonicalName.value)
        assertThrows(IllegalArgumentException::class.java) {
            IndexedPackageEvidence.CanonicalName.parse("")
        }
    }
}
