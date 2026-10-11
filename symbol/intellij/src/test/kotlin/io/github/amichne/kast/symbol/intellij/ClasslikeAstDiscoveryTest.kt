package io.github.amichne.kast.symbol.intellij

import com.intellij.lang.LanguageParserDefinitions
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.outcome
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Path
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Real dispatcher, parser, admission and projection; only native indexes and clock are supplied. */
class ClasslikeAstDiscoveryTest {
    @Test
    fun `dense reference wrapper retains complete discovery without spending excluded names`(@TempDir home: Path) =
        withParser(home) { project ->
            val source = fixture(project, home.resolve("source"), denseSources(), setOf(CompilerSymbolKind.CLASSLIKE))
            val result = discover(project, source, "Target", work = 1)
            assertTrue(result.outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(listOf("Target"), result.outcome.batch().candidates.map { it.name.value })
            assertEquals(1L, result.outcome.batch().examinedWorkUnits.value)
            assertEquals(18_063, result.observation.counters[IntellijReadCounter.DECLARATION_PSI_NODES_VISITED])
        }

    @Test
    fun `recovered enum stays discoverable without any class introducer`(@TempDir home: Path) =
        withParser(home) { project ->
            val source =
                fixture(
                    project,
                    home.resolve("source"),
                    mapOf("Input.kt" to "enum Target { ENTRY }"),
                    setOf(CompilerSymbolKind.CLASSLIKE),
                )
            val result = discover(project, source, "Target", indexed = false)
            assertEquals(listOf("Target"), result.outcome.batch().candidates.map { it.name.value })
            assertTrue(result.outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(1L, result.outcome.batch().examinedWorkUnits.value)
        }

    @Test
    fun `unsupported parser and normal dispatcher preserve local body candidates on warm PSI`(@TempDir home: Path) =
        withParser(home) { project ->
            val source =
                fixture(
                    project,
                    home.resolve("source"),
                    mapOf(
                        "Input.kt" to
                            "class Target\nval property = run { class Target; 0 }\nfun outer() { class Target }"
                    ),
                    setOf(CompilerSymbolKind.CLASSLIKE),
                )
            val baseline = withUnsupportedParser { discover(project, source, "Target", indexed = false).outcome }
            val candidate = discover(project, source, "Target", indexed = false).outcome
            assertTrue(candidate is SymbolDiscoveryOutcome.Complete)
            assertEquals(3, candidate.batch().candidates.size)
            assertEquals(baseline.batch().candidates, candidate.batch().candidates)
            assertEquals(baseline.batch().examinedWorkUnits, candidate.batch().examinedWorkUnits)
        }

    private data class Result(val outcome: SymbolDiscoveryOutcome, val observation: RecordingDiscoveryObservation)

    private fun discover(
        project: Project,
        source: IntellijDeclarationDiscoveryTestFixture.Fixture,
        pattern: String,
        work: Long = 100,
        indexed: Boolean = true,
    ): Result =
        ApplicationManager.getApplication().runReadAction<Result> {
            val initial = source.request(100, work, 64_000, null)
            val request =
                SymbolDiscoveryRequest(
                    initial.scope,
                    SymbolDiscoveryTarget.Name(
                        SymbolNameDiscoveryKind.CLASS,
                        (SymbolDiscoveryPattern.parse(pattern) as Refinement.Refined).value,
                        SymbolDiscoveryMatch.EXACT_NAME,
                    ),
                    initial.budget,
                    initial.constraints,
                )
            val observation = RecordingDiscoveryObservation { 0L }
            var named = 0
            var files = 0
            val indexes =
                object : IntellijDeclarationIndexes {
                    override fun named(
                        index: IntellijNamedDeclarationIndex,
                        name: String,
                        scope: GlobalSearchScope,
                        accept: (NavigationItem) -> Boolean,
                    ): Boolean {
                        expect(named++ == 0 && index == IntellijNamedDeclarationIndex.CLASS && name == pattern)
                        expect(scope === source.scope.nativeScope)
                        return if (indexed) {
                            val declaration =
                                source.files.first().declarations.filterIsInstance<KtNamedDeclaration>().first {
                                    it.name == pattern
                                }
                            expect(accept(declaration))
                            true
                        } else true
                    }

                    override fun kotlinFiles(scope: GlobalSearchScope, accept: (VirtualFile) -> Boolean): Boolean {
                        expect(named == 1 && files++ == 0 && scope === source.scope.nativeScope)
                        return source.files.all { accept(it.virtualFile) }
                    }

                    override fun packageFiles(
                        packageName: String,
                        scope: GlobalSearchScope,
                        accept: (VirtualFile) -> Boolean,
                    ): Boolean = throw AssertionError("unexpected package index")
                }
            val result =
                IntellijNativeDiscoveryQuery(
                        environmentState = { IntellijDiscoveryEnvironmentState.READY },
                        cancellationCheck = {},
                        clock = IntellijReadNanoClock { 0L },
                        observation = observation,
                    )
                    .discoverNative(project, source.scope, request, indexes = indexes)
                    .outcome()
            expect(named == 1 && files == 1)
            Result(result, observation)
        }

    private fun <T> withUnsupportedParser(action: () -> T): T {
        val definition = object : KotlinParserDefinition() {}
        val original = requireNotNull(LanguageParserDefinitions.INSTANCE.forLanguage(KotlinLanguage.INSTANCE))
        LanguageParserDefinitions.INSTANCE.removeExplicitExtension(KotlinLanguage.INSTANCE, original)
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(KotlinLanguage.INSTANCE, definition)
        try {
            assertSame(definition, LanguageParserDefinitions.INSTANCE.forLanguage(KotlinLanguage.INSTANCE))
            return action()
        } finally {
            LanguageParserDefinitions.INSTANCE.removeExplicitExtension(KotlinLanguage.INSTANCE, definition)
            LanguageParserDefinitions.INSTANCE.addExplicitExtension(KotlinLanguage.INSTANCE, original)
        }
    }

    private fun denseSources() =
        linkedMapOf(
            "Target.kt" to "package fixture.reference.dense\nclass Target\n",
            "Uses.kt" to
                "package fixture.reference.denseusage\nimport fixture.reference.dense.Target\nclass Uses {\n" +
                    (0 until 1000).joinToString("\n") {
                        "    val reference${it.toString().padStart(4, '0')}: Target? = null"
                    } +
                    "\n}\n",
        )

    private fun expect(condition: Boolean) {
        if (!condition) throw AssertionError("unexpected or unconsumed native index effect")
    }
}
