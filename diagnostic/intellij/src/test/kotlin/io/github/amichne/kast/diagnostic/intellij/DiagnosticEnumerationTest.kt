package io.github.amichne.kast.diagnostic.intellij

import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationFailure
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationStop
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
import io.github.amichne.kast.diagnostic.contract.DiagnosticSourceFile
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticEnumerationTest {
    private val lease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            EvidenceGeneration.parse(19).refined(),
        )
    private val query = DiagnosticScopeQuery.parse(lease, "src").refined()
    private val files =
        DiagnosticScope.fromCanonicalPaths(
                lease,
                (0 until 37).map {
                    Path.of("/workspace/src/File%02d.kt".format(it))
                },
            )
            .refined()
            .files

    @Test
    fun `small file allowance resumes regardless of native callback order`() {
        var request: DiagnosticEnumerationRequest = DiagnosticEnumerationRequest.First(query)
        repeat(30) { page ->
            val source = if (page % 2 == 0) files.reversed() else files
            when (val result = enumerate(request, source, fileLimit = 2)) {
                is DiagnosticEnumerationResult.Advancing -> {
                    assertTrue(result.files.isEmpty(), "canonical inventory is not established before exhaustion")
                    request = DiagnosticEnumerationRequest.Resume(result.cursor)
                }
                is DiagnosticEnumerationResult.Exhausted -> {
                    assertEquals(files, result.files)
                    assertEquals(18, page)
                    return
                }
                is DiagnosticEnumerationResult.Rejected -> error(result.failure.toString())
            }
        }
        error("bounded enumeration did not finish")
    }

    @Test
    fun `grant unable to cross replay prefix rejects without unchanged cursor`() {
        val first =
            assertInstanceOf(
                DiagnosticEnumerationResult.Advancing::class.java,
                enumerate(DiagnosticEnumerationRequest.First(query), files, work = 2, fileLimit = 2),
            )
        val result = enumerate(DiagnosticEnumerationRequest.Resume(first.cursor), files, work = 2, fileLimit = 2)
        assertEquals(
            DiagnosticEnumerationFailure.IncreaseGrant(DiagnosticEnumerationStop.WORK_LIMIT),
            assertInstanceOf(DiagnosticEnumerationResult.Rejected::class.java, result).failure,
        )
        val larger = enumerate(DiagnosticEnumerationRequest.Resume(first.cursor), files, work = 100, fileLimit = 100)
        assertEquals(files, assertInstanceOf(DiagnosticEnumerationResult.Exhausted::class.java, larger).files)
    }

    @Test
    fun `replaying cursor preserves inventory even when callback order changes`() {
        val first =
            assertInstanceOf(
                DiagnosticEnumerationResult.Advancing::class.java,
                enumerate(DiagnosticEnumerationRequest.First(query), files, fileLimit = 2),
            )
        val request = DiagnosticEnumerationRequest.Resume(first.cursor)
        val one = enumerate(request, files, fileLimit = 100)
        val two = enumerate(request, files.reversed(), fileLimit = 100)
        assertEquals(one, two)
    }

    @Test
    fun `discarded read attempt cannot add its files to retained cursor`() {
        val first =
            assertInstanceOf(
                DiagnosticEnumerationResult.Advancing::class.java,
                enumerate(DiagnosticEnumerationRequest.First(query), files, fileLimit = 2),
            )
        val request = DiagnosticEnumerationRequest.Resume(first.cursor)
        val abandoned = collector(request, work = 100, fileLimit = 100)
        files.take(8).forEach { file -> abandoned.accept { Refinement.Refined(file) } }
        // A retry creates a new collector from the original immutable cursor.
        val retry = enumerate(request, files.reversed(), fileLimit = 100)
        assertEquals(files, assertInstanceOf(DiagnosticEnumerationResult.Exhausted::class.java, retry).files)
    }

    @Test
    fun `time exhaustion before a callback rejects instead of publishing unchanged cursor`() {
        val result = enumerate(DiagnosticEnumerationRequest.First(query), files, elapsed = 1000)
        assertEquals(
            DiagnosticEnumerationFailure.IncreaseGrant(DiagnosticEnumerationStop.TIME_LIMIT),
            assertInstanceOf(DiagnosticEnumerationResult.Rejected::class.java, result).failure,
        )
    }

    @Test
    fun `retention capacity rejects before a cursor can be published`() {
        val collector = collector(DiagnosticEnumerationRequest.First(query), maximumBytes = 400)
        collector.accept { Refinement.Refined(files.first()) }
        assertEquals(
            DiagnosticEnumerationFailure.RetentionCapacity,
            assertInstanceOf(DiagnosticEnumerationResult.Rejected::class.java, collector.finish()).failure,
        )
    }

    @Test
    fun `work admission precedes file classification`() {
        val collector = collector(DiagnosticEnumerationRequest.First(query), work = 1)
        var classifications = 0
        files.take(2).forEach { file ->
            collector.accept {
                classifications++
                Refinement.Refined(file)
            }
        }
        assertEquals(1, classifications)
        assertInstanceOf(DiagnosticEnumerationResult.Advancing::class.java, collector.finish())
    }

    @Test
    fun `excluded nested owner does not fall back to an admitted ancestor or consume file capacity`() {
        val collector = collector(DiagnosticEnumerationRequest.First(query), work = 2, fileLimit = 2)
        val excluded = (0 until 100).map { Path.of("/workspace/src/foreign/Noise$it.kt") }
        val admission: (Path) -> Boolean = { path -> !path.startsWith(Path.of("/workspace/src/foreign")) }
        val candidates = excluded + files.take(2).map { Path.of(it.value) }
        for (candidate in candidates) {
            if (diagnosticIndexContains(query.path, candidate, admission)) {
                collector.accept {
                    Refinement.Refined(
                        DiagnosticScope.fromCanonicalPaths(lease, listOf(candidate)).refined().files.single()
                    )
                }
            }
        }
        assertEquals(
            files.take(2),
            assertInstanceOf(DiagnosticEnumerationResult.Exhausted::class.java, collector.finish()).files,
        )
    }

    private fun enumerate(
        request: DiagnosticEnumerationRequest,
        source: List<DiagnosticSourceFile>,
        work: Long = 100,
        fileLimit: Int = 2,
        elapsed: Long = 0,
    ): DiagnosticEnumerationResult {
        val collector = collector(request, work, fileLimit, elapsed)
        for (file in source) if (!collector.accept { Refinement.Refined(file) }) break
        return collector.finish()
    }

    private fun collector(
        request: DiagnosticEnumerationRequest,
        work: Long = 100,
        fileLimit: Int = 2,
        elapsed: Long = 0,
        maximumBytes: Long = 1_000_000,
    ): BoundedDiagnosticEnumeration =
        BoundedDiagnosticEnumeration.create(
                request,
                DiagnosticEnumerationAllowance(
                    ResourceBudget(
                        ResultLimit.parse(fileLimit).refined(),
                        WorkUnitLimit.parse(work).refined(),
                        ElapsedTimeLimitMillis.parse(1000).refined(),
                    )
                ) {
                    elapsed
                },
                maximumBytes,
            )
            .refined()

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
