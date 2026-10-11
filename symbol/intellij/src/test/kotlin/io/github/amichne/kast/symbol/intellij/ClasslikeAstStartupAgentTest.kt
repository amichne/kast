package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.project.Project
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
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.lang.instrument.Instrumentation
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A no-op startup agent in an owned child proves conservative fallback, without installing or attaching an agent. */
class ClasslikeAstStartupAgentTest {
    @Test
    fun `observed startup agent retains old visitor outcome and explicit fallback on repeated reads`(
        @TempDir home: Path
    ) = child(home, agent = true)

    @Test fun `ordinary SDK child retains qualified AST traversal`(@TempDir home: Path) = child(home, agent = false)

    private fun child(home: Path, agent: Boolean) {
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        val command = mutableListOf(java.toString())
        if (agent) command += "-javaagent:${agentArchive(home)}"
        command +=
            listOf(
                "-Djava.awt.headless=true",
                "-cp",
                System.getProperty("java.class.path"),
                ClasslikeAstStartupAgentWitness::class.java.name,
                home.resolve("parser").toString(),
                agent.toString(),
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

    private fun agentArchive(home: Path): Path {
        val owner = ClasslikeAstNoOpAgent::class.java
        val resource = owner.name.replace('.', '/') + ".class"
        val manifest = Manifest()
        manifest.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        manifest.mainAttributes[Attributes.Name("Premain-Class")] = owner.name
        val archive = home.resolve("owned-agent.jar")
        JarOutputStream(Files.newOutputStream(archive), manifest).use { jar ->
            jar.putNextEntry(JarEntry(resource))
            requireNotNull(owner.getResourceAsStream("/${resource}")).use { it.copyTo(jar) }
            jar.closeEntry()
        }
        return archive
    }
}

/** JVM-supported premain entry point. Presence alone must select the conservative path. */
object ClasslikeAstNoOpAgent {
    @JvmStatic
    fun premain(arguments: String?, instrumentation: Instrumentation?) {
        require(arguments.isNullOrEmpty()) { "owned agent expects no options" }
        requireNotNull(instrumentation) { "JVM must supply its instrumentation handle" }
    }
}

/** Actual production binding and dispatcher in a case-owned JVM; no fake binding or runtime flag. */
object ClasslikeAstStartupAgentWitness {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val home = Path.of(arguments[0])
        val agent = arguments[1].toBooleanStrict()
        withParser(home) { project ->
            for (variant in KotlinAstParserVariant.entries) {
                withParserDefinition(ClasslikeAstK2ParserFixture.definition(variant)) {
                    verify(project, home.resolve(variant.name), agent)
                }
            }
        }
    }

    private fun verify(project: Project, home: Path, agent: Boolean) {
        val binding = IntellijClasslikeAstBinding.Owner().acquire({ true }, IntellijReadObservation.None)
        if (agent) assertSame(IntellijClasslikeAstBinding.StartupAgent, binding)
        else assertTrue(binding is IntellijClasslikeAstBinding.Supported)
        val source =
            fixture(
                project,
                home.resolve("source"),
                mapOf("Input.kt" to "class Target\nfun outer() { class Target }"),
                setOf(CompilerSymbolKind.CLASSLIKE),
            )
        repeat(2) {
            val candidate = discover(project, source, "Target", Controls(indexed = false))
            assertTrue(candidate.outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(
                listOf("Target", "Target"),
                candidate.outcome.batch().candidates.map { it.name.value },
            )
            val baseline = withUnsupportedParser {
                discover(project, source, "Target", Controls(indexed = false))
            }
            assertEquals(baseline.outcome, candidate.outcome)
            val counters = candidate.observation.counters
            assertEquals(
                if (agent) 1 else 0,
                counters[IntellijReadCounter.DECLARATION_AST_STARTUP_AGENT_FALLBACKS] ?: 0,
            )
            assertEquals(0, counters[IntellijReadCounter.DECLARATION_AST_PARSER_FALLBACKS] ?: 0)
            if (agent) {
                assertEquals(1, counters[IntellijReadCounter.DECLARATION_AST_LEGACY_FALLBACKS])
                assertEquals(0, counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED] ?: 0)
                assertEquals(0, counters[IntellijReadCounter.DECLARATION_AST_BINDING_BYTES_READ] ?: 0)
                assertEquals(0, counters[IntellijReadCounter.DECLARATION_AST_MAPPING_PSI_PROBES] ?: 0)
                assertTrue((counters[IntellijReadCounter.DECLARATION_PSI_NODES_VISITED] ?: 0) > 0)
            } else assertTrue((counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED] ?: 0) > 0)
        }
    }
}
