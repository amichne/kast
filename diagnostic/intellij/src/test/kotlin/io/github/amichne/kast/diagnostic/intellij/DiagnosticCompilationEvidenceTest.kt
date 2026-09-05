package io.github.amichne.kast.diagnostic.intellij

import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilation
import io.github.amichne.kast.diagnostic.contract.DiagnosticCompilerRejection
import io.github.amichne.kast.diagnostic.contract.DiagnosticFact
import io.github.amichne.kast.diagnostic.contract.DiagnosticLimitationReason
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticSeverity
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DiagnosticCompilationEvidenceTest {
    @Test
    fun `complete success and complete compiler errors are distinct bounded evidence`() {
        val scope = scope()
        val collector = IntellijDiagnosticCollector(scope)
        collector.recordAnalyzed(scope.files.single())
        val success = collector.finish().observation() as IntellijDiagnosticCompilationEvidence.Complete
        assertEquals(0, success.errors.errorCount.value)
        val erroneous = IntellijDiagnosticCollector(scope)
        erroneous.accept(fact(scope, "UNRESOLVED_REFERENCE"))
        erroneous.recordAnalyzed(scope.files.single())
        val failure = erroneous.finish().observation() as IntellijDiagnosticCompilationEvidence.Complete
        assertEquals(1, failure.errors.errorCount.value)
        assertEquals(listOf("UNRESOLVED_REFERENCE"), failure.errors.factories.map { it.value })
        val rendered = failure.logFields().toString()
        assertFalse(rendered.contains("SensitiveName"))
        assertFalse(rendered.contains("private-token"))
        assertFalse(rendered.contains("/workspace"))
    }

    @Test
    fun `qualified rejected and cancelled remain explicit terminal observations`() {
        val scope = scope()
        val collector = IntellijDiagnosticCollector(scope)
        collector.recordLimitation(scope.files.single(), DiagnosticLimitationReason.INDEXING)
        val qualified = collector.finish().observation() as IntellijDiagnosticCompilationEvidence.Qualified
        assertEquals(setOf(DiagnosticLimitationReason.INDEXING), qualified.limitations)
        for (reason in DiagnosticCompilerRejection.entries) {
            assertEquals(IntellijDiagnosticCompilationEvidence.Rejected(reason), DiagnosticCompilation.Rejected(reason).observation())
        }
        assertTrue(IntellijDiagnosticCompilationEvidence.Cancelled.logFields().toString().contains("cancelled"))
    }

    @Test
    fun `factory evidence is capped and unsafe identities are withheld without losing counts`() {
        val scope = scope()
        val facts = (1..20).map { fact(scope, "FACTORY_$it") } + fact(scope, "private-token")
        val evidence = DiagnosticErrorEvidence.observe(facts)
        assertEquals(21, evidence.errorCount.value)
        assertEquals(16, evidence.factories.size)
        assertEquals(5, evidence.withheldFactCount.value)
        assertFalse(evidence.toString().contains("private-token"))
        assertEquals(evidence, DiagnosticErrorEvidence.observe(facts.reversed()))
    }

    private fun fact(scope: DiagnosticScope, code: String): DiagnosticFact =
        DiagnosticFact.fromBoundary(scope, scope.files.single(), 0, 1, DiagnosticSeverity.ERROR,
            code, "SensitiveName private-token compiler message").refined()

    private fun scope(): DiagnosticScope = DiagnosticScope.fromCanonicalPaths(
        SemanticReadLease(CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            EvidenceGeneration.parse(1).refined()), listOf(Path.of("/workspace/SensitiveName.kt")),
    ).refined()

    private fun <S, F> Refinement<S, F>.refined(): S = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
