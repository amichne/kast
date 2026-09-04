package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class InstalledSemanticProjectStoreTest {
    @Test
    fun `each preparation creates one fresh configured store outside the workspace`(@TempDir root: Path) {
        val workspacePath = Files.createDirectory(root.resolve("workspace & source")).toRealPath()
        val workspace = canonicalWorkspace(workspacePath)
        val state = Files.createDirectory(root.resolve("state")).toRealPath()

        val first = prepared(workspace, state)
        val second = prepared(workspace, state)

        assertNotEquals(first.path, second.path)
        listOf(first, second).forEach { store ->
            assertTrue(store.path.startsWith(state))
            assertFalse(store.path.startsWith(workspacePath))
            assertEquals(store.path.toString(), store.root.value)
            assertEquals(workspace, store.root.workspaceRoot)
            assertTrue(Files.isRegularFile(store.path.resolve(".idea/modules.xml")))
            assertTrue(Files.isRegularFile(store.path.resolve(".idea/kast-index-bootstrap.iml")))
        }
    }

    @Test
    fun `bootstrap module excludes generated output directories before project open`(
        @TempDir root: Path,
    ) {
        val workspacePath = Files.createDirectory(root.resolve("workspace & source")).toRealPath()
        Files.createDirectories(workspacePath.resolve(".gradle/caches"))
        Files.createDirectories(workspacePath.resolve(".kotlin/sessions"))
        Files.createDirectories(workspacePath.resolve("build/generated/sources"))
        Files.createDirectories(workspacePath.resolve("module/build/classes"))
        Files.createDirectories(workspacePath.resolve("module/src/main/kotlin"))
        Files.createDirectories(workspacePath.resolve("topology/build/build/classes"))
        Files.createDirectories(workspacePath.resolve("topology/build/src/main/kotlin"))
        Files.createDirectories(
            workspacePath.resolve("topology/build/src/main/kotlin/example/build"),
        )
        Files.createDirectories(workspacePath.resolve("docs/node_modules/package"))
        Files.writeString(workspacePath.resolve("build.gradle.kts"), "plugins {}\n")
        Files.writeString(workspacePath.resolve("module/build.gradle.kts"), "plugins {}\n")
        Files.writeString(
            workspacePath.resolve("topology/build/build.gradle.kts"),
            "plugins {}\n",
        )
        Files.writeString(
            workspacePath.resolve("topology/build/src/main/kotlin/example/build/Fixture.kt"),
            "package example.build\nclass Fixture\n",
        )
        val state = Files.createDirectory(root.resolve("state")).toRealPath()

        val store = prepared(canonicalWorkspace(workspacePath), state)

        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val modules = parser.parse(store.path.resolve(".idea/modules.xml").toFile())
        assertEquals(
            "\$PROJECT_DIR\$/.idea/kast-index-bootstrap.iml",
            modules.getElementsByTagName("module").item(0).attributes
                .getNamedItem("filepath").nodeValue,
        )
        val module = parser.parse(store.path.resolve(".idea/kast-index-bootstrap.iml").toFile())
        val excludedUrls = module.getElementsByTagName("excludeFolder")
            .let { nodes -> (0 until nodes.length).map { index -> nodes.item(index).attributes
                .getNamedItem("url").nodeValue } }
        assertEquals(
            listOf(
                "file://${workspacePath.resolve(".gradle")}",
                "file://${workspacePath.resolve(".kotlin")}",
                "file://${workspacePath.resolve("build")}",
                "file://${workspacePath.resolve("docs/node_modules")}",
                "file://${workspacePath.resolve("module/build")}",
                "file://${workspacePath.resolve("topology/build/build")}",
            ),
            excludedUrls,
        )
        assertFalse(excludedUrls.any { url -> url.contains("%20") })
        assertFalse(excludedUrls.any { url -> url.contains("src/main/kotlin") })
        assertFalse(excludedUrls.contains("file://${workspacePath.resolve("topology/build")}"))
        assertFalse(
            excludedUrls.contains(
                "file://${workspacePath.resolve("topology/build/src/main/kotlin/example/build")}",
            ),
        )
    }

    @Test
    fun `runtime state inside the workspace fails before creating a store`(@TempDir root: Path) {
        val workspacePath = Files.createDirectory(root.resolve("workspace")).toRealPath()
        val workspace = canonicalWorkspace(workspacePath)
        val state = Files.createDirectory(workspacePath.resolve("runtime-state")).toRealPath()

        val rejected = assertInstanceOf(
            InstalledSemanticProjectStorePreparation.Rejected::class.java,
            InstalledSemanticProjectStore.prepare(workspace, state),
        )

        assertEquals(InstalledSemanticProjectStoreFailure.OVERLAPS_WORKSPACE, rejected.failure)
        Files.list(state).use { entries -> assertEquals(0L, entries.count()) }
    }

    private fun prepared(
        workspace: CanonicalWorkspaceRoot,
        state: Path,
    ): InstalledSemanticProjectStore = assertInstanceOf(
        InstalledSemanticProjectStorePreparation.Prepared::class.java,
        InstalledSemanticProjectStore.prepare(workspace, state),
    ).store

    private fun canonicalWorkspace(path: Path): CanonicalWorkspaceRoot = when (
        val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(path)
    ) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error(admitted.failure)
    }
}
