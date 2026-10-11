package io.github.amichne.kast.symbol.intellij

import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.Controls
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.denseSources
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.discover
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.withParserDefinition
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.withUnsupportedParser
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.lang.reflect.Modifier
import java.nio.file.Path
import java.security.SecureClassLoader
import org.jetbrains.kotlin.parsing.KotlinCommonParserDefinition
import org.jetbrains.kotlin.parsing.KotlinParserDefinition
import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.io.TempDir

/** Actual bundled IDE K2 parser, physical PSI and production qualifier/dispatcher; no IDE process is started. */
class ClasslikeAstK2ParserTest {
    @Test
    fun `audited final K2 variant inherits element parser and lexer factories`() {
        val parser = ClasslikeAstK2ParserFixture.type
        assertTrue(Modifier.isFinal(parser.modifiers))
        assertEquals(KotlinParserDefinition::class.java, parser.superclass)
        listOf(
                parser.getMethod("createElement", ASTNode::class.java),
                parser.getMethod("createParser", Project::class.java),
                parser.getMethod("createLexer", Project::class.java),
                parser.getMethod("getFileNodeType"),
            )
            .forEach { assertEquals(KotlinCommonParserDefinition::class.java, it.declaringClass) }
        assertEquals(parser, parser.getMethod("createFile", FileViewProvider::class.java).declaringClass)
    }

