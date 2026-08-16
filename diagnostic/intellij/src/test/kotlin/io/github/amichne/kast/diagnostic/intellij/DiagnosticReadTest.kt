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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Path

class DiagnosticReadTest {
    @Test
    fun `adapter admission rejects cross-generation diagnostics`() {
        val admission = admitDiagnosticLease(lease(19L), lease(20L))

        val rejected = assertInstanceOf(IntellijDiagnosticLeaseAdmission.Rejected::class.java, admission)
        assertEquals(DiagnosticCompilerRejection.GENERATION_MOVED, rejected.reason)
    }

    @Test
    fun `collector completes only after every exact scope file is analyzed`() {
        val scope = scope("Subject.kt", "Related.kt")
        val collector = IntellijDiagnosticCollector(scope)
        val fact = DiagnosticFact.fromBoundary(
            scope,
            scope.files.first(),
            4,
            11,
            DiagnosticSeverity.ERROR,
            "UNRESOLVED_REFERENCE",
            "Unresolved reference",
        ).refined()

        assertEquals(IntellijDiagnosticCollectionAdmission.ACCEPTED, collector.accept(fact))
        scope.files.forEach { file ->
            assertEquals(
                IntellijDiagnosticCollectionAdmission.ACCEPTED,
                collector.recordAnalyzed(file),
            )
        }

        val complete = assertInstanceOf(DiagnosticCompilation.Complete::class.java, collector.finish())
        assertEquals(scope.files, complete.coverage.analyzedFiles)
        assertEquals(scope.lease.generation, complete.batch.facts.single().generation)
    }

    @Test
    fun `unavailable exact file remains qualified and never proves absence`() {
        val scope = scope("Subject.kt", "Related.kt")
        val collector = IntellijDiagnosticCollector(scope)

        collector.recordAnalyzed(scope.files.first())
        collector.recordLimitation(scope.files.last(), DiagnosticLimitationReason.INDEXING)

        val qualified = assertInstanceOf(DiagnosticCompilation.Qualified::class.java, collector.finish())
        assertEquals(emptyList<DiagnosticFact>(), qualified.batch.facts)
        assertEquals(setOf(scope.files.last()), qualified.coverage.limitations.map { it.file }.toSet())
    }

    @Test
    fun `unaccounted file is a contract rejection instead of complete evidence`() {
        val result = IntellijDiagnosticCollector(scope("Subject.kt")).finish()

        val rejected = assertInstanceOf(DiagnosticCompilation.Rejected::class.java, result)
        assertEquals(DiagnosticCompilerRejection.COMPILER_CONTRACT_VIOLATION, rejected.reason)
    }

    @Test
    fun `native adapter surface exposes no refresh transition publication or write operation`() {
        val forbidden = Regex("refresh|import|publish|transition|write", RegexOption.IGNORE_CASE)

        assertFalse(
            IntellijDiagnosticCompilerAdapter::class.java.declaredMethods.any { method ->
                forbidden.containsMatchIn(method.name)
            },
        )
    }

    private fun scope(vararg files: String): DiagnosticScope = DiagnosticScope.fromCanonicalPaths(
        lease(19L),
        files.map { name -> Path.of("/workspace/src/$name") },
    ).refined()

    private fun lease(generation: Long): SemanticReadLease = SemanticReadLease(
        CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
        EvidenceGeneration.parse(generation).refined(),
    )

    private fun <Strong, Failure> Refinement<Strong, Failure>.refined(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
