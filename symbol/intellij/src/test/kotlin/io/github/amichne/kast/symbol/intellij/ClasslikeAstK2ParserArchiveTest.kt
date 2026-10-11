package io.github.amichne.kast.symbol.intellij

import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.Controls
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.discover
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.withParserDefinition
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.withUnsupportedParser
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Exact named K2 class on the trusted JDK loader still needs its own source fingerprint. */
class ClasslikeAstK2ParserArchiveTest {
    @Test
    fun `changed K2 archive retains old visitor even when inherited factories are unchanged`(@TempDir home: Path) =
        child(home, changed = true)

    @Test
    fun `original K2 archive qualifies under the same child boundary`(@TempDir home: Path) =
        child(home, changed = false)

    private fun child(home: Path, changed: Boolean) {
        val classpath = System.getProperty("java.class.path")
        val ownedArchive = home.resolve("changed-parser.jar")
        if (changed) {
            val type = ClasslikeAstK2ParserFixture.type
            val original = requireNotNull(type.getResourceAsStream("${type.simpleName}.class")).use { it.readBytes() }
            val originalText = String(original, Charsets.ISO_8859_1)
            assertTrue("fun injectedFragment() { Unit }" in originalText)
            val bytes =
                originalText
                    .replace("fun injectedFragment() { Unit }", "fun modifiedFragment() { Unit }")
                    .toByteArray(Charsets.ISO_8859_1)
            assertEquals(original.size, bytes.size)
            JarOutputStream(Files.newOutputStream(ownedArchive)).use { jar ->
                jar.putNextEntry(JarEntry(type.name.replace('.', '/') + ".class"))
                jar.write(bytes)
                jar.closeEntry()
            }
        }
        val command =
            listOf(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.awt.headless=true",
                "-cp",
                if (changed) "$ownedArchive${java.io.File.pathSeparator}$classpath" else classpath,
                ClasslikeAstK2ParserArchiveWitness::class.java.name,
                home.toString(),
                changed.toString(),
            )
        val output = home.resolve("child-output.txt")
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "owned parser child must terminate")
            assertEquals(0, process.exitValue(), Files.readString(output))
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "owned child cleanup must terminate")
            }
        }
    }
}

object ClasslikeAstK2ParserArchiveWitness {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val home = Path.of(arguments[0])
        val changed = arguments[1].toBooleanStrict()
        val definition = ClasslikeAstK2ParserFixture.definition()
        if (changed)
            assertEquals(
                home.resolve("changed-parser.jar").toRealPath(),
                Path.of(definition.javaClass.protectionDomain.codeSource.location.toURI()).toRealPath(),
            )
        withParser(home.resolve("parser")) { project ->
            withParserDefinition(definition) {
                assertBinding(changed)
                val source =
                    fixture(
                        project,
                        home.resolve("source"),
                        mapOf("Input.kt" to "class Target\nfun outer() { class Target }"),
                        setOf(CompilerSymbolKind.CLASSLIKE),
                    )
                val candidate = discover(project, source, "Target", Controls(indexed = false))
                assertTrue(candidate.outcome is SymbolDiscoveryOutcome.Complete)
                assertEquals(listOf("Target", "Target"), candidate.outcome.batch().candidates.map { it.name.value })
                val baseline = withUnsupportedParser { discover(project, source, "Target", Controls(indexed = false)) }
                assertEquals(baseline.outcome, candidate.outcome)
                assertEquals(
                    0,
                    candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_PARSER_FALLBACKS] ?: 0,
                )
                assertEquals(
                    if (changed) 1 else 0,
                    candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_LEGACY_FALLBACKS] ?: 0,
                )
                if (changed)
                    assertEquals(
                        0,
                        candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED] ?: 0,
                    )
                else
                    assertTrue(
                        (candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED] ?: 0) > 0
                    )
            }
        }
    }

    private fun assertBinding(changed: Boolean) {
        val observation = RecordingDiscoveryObservation { 0L }
        val binding = IntellijClasslikeAstBinding.Owner().acquire({ true }, observation)
        if (changed) {
            assertSame(IntellijClasslikeAstBinding.Unsupported, binding)
            assertEquals(0, observation.counters[IntellijReadCounter.DECLARATION_AST_MAPPING_PSI_PROBES] ?: 0)
        } else {
            assertTrue(binding is IntellijClasslikeAstBinding.Supported)
            assertEquals(116, observation.counters[IntellijReadCounter.DECLARATION_AST_MAPPING_PSI_PROBES])
        }
        assertEquals(173_712, observation.counters[IntellijReadCounter.DECLARATION_AST_BINDING_BYTES_READ])
    }
}
