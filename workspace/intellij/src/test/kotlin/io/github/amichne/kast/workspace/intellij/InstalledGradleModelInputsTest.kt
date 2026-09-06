package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.kernel.Refinement
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
        assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_UNAVAILABLE, failure(InstalledGradleModelInputs.capture(root)))
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
        assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_UNAVAILABLE, failure(InstalledGradleModelInputs.capture(root)))
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
        assertEquals(InstalledGradleModelCaptureFailure.MODEL_INPUTS_UNAVAILABLE, failure(result))
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
