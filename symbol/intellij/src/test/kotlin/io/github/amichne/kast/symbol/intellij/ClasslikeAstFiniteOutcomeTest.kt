package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.PsiDocumentManager
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.Controls
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.discover
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.withUnsupportedParser
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Path
import javax.swing.SwingUtilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ClasslikeAstFiniteOutcomeTest {
    @Test
    fun `finite work record and byte outcomes retain indexed evidence and baseline coverage`(@TempDir home: Path) =
        withParser(home) { project ->
            val source =
                fixture(
                    project,
                    home.resolve("source"),
                    mapOf("Input.kt" to "class Target\nfun outer() { class Target; val v = run { class Target; 0 } }"),
                    setOf(CompilerSymbolKind.CLASSLIKE),
                )
            for (controls in
                listOf(
                    Controls(work = 1),
                    Controls(records = 1),
                    Controls(bytes = 1, expectedFileCalls = 0),
                    Controls(namedComplete = false, expectedFileCalls = 0),
                )) {
                val baseline = withUnsupportedParser { discover(project, source, "Target", controls).outcome }
                val candidate = discover(project, source, "Target", controls).outcome
                assertTrue(candidate is SymbolDiscoveryOutcome.Qualified)
                assertEquals(baseline, candidate)
            }
        }

    @Test
    fun `deadline during AST scan retains indexed row and finite time qualification`(@TempDir home: Path) =
        withParser(home) { project ->
            val source =
                fixture(
                    project,
                    home.resolve("source"),
                    ClasslikeAstDispatcherFixture.denseSources(),
                    setOf(CompilerSymbolKind.CLASSLIKE),
                )
            var now = 0L
            val controls =
                Controls(
                    clock = IntellijReadNanoClock { now },
                    onCounter = { counter ->
                        if (counter == IntellijReadCounter.DECLARATION_AST_NODES_VISITED) now = 10_000_000_000L
                    },
                )
            val result = discover(project, source, "Target", controls)
            assertEquals(listOf("Target"), result.outcome.batch().candidates.map { it.name.value })
            assertTrue(result.outcome is SymbolDiscoveryOutcome.Qualified)
            assertEquals(
                setOf(SymbolDiscoveryQualification.TIME_LIMIT_REACHED),
                (result.outcome as SymbolDiscoveryOutcome.Qualified).qualifications.values.toSet(),
            )
            assertEquals(1L, result.outcome.batch().examinedWorkUnits.value)
        }

    @Test
    fun `environment stop during AST scan stays finite with retained indexed evidence`(@TempDir home: Path) =
        withParser(home) { project ->
            val source =
                fixture(
                    project,
                    home.resolve("source"),
                    mapOf("Input.kt" to "class Target"),
                    setOf(CompilerSymbolKind.CLASSLIKE),
                )
            var state = IntellijDiscoveryEnvironmentState.READY
            val result =
                discover(
                    project,
                    source,
                    "Target",
                    Controls(
                        environment = { state },
                        onCounter = {
                            if (it == IntellijReadCounter.DECLARATION_AST_NODES_VISITED)
                                state = IntellijDiscoveryEnvironmentState.DUMB
                        },
                    ),
                )
            assertTrue(result.outcome is SymbolDiscoveryOutcome.Qualified)
            assertEquals(listOf("Target"), result.outcome.batch().candidates.map { it.name.value })
            assertEquals(
                setOf(SymbolDiscoveryQualification.DUMB_MODE_TRANSITION),
                (result.outcome as SymbolDiscoveryOutcome.Qualified).qualifications.values.toSet(),
            )
        }

    @Test
    fun `cancelled read action restarts with current PSI and fresh admission state`(@TempDir home: Path) =
        withParser(home) { project ->
            val source =
                fixture(
                    project,
                    home.resolve("source"),
                    mapOf("Input.kt" to "class Target\nclass Old"),
                    setOf(CompilerSymbolKind.CLASSLIKE),
                )
            val cancellation = ProcessCanceledException()
            var interrupted = false
            val controls =
                Controls(
                    onCounter = {
                        if (it == IntellijReadCounter.DECLARATION_AST_NODES_VISITED) interrupted = true
                    },
                    cancellation = { if (interrupted) throw cancellation },
                )
            val caught =
                assertThrows(ProcessCanceledException::class.java) {
                    discover(project, source, "Target", controls)
                }
            assertSame(cancellation, caught)
            val documents = PsiDocumentManager.getInstance(project)
            SwingUtilities.invokeAndWait {
                ApplicationManager.getApplication().runWriteAction {
                    val document = requireNotNull(documents.getDocument(source.files.single()))
                    document.setText("fun outer() { class Target }\nclass New")
                    documents.commitAllDocuments()
                }
            }
            val restarted = discover(project, source, "Target", Controls(indexed = false))
            val baseline = withUnsupportedParser { discover(project, source, "Target", Controls(indexed = false)) }
            assertTrue(restarted.outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(1, restarted.outcome.batch().candidates.size)
            assertEquals(baseline.outcome, restarted.outcome)
            assertTrue((restarted.observation.counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED] ?: 0) > 0)
        }

    @Test
    fun `retained property family uses existing visitor despite CLASS target`(@TempDir home: Path) =
        withParser(home) { project ->
            val source =
                fixture(
                    project,
                    home.resolve("source"),
                    mapOf("Input.kt" to "val Target = 1\nfun outer() { val Target = 2; class Other }"),
                    setOf(CompilerSymbolKind.PROPERTY),
                )
            val result =
                discover(
                    project,
                    source,
                    "Target",
                    Controls(namedFamilies = listOf(IntellijNamedDeclarationIndex.PROPERTY)),
                )
            assertTrue(result.outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(2, result.outcome.batch().candidates.size)
            assertEquals(0, result.observation.counters[IntellijReadCounter.DECLARATION_AST_NODES_VISITED] ?: 0)
        }
}
