package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.psi.impl.PsiManagerEx
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryOutcome
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryQualification
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.Controls
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.discover
import io.github.amichne.kast.symbol.intellij.ClasslikeAstDispatcherFixture.withUnsupportedParser
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.batch
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.fixture
import io.github.amichne.kast.symbol.intellij.IntellijDeclarationDiscoveryTestFixture.withParser
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
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
            for ((controls, expected) in
                listOf(
                    Controls(work = 1) to SymbolDiscoveryQualification.WORK_LIMIT_REACHED,
                    Controls(records = 1) to SymbolDiscoveryQualification.RESULT_LIMIT_REACHED,
                    Controls(bytes = 1, expectedFileCalls = 0) to SymbolDiscoveryQualification.BYTE_LIMIT_REACHED,
                    Controls(namedComplete = false, expectedFileCalls = 0) to
                        SymbolDiscoveryQualification.PROVIDER_FAILURE,
                )) {
                val baseline = withUnsupportedParser { discover(project, source, "Target", controls).outcome }
                val candidate = discover(project, source, "Target", controls).outcome
                assertTrue(candidate is SymbolDiscoveryOutcome.Qualified)
                assertEquals(setOf(expected), (candidate as SymbolDiscoveryOutcome.Qualified).qualifications.values)
                assertEquals(
                    if (controls.bytes == 1L) emptyList() else listOf("Target"),
                    candidate.batch.candidates.map { it.name.value },
                )
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
                        if (counter == ClasslikeAstSdkExpectation.selected.traversalCounter) now = 10_000_000_000L
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
                            if (it == ClasslikeAstSdkExpectation.selected.traversalCounter)
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
    fun `cancelled read action restarts with reacquired changed PSI and fresh admission state`(@TempDir home: Path) =
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
                        if (it == ClasslikeAstSdkExpectation.selected.traversalCounter) interrupted = true
                    },
                    cancellation = { if (interrupted) throw cancellation },
                )
            val caught =
                assertThrows(ProcessCanceledException::class.java) {
                    discover(project, source, "Target", controls)
                }
            assertSame(cancellation, caught)
            Files.writeString(home.resolve("source/Input.kt"), "fun outer() { class Target }\nclass New")
            ApplicationManager.getApplication().runWriteAction {
                // The compiler VFS is read-only and has no live editor/reload synchronization.
                // Reacquire the changed file through a fresh real PSI view for the next read.
                PsiManagerEx.getInstanceEx(project).fileManager.setViewProvider(source.files.single().virtualFile, null)
            }
            val current =
                fixture(
                    project,
                    home.resolve("source"),
                    mapOf("Input.kt" to "fun outer() { class Target }\nclass New"),
                    setOf(CompilerSymbolKind.CLASSLIKE),
                )
            assertEquals("fun outer() { class Target }\nclass New", current.files.single().text)
            assertEquals(source.scope.lease, current.scope.lease)
            val restarted = discover(project, current, "Target", Controls(indexed = false))
            val baseline = withUnsupportedParser { discover(project, current, "Target", Controls(indexed = false)) }
            assertTrue(restarted.outcome is SymbolDiscoveryOutcome.Complete)
            assertEquals(1, restarted.outcome.batch().candidates.size)
            val candidate = restarted.outcome.batch().candidates.single()
            assertEquals(source.scope.lease, candidate.lease)
            assertEquals("Target", candidate.name.value)
            assertEquals(SymbolDiscoveryKind.CLASS, candidate.kind)
            assertEquals(home.resolve("source/Input.kt").toRealPath().toString(), candidate.location.file.stableValue)
            assertEquals(
                14,
                assertInstanceOf<SymbolDiscoveryCandidateLocation.Declaration>(
                        restarted.outcome.batch().candidates.single().location
                    )
                    .offset
                    .value,
            )
            assertEquals(baseline.outcome, restarted.outcome)
            assertTrue((restarted.observation.counters[ClasslikeAstSdkExpectation.selected.traversalCounter] ?: 0) > 0)
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
