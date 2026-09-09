package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.distribution.contract.bootstrap.ModelInputFailureReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InstalledGradleModelInputsTest {
    @Test
    fun `unchanged inputs retain imported authority and ordinary source changes do not invalidate it`(
        @TempDir temporary: Path,
    ) {
        val root = temporary.toRealPath()
        write(root, "build.gradle.kts", "plugins {}")
        val inputs = capture(root)
        write(root, "src/main/kotlin/App.kt", "fun app() = 2")
        write(root, "build/generated/build.gradle.kts", "generated output")
        assertSame(inputs, assertInstanceOf(Refinement.Refined::class.java, inputs.current()).value)
    }

    @Test
    fun `Kotlin tool state inside build logic does not invalidate imported authority`(
        @TempDir temporary: Path,
    ) {
        val root = temporary.toRealPath()
        val source = write(root, "build-logic/src/main/kotlin/Convention.kt", "class Convention")
        val inputs = capture(root)

        write(root, "build-logic/.kotlin/errors/errors.log", "volatile compiler output")
        assertSame(inputs, assertInstanceOf(Refinement.Refined::class.java, inputs.current()).value)

        Files.writeString(source, "class ChangedConvention")
        assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_CHANGED, failure(inputs.current()))
    }

    @Test
    fun `changed deleted and newly added conventional model inputs reject the original import`(
        @TempDir temporary: Path,
    ) {
        val names = listOf(
            "settings.gradle.kts", "library/build.gradle", "gradle.properties",
            "gradle/libs.versions.toml", "gradle/gradle-daemon-jvm.properties",
            "gradle/wrapper/gradle-wrapper.properties", "buildSrc/src/main/kotlin/Conventions.kt",
            "build-logic/conventions/src/main/kotlin/Plugin.kt",
        )
        for ((index, name) in names.withIndex()) {
            val root = Files.createDirectories(temporary.resolve(index.toString())).toRealPath()
            val original = capture(root)
            val input = write(root, name, "initial")
            assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_CHANGED, failure(original.current()))
            val imported = capture(root)
            Files.writeString(input, "changed")
            assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_CHANGED, failure(imported.current()))
            val changed = capture(root)
            Files.delete(input)
            assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_CHANGED, failure(changed.current()))
        }
    }

    @Test
    fun `symlinked Gradle input rejects observation without following its target`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("workspace")).toRealPath()
        val target = Files.writeString(temporary.resolve("outside.gradle.kts"), "plugins {}")
        Files.createSymbolicLink(root.resolve("build.gradle.kts"), target)
        assertInputFailure(root, ModelInputFailureReason.OUTSIDE_WORKSPACE)
    }

    @Test
    fun `unrelated symlinks do not invalidate conventional Gradle inputs`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("workspace")).toRealPath()
        val target = Files.writeString(temporary.resolve("outside.txt"), "documentation")
        val imported = capture(root)
        Files.createSymbolicLink(root.resolve("documentation.txt"), target)
        assertSame(imported, assertInstanceOf(Refinement.Refined::class.java, imported.current()).value)
    }

    @Test
    fun `symlinked conventional model input directory rejects observation`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("workspace")).toRealPath()
        val target = Files.createDirectories(temporary.resolve("outside-build-logic"))
        Files.createSymbolicLink(root.resolve("build-logic"), target)
        assertInputFailure(root, ModelInputFailureReason.OUTSIDE_WORKSPACE)
    }

    @Test
    fun `input movement during capture rejects the produced evidence`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val build = write(root, "build.gradle.kts", "initial")
        val imported = capture(root)
        val result = imported.observeCurrent { authority ->
            assertSame(imported, authority)
            Files.writeString(build, "changed during capture")
            Refinement.Refined(CapturedModel)
        }
        assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_CHANGED, failure(result))
    }

    @Test
    fun `unchanged before and after observations retain callback evidence`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        write(root, "build.gradle.kts", "initial")
        val imported = capture(root)
        val result = imported.observeCurrent { authority ->
            assertSame(imported, authority)
            Refinement.Refined(CapturedModel)
        }
        assertSame(CapturedModel, assertInstanceOf(Refinement.Refined::class.java, result).value)
    }

    @Test
    fun `stale input authority prevents capture effects`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val build = write(root, "build.gradle.kts", "initial")
        val imported = capture(root)
        Files.writeString(build, "already changed")
        var calls = 0
        val result = imported.observeCurrent {
            calls += 1
            Refinement.Refined(CapturedModel)
        }
        assertEquals(0, calls)
        assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_CHANGED, failure(result))
    }

    @Test
    fun `input becoming unavailable during capture rejects the produced evidence`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val build = write(root, "build.gradle.kts", "initial")
        val imported = capture(root)
        val result = imported.observeCurrent {
            Files.delete(build)
            Files.createSymbolicLink(build, root.resolve("absent.gradle.kts"))
            Refinement.Refined(CapturedModel)
        }
        val rejected = failure(result) as InstalledGradleModelCaptureFailure.ModelInputRejected
        assertEquals(ModelInputFailureReason.TARGET_MISSING, rejected.failure.reason)
        assertEquals(InstalledIntellijWorkspaceOpening.ModelInputRejected(rejected.failure), rejected.workspaceOpening())
    }

    @Test
    fun `contained file links retain content and retargeting evidence`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val first = write(root, "shared/first", "same")
        val second = write(root, "shared/second", "same")
        val link = Files.createSymbolicLink(root.resolve("gradle.properties"), first)
        val imported = capture(root)
        assertSame(imported, assertInstanceOf(Refinement.Refined::class.java, imported.current()).value)
        Files.delete(link)
        Files.createSymbolicLink(link, second)
        assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_CHANGED, failure(imported.current()))
        val retargeted = capture(root)
        Files.writeString(second, "changed")
        assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_CHANGED, failure(retargeted.current()))
        org.junit.jupiter.api.Assertions.assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun `directory aliases are distinct and retain selected descendant membership`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val shared = Files.createDirectories(root.resolve("shared"))
        write(shared, "Plugin.kt", "class Plugin")
        val first = Files.createSymbolicLink(root.resolve("build-logic"), shared)
        Files.createDirectories(root.resolve("included"))
        Files.createSymbolicLink(root.resolve("included/build-logic"), shared)
        val imported = capture(root)
        assertSame(imported, assertInstanceOf(Refinement.Refined::class.java, imported.current()).value)
        val added = write(shared, "Other.kt", "class Other")
        assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_CHANGED, failure(imported.current()))
        val expanded = capture(root)
        Files.delete(added)
        assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_CHANGED, failure(expanded.current()))
        Files.delete(first)
        assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_CHANGED, failure(imported.current()))
    }

    @Test
    fun `selected missing cyclic escaping and unsupported links retain logical cause`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("workspace")).toRealPath()
        val link = root.resolve("gradle.properties")
        Files.createSymbolicLink(link, Path.of("missing"))
        assertInputFailure(root, ModelInputFailureReason.TARGET_MISSING, "gradle.properties")
        Files.delete(link)
        Files.createSymbolicLink(link, Path.of("gradle.properties"))
        assertInputFailure(root, ModelInputFailureReason.LINK_CYCLE, "gradle.properties")
        Files.delete(link)
        val chain = Files.createSymbolicLink(root.resolve("chain"), temporary.resolve("outside"))
        Files.createSymbolicLink(link, chain)
        assertInputFailure(root, ModelInputFailureReason.OUTSIDE_WORKSPACE, "gradle.properties")
        Files.delete(link)
        Files.delete(chain)
        Files.createSymbolicLink(root.resolve("build-logic"), root)
        assertInputFailure(root, ModelInputFailureReason.LINK_CYCLE, "build-logic")
    }

    @Test
    fun `parent traversal cannot erase an escaping link boundary`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("workspace")).toRealPath()
        val outside = Files.createDirectories(temporary.resolve("outside"))
        Files.createSymbolicLink(root.resolve("escape"), outside)
        Files.createSymbolicLink(root.resolve("gradle.properties"), Path.of("escape/../workspace/shared"))
        write(root, "shared", "content")
        assertInputFailure(root, ModelInputFailureReason.OUTSIDE_WORKSPACE, "gradle.properties")
    }

    @Test
    fun `excluded outputs and unrelated dangling aliases stay excluded`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val imported = capture(root)
        Files.createSymbolicLink(root.resolve("build"), root.resolve("absent"))
        Files.createSymbolicLink(root.resolve("unrelated"), root.resolve("absent"))
        assertSame(imported, assertInstanceOf(Refinement.Refined::class.java, imported.current()).value)
    }

    @Test
    fun `changing resolved entry kind invalidates authority`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val target = write(root, "shared", "value")
        Files.createSymbolicLink(root.resolve("gradle.properties"), target)
        val imported = capture(root)
        Files.delete(target)
        Files.createDirectory(target)
        assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_CHANGED, failure(imported.current()))
    }

    @Test
    fun `file cannot supply directory traversal proof`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        write(root, "file", "content")
        write(root, "shared", "content")
        for (target in listOf("file/../shared", "file/.")) {
            val link = Files.createSymbolicLink(root.resolve("gradle.properties"), Path.of(target))
            assertInputFailure(root, ModelInputFailureReason.UNSUPPORTED, "gradle.properties")
            Files.delete(link)
        }
    }

    @Test
    fun `unreadable selected input rejects with its logical path`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val input = write(root, "gradle.properties", "secret")
        val permissions = Files.getPosixFilePermissions(input)
        try {
            Files.setPosixFilePermissions(input, emptySet())
            assertInputFailure(root, ModelInputFailureReason.UNREADABLE, "gradle.properties")
        } finally {
            Files.setPosixFilePermissions(input, permissions)
        }
    }

    private fun assertInputFailure(root: Path, reason: ModelInputFailureReason, logical: String? = null) {
        val rejected = failure(InstalledGradleModelInputs.capture(root)) as InstalledGradleModelCaptureFailure.ModelInputRejected
        assertEquals(reason, rejected.failure.reason)
        if (logical != null) assertEquals(logical, rejected.failure.path.value)
    }

    private data object CapturedModel

    private fun capture(root: Path): InstalledGradleModelInputs =
        when (val captured = InstalledGradleModelInputs.capture(root)) {
            is Refinement.Refined -> captured.value
            is Refinement.Rejected -> error(captured.failure)
        }

    private fun failure(result: Refinement<*, InstalledGradleModelCaptureFailure>): InstalledGradleModelCaptureFailure =
        assertInstanceOf(Refinement.Rejected::class.java, result).failure as InstalledGradleModelCaptureFailure

    private fun write(root: Path, relative: String, value: String): Path {
        val path = root.resolve(relative)
        Files.createDirectories(path.parent)
        return Files.writeString(path, value)
    }
}
