package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.distribution.contract.gradle.GradleImportEnvironment
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstalledGradleSemanticIdentityTest {
    @Test
    fun `semantic identity is reproducible across capture ordering`(@TempDir temporary: Path) {
        val rootPath = Files.createDirectories(temporary.resolve("workspace")).toRealPath()
        val root = canonicalRoot(rootPath)
        val sourceRoot = sourceRoot(rootPath)
        val sourceContents =
            listOf(
                InstalledSourceContentIdentity.Root(
                    sourcePath("src/main/kotlin"),
                    InstalledSourceRootState.Present,
                ),
                InstalledSourceContentIdentity.File(
                    sourcePath("src/main/kotlin/example/App.kt"),
                    contentHash('a'),
                ),
            )
        val modules =
            listOf(
                module("app", "kotlin-stdlib"),
                module("library", "annotations"),
            )
        val first =
            InstalledGradleSemanticIdentityBoundary(
                root,
                listOf(sourceRoot),
                sourceContents,
                listOf(rootPath, rootPath.resolve("included")),
                modules,
            )
        val reordered =
            InstalledGradleSemanticIdentityBoundary(
                root,
                listOf(sourceRoot),
                sourceContents.reversed(),
                listOf(rootPath.resolve("included"), rootPath),
                modules.reversed(),
            )

        assertEquals(identity(first), identity(reordered))
    }

    @Test
    fun `admitted import input changes invalidate semantic identity`(@TempDir temporary: Path) {
        val rootPath = Files.createDirectories(temporary.resolve("workspace")).toRealPath()
        val root = canonicalRoot(rootPath)
        val original = boundary(root, rootPath, sourceRoot(rootPath), contentHash('a'))
        val changed =
            (GradleImportEnvironment.admit("IMPORT_PROPERTY", "", mapOf("IMPORT_PROPERTY" to "new"))
                    as Refinement.Refined)
                .value
        assertNotEquals(identity(original), identity(original.copy(importEnvironmentIdentity = changed.identity)))
    }

    @Test
    fun `semantic source movement changes identity`(@TempDir temporary: Path) {
        val rootPath = Files.createDirectories(temporary.resolve("workspace")).toRealPath()
        val root = canonicalRoot(rootPath)
        val sourceRoot = sourceRoot(rootPath)
        val before = boundary(root, rootPath, sourceRoot, contentHash('a'))
        val after = boundary(root, rootPath, sourceRoot, contentHash('b'))

        assertNotEquals(identity(before), identity(after))
    }

    @Test
    fun `detached capture rehashes current source content`(@TempDir temporary: Path) {
        val rootPath = Files.createDirectories(temporary.resolve("workspace")).toRealPath()
        val sourceDirectory = Files.createDirectories(rootPath.resolve("src/main/kotlin/example"))
        val source = sourceDirectory.resolve("App.kt")
        Files.writeString(source, "fun value() = 1")
        val root = canonicalRoot(rootPath)
        val sourceRoot = sourceRoot(rootPath)
        val boundary = boundary(root, rootPath, sourceRoot, contentHash('a'))
        val capture =
            InstalledGradleModelCapture(
                root,
                listOf(sourceRoot),
                identity(boundary),
                boundary,
                inputs(rootPath),
            )
        val before = currentIdentity(capture)

        Files.writeString(source, "fun value() = 2")

        assertNotEquals(before, currentIdentity(capture))
    }

    @Test
    fun `detached capture rejects changed Gradle model inputs`(@TempDir temporary: Path) {
        val rootPath = Files.createDirectories(temporary.resolve("workspace")).toRealPath()
        val sourceDirectory = Files.createDirectories(rootPath.resolve("src/main/kotlin/example"))
        Files.writeString(sourceDirectory.resolve("App.kt"), "fun value() = 1")
        val build = rootPath.resolve("build.gradle.kts")
        Files.writeString(build, "plugins { kotlin(\"jvm\") }")
        val root = canonicalRoot(rootPath)
        val sourceRoot = sourceRoot(rootPath)
        val boundary = boundary(root, rootPath, sourceRoot, contentHash('a'))
        val capture =
            InstalledGradleModelCapture(root, listOf(sourceRoot), identity(boundary), boundary, inputs(rootPath))
        currentIdentity(capture)

        Files.writeString(build, "plugins { kotlin(\"jvm\") }; dependencies { implementation(\"new:dependency:1\") }")

        assertInstanceOf(Refinement.Rejected::class.java, capture.captureCurrentSemanticIdentity())
    }

    @Test
    fun `SDK installations with the same version retain distinct semantic identities`(@TempDir temporary: Path) {
        val rootPath = Files.createDirectories(temporary.resolve("workspace")).toRealPath()
        val root = canonicalRoot(rootPath)
        val original = boundary(root, rootPath, sourceRoot(rootPath), contentHash('a'))
        fun withSdk(home: String) =
            original.copy(
                modules =
                    original.modules.map { module ->
                        module.copy(
                            sdk =
                                InstalledSdkSemanticIdentity.Present(
                                    InstalledSdkVersion.Known("21"),
                                    InstalledSdkHome.Known(home),
                                )
                        )
                    }
            )
        assertNotEquals(identity(withSdk("/jdks/one")), identity(withSdk("/jdks/two")))
    }

    @Test
    fun `classpath lookup order remains part of semantic identity`(@TempDir temporary: Path) {
        val rootPath = Files.createDirectories(temporary.resolve("workspace")).toRealPath()
        val root = canonicalRoot(rootPath)
        val original = boundary(root, rootPath, sourceRoot(rootPath), contentHash('a'))
        val first = InstalledClasspathEntrySemanticIdentity("file:///dependencies/first.jar")
        val second = InstalledClasspathEntrySemanticIdentity("file:///dependencies/second.jar")
        fun ordered(entries: List<InstalledClasspathEntrySemanticIdentity>) =
            original.copy(modules = original.modules.map { it.copy(classpath = entries) })
        assertNotEquals(identity(ordered(listOf(first, second))), identity(ordered(listOf(second, first))))
    }

    private fun inputs(root: Path): InstalledGradleModelInputs =
        when (val captured = InstalledGradleModelInputs.capture(root)) {
            is Refinement.Refined -> captured.value
            is Refinement.Rejected -> error(captured.failure)
        }

    private fun boundary(
        root: CanonicalWorkspaceRoot,
        rootPath: Path,
        sourceRoot: WorkspaceSourceRootBoundary,
        hash: WorkspaceSourceContentHash,
    ): InstalledGradleSemanticIdentityBoundary =
        InstalledGradleSemanticIdentityBoundary(
            root,
            listOf(sourceRoot),
            listOf(
                InstalledSourceContentIdentity.Root(
                    sourcePath("src/main/kotlin"),
                    InstalledSourceRootState.Present,
                ),
                InstalledSourceContentIdentity.File(
                    sourcePath("src/main/kotlin/example/App.kt"),
                    hash,
                ),
            ),
            listOf(rootPath),
            listOf(module("app", "kotlin-stdlib")),
        )

    private fun module(
        name: String,
        dependency: String,
    ): InstalledModuleSemanticIdentity =
        InstalledModuleSemanticIdentity(
            name,
            InstalledSdkSemanticIdentity.Present(InstalledSdkVersion.Known("21.0.7")),
            listOf(InstalledClasspathEntrySemanticIdentity("file:///dependencies/$dependency.jar")),
        )

    private fun identity(boundary: InstalledGradleSemanticIdentityBoundary): WorkspaceStateIdentity =
        when (val derived = deriveInstalledGradleSemanticIdentity(boundary)) {
            is Refinement.Refined -> derived.value
            is Refinement.Rejected -> error(derived.failure)
        }

    private fun currentIdentity(capture: InstalledGradleModelCapture): WorkspaceStateIdentity =
        when (val captured = capture.captureCurrentSemanticIdentity()) {
            is Refinement.Refined -> captured.value
            is Refinement.Rejected -> error(captured.failure)
        }

    private fun canonicalRoot(path: Path): CanonicalWorkspaceRoot =
        when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(path)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error(admitted.failure)
        }

    private fun sourcePath(value: String): WorkspaceSourcePath =
        when (val admitted = WorkspaceSourcePath.parse(value)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error(admitted.failure)
        }

    private fun contentHash(character: Char): WorkspaceSourceContentHash =
        when (val admitted = WorkspaceSourceContentHash.parse(character.toString().repeat(64))) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> error(admitted.failure)
        }

    private fun sourceRoot(root: Path): WorkspaceSourceRootBoundary =
        WorkspaceSourceRootBoundary(
            "app.main",
            root,
            ":",
            "main",
            root.resolve("src/main/kotlin"),
            WorkspaceSourceRootKind.PRODUCTION,
            WorkspaceSourceRootProvenance.AUTHORED,
        )
}