    @Test
    fun `actual K2 parser activates complete dense dispatcher without legacy traversal`(@TempDir home: Path) =
        withParser(home) { project ->
            withParserDefinition(ClasslikeAstK2ParserFixture.definition()) {
                val source =
                    fixture(project, home.resolve("source"), denseSources(), setOf(CompilerSymbolKind.CLASSLIKE))
                source.files.forEach {
                    assertSame(KtFile::class.java, it.javaClass)
                    assertTrue(it.isPhysical)
                }
                val before = ClasslikeAstPsiWork.census(source.files)
                val candidate = discover(project, source, "Target", Controls(work = 1))
                val after = ClasslikeAstPsiWork.census(source.files)
                assertTrue(candidate.outcome is SymbolDiscoveryOutcome.Complete)
                assertEquals(listOf("Target"), candidate.outcome.batch().candidates.map { it.name.value })
                assertEquals(18_063, candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED])
                assertEquals(2, candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_PSI_REQUESTS])
                assertEquals(
                    0,
                    candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_LEGACY_FALLBACKS] ?: 0,
                )
                assertEquals(
                    0,
                    candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_PARSER_FALLBACKS] ?: 0,
                )
                assertEquals(2, candidate.observation.calls[IntellijReadCall.DECLARATION_AST_BINDING])
                assertTrue(after.cachedCompositePsi - before.cachedCompositePsi <= 32)
                println(
                    "K2_PIPELINE_WORK nodes=${after.nodes} " +
                        "newCompositePsi=${after.cachedCompositePsi - before.cachedCompositePsi} " +
                        "counters=${candidate.observation.counters}"
                )
                val baseline = withUnsupportedParser { discover(project, source, "Target", Controls(work = 1)) }
                assertEquals(baseline.outcome, candidate.outcome)
            }
        }

    @Test
    fun `same named K2 class from an unknown loader retains typed parser fallback and old outcome`(
        @TempDir home: Path
    ) =
        withParser(home) { project ->
            val type = ClasslikeAstK2ParserFixture.type
            val bytes = requireNotNull(type.getResourceAsStream("${type.simpleName}.class")).use { it.readBytes() }
            val loader =
                object : SecureClassLoader(type.classLoader) {
                    fun define(): Class<*> =
                        defineClass(type.name, bytes, 0, bytes.size, type.protectionDomain.codeSource)
                }
            val shadow = loader.define().getConstructor().newInstance() as KotlinParserDefinition
            assertEquals(type.name, shadow.javaClass.name)
            assertSame(loader, shadow.javaClass.classLoader)
            val source =
                fixture(
                    project,
                    home.resolve("source"),
                    mapOf("Input.kt" to "class Target\nfun outer() { class Target }"),
                    setOf(CompilerSymbolKind.CLASSLIKE),
                )
            val owner = IntellijClasslikeAstBinding.Owner()
            assertTrue(
                owner.acquire({ true }, RecordingDiscoveryObservation { 0L }) is IntellijClasslikeAstBinding.Supported
            )
            val baseline = withUnsupportedParser { discover(project, source, "Target", Controls(indexed = false)) }
            withParserDefinition(shadow) {
                val observation = RecordingDiscoveryObservation { 0L }
                assertSame(IntellijClasslikeAstBinding.UnsupportedParser, owner.acquire({ true }, observation))
                assertTrue(observation.counters.isEmpty())
                repeat(2) {
                    val candidate = discover(project, source, "Target", Controls(indexed = false))
                    assertEquals(baseline.outcome, candidate.outcome)
                    assertEquals(
                        1,
                        candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_PARSER_FALLBACKS],
                    )
                    assertEquals(
                        1,
                        candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_LEGACY_FALLBACKS],
                    )
                    assertEquals(
                        0,
                        candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED] ?: 0,
                    )
                    assertEquals(1, candidate.observation.calls[IntellijReadCall.DECLARATION_AST_BINDING])
                }
            }
        }

    @Test
    fun `base metadata cannot bypass K2 source qualification and each variant memoizes separately`(
        @TempDir home: Path
    ) =
        withParser(home) {
            val owner = IntellijClasslikeAstBinding.Owner()
            val baseObservation = RecordingDiscoveryObservation { 0L }
            val base = owner.acquire({ true }, baseObservation)
            assertTrue(base is IntellijClasslikeAstBinding.Supported)
            assertEquals(169_937, baseObservation.counters[IntellijReadCounter.DECLARATION_AST_BINDING_BYTES_READ])
            withParserDefinition(ClasslikeAstK2ParserFixture.definition()) {
                val first = RecordingDiscoveryObservation { 0L }
                val k2 = owner.acquire({ true }, first)
                assertTrue(k2 is IntellijClasslikeAstBinding.Supported)
                assertEquals(173_712, first.counters[IntellijReadCounter.DECLARATION_AST_BINDING_BYTES_READ])
                assertEquals(116, first.counters[IntellijReadCounter.DECLARATION_AST_MAPPING_PSI_PROBES])
                val memo = RecordingDiscoveryObservation { 0L }
                assertSame(k2, owner.acquire({ true }, memo))
                assertEquals(0, memo.counters[IntellijReadCounter.DECLARATION_AST_BINDING_BYTES_READ] ?: 0)
            }
            assertSame(base, owner.acquire({ true }, RecordingDiscoveryObservation { 0L }))
        }

    @TestFactory
    fun `actual K2 cold recovered and lazy sources retain exhaustive legacy outcomes`(
        @TempDir home: Path
    ): List<DynamicTest> =
        listOf("enum recovery", "function body", "error body", "constructor and init").mapIndexed { index, label ->
            val case = ClasslikeAstDifferentialCases.values.single { it.label == label }
            DynamicTest.dynamicTest("K2 cold $label") {
                withParser(home.resolve("parser-$index")) { project ->
                    withParserDefinition(ClasslikeAstK2ParserFixture.definition()) {
                        case.names.filterNotNull().distinct().forEachIndexed { query, name ->
                            val source =
                                fixture(
                                    project,
                                    home.resolve("source-$index-$query"),
                                    mapOf("Input.kt" to case.source),
                                    setOf(CompilerSymbolKind.CLASSLIKE),
                                )
                            assertFalse(source.files.single().isContentsLoaded)
                            val candidate = discover(project, source, name, Controls(indexed = false))
                            assertTrue(candidate.outcome is SymbolDiscoveryOutcome.Complete)
                            assertEquals(
                                List(case.names.count { it == name }) { name },
                                candidate.outcome.batch().candidates.map { it.name.value },
                            )
                            assertTrue(
                                (candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED]
                                    ?: 0) > 0
                            )
                            val baseline = withUnsupportedParser {
                                discover(project, source, name, Controls(indexed = false))
                            }
                            assertEquals(baseline.outcome, candidate.outcome)
                            assertEquals(
                                baseline.observation.counters[IntellijReadCounter.DECLARATION_PSI_NODES_VISITED],
                                candidate.observation.counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED],
                            )
                        }
                    }
                }
            }
        }
}

/** Reflective use avoids Kotlin-internal API suppression; this constructs the actual audited SDK class. */
internal object ClasslikeAstK2ParserFixture {
    val type: Class<*> =
        Class.forName(
            "org.jetbrains.kotlin.idea.base.analysisApiPlatform.IDEKotlinK2KotlinParserDefinition",
            false,
            KotlinParserDefinition::class.java.classLoader,
        )

    fun definition(): KotlinParserDefinition = type.getConstructor().newInstance() as KotlinParserDefinition

    fun definition(variant: KotlinAstParserVariant): KotlinParserDefinition =
        when (variant) {
            KotlinAstParserVariant.BASE -> KotlinParserDefinition()
            KotlinAstParserVariant.IDE_K2 -> definition()
        }
}
