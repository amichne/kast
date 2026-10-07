package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PassiveCompilerClasspathJarTest {
    @TempDir lateinit var root: Path

    @Test
    fun `ordinary runtime archive has no compiler provider capability`() {
        val path = jar("runtime.jar", "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n\n", "Runtime.class" to "bytes")
        assertEquals(Refinement.Refined(Unit), observe(path))
    }

    @Test
    fun `service provider manifest classpath and jar index each reject optional reuse`() {
        val paths =
            listOf(
                jar(
                    "provider.jar",
                    "META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar" to
                        "fixture.Plugin",
                ),
                jar("classpath.jar", "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nClass-Path: unknown.jar\n\n"),
                jar("index.jar", "META-INF/INDEX.LIST" to "JarIndex-Version: 1.0\nunknown.jar\n"),
            )
        for (path in paths) assertEquals(unmodeled(), observe(path))
    }

    @Test
    fun `malformed and duplicate entry archives never establish passive input proof`() {
        val malformed = root.resolve("malformed.jar")
        Files.writeString(malformed, "not a zip")
        assertEquals(unmodeled(), observe(malformed))
        val duplicate = jar("duplicates.jar", "a.txt" to "one", "b.txt" to "two")
        val bytes = Files.readAllBytes(duplicate)
        val name = "b.txt".toByteArray()
        for (index in 0..bytes.size - name.size) {
            if (name.indices.all { bytes[index + it] == name[it] }) bytes[index] = 'a'.code.toByte()
        }
        Files.write(duplicate, bytes)
        assertEquals(unmodeled(), observe(duplicate))
    }

    @Test
    fun `metadata enumeration charges work and halts before exceeding grant`() {
        val path = jar("bounded.jar", "a.class" to "one", "b.class" to "two")
        val budget = budget(1)
        assertEquals(
            Refinement.Rejected(SemanticDependencyCaptureFailure.WORK_EXHAUSTED),
            observePassiveCompilerClasspathJar(path, ReadLimits.Default, budget),
        )
        assertEquals(1L, budget.cost().workUnits)
    }

    private fun observe(path: Path) = observePassiveCompilerClasspathJar(path, ReadLimits.Default, budget(100))

    private fun unmodeled() = Refinement.Rejected(SemanticDependencyCaptureFailure.COMPILER_PLUGIN_INPUTS_UNMODELED)

    private fun budget(work: Long) =
        DependencyCaptureBudget(
            ResourceBudget(
                (ResultLimit.parse(1) as Refinement.Refined).value,
                (WorkUnitLimit.parse(work) as Refinement.Refined).value,
                (ElapsedTimeLimitMillis.parse(1000) as Refinement.Refined).value,
            ),
            { 0L },
            {},
        )

    private fun jar(name: String, vararg entries: Pair<String, String>): Path =
        root.resolve(name).also { path ->
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                for ((entry, value) in entries) {
                    zip.putNextEntry(ZipEntry(entry))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
        }
}
