package io.github.amichne.kast.diagnostic.intellij

import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationRequest
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationResult
import io.github.amichne.kast.diagnostic.contract.DiagnosticEnumerationStop
import io.github.amichne.kast.diagnostic.contract.DiagnosticScope
import io.github.amichne.kast.diagnostic.contract.DiagnosticScopeQuery
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class DiagnosticEnumerationTest {
    private val lease =
        SemanticReadLease(
            CanonicalWorkspaceRoot.fromCanonicalPath(Path.of("/workspace")).refined(),
            EvidenceGeneration.parse(19).refined(),
        )
    private val query = DiagnosticScopeQuery.parse(lease, "src").refined()

    @Test
    fun `wide directory resumes with one probe per page in canonical order`() {
        val tree = tree(37)
        var request: DiagnosticEnumerationRequest = DiagnosticEnumerationRequest.First(query)
        val files = mutableListOf<String>()
        var pages = 0
        while (true) {
            val before = tree.probes
            val result = enumerateDiagnosticTree(request, tree, allowance(work = 1))
            assertTrue(tree.probes - before <= 1)
            files += result.files.map { it.value }
            pages += 1
            assertTrue(pages < 2000, "continuation must advance inside sibling scanning")
            when (result) {
                is DiagnosticEnumerationResult.Advancing -> request = DiagnosticEnumerationRequest.Resume(result.cursor)
                is DiagnosticEnumerationResult.Exhausted -> break
                is DiagnosticEnumerationResult.Rejected -> fail("unexpected rejection: ${result.reason}")
            }
        }
        assertEquals((0 until 37).map { "/workspace/src/File%02d.kt".format(it) }, files)
        val reference =
            enumerateDiagnosticTree(DiagnosticEnumerationRequest.First(query), tree(37), allowance(work = 10000))
        assertInstanceOf(DiagnosticEnumerationResult.Exhausted::class.java, reference)
        assertEquals(reference.files.map { it.value }, files)
    }

    @Test
    fun `empty intermediate page never exhausts directory`() {
        val result = enumerateDiagnosticTree(DiagnosticEnumerationRequest.First(query), tree(3), allowance(work = 1))
        assertInstanceOf(DiagnosticEnumerationResult.Advancing::class.java, result)
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `replaying immutable cursor yields identical file page`() {
        val first = enumerateDiagnosticTree(DiagnosticEnumerationRequest.First(query), tree(3), allowance(work = 2))
        val cursor = assertInstanceOf(DiagnosticEnumerationResult.Advancing::class.java, first).cursor
        val one = enumerateDiagnosticTree(DiagnosticEnumerationRequest.Resume(cursor), tree(3), allowance(work = 100))
        val two = enumerateDiagnosticTree(DiagnosticEnumerationRequest.Resume(cursor), tree(3), allowance(work = 100))
        assertEquals(one.files, two.files)
    }

    @Test
    fun `excluded directories are not expanded and cannot consume file capacity`() {
        val tree = tree(2, ignoredDirectories = 100)
        val result =
            enumerateDiagnosticTree(DiagnosticEnumerationRequest.First(query), tree, allowance(work = 10000, files = 2))
        assertEquals(2, result.files.size)
        assertEquals(setOf(query.path), tree.directories)
        assertInstanceOf(DiagnosticEnumerationResult.Advancing::class.java, result)
    }

    @Test
    fun `cancelled attempt cannot add files to retained position`() {
        val first = enumerateDiagnosticTree(DiagnosticEnumerationRequest.First(query), tree(3), allowance(work = 2))
        val request =
            DiagnosticEnumerationRequest.Resume(
                assertInstanceOf(DiagnosticEnumerationResult.Advancing::class.java, first).cursor
            )
        val interrupted = tree(3)
        interrupted.cancelAfter = 6
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            enumerateDiagnosticTree(request, interrupted, allowance(work = 100))
        }
        val result = enumerateDiagnosticTree(request, tree(3), allowance(work = 100))
        assertEquals(3, result.files.size)
        assertEquals(3, result.files.distinct().size)
    }

    @Test
    fun `time exhaustion performs no probe and preserves resumable root`() {
        val tree = tree(2)
        val result =
            enumerateDiagnosticTree(
                DiagnosticEnumerationRequest.First(query),
                tree,
                allowance(work = 100, elapsed = 1000),
            )
        assertEquals(
            DiagnosticEnumerationStop.TIME_LIMIT,
            assertInstanceOf(DiagnosticEnumerationResult.Advancing::class.java, result).reason,
        )
        assertEquals(0, tree.probes)
    }

    private fun tree(count: Int, ignoredDirectories: Int = 0): Tree =
        Tree(
            (0 until count).reversed().map { index ->
                DiagnosticTreeEntry.File(
                    DiagnosticScope.fromCanonicalPaths(
                            lease,
                            listOf(Path.of("/workspace/src/File%02d.kt".format(index))),
                        )
                        .refined()
                        .files
                        .single()
                )
            } + List(ignoredDirectories) { DiagnosticTreeEntry.Ignored }
        )

    private inner class Tree(private val entries: List<DiagnosticTreeEntry>) : DiagnosticTreeProbe {
        var probes = 0
        var cancelAfter = Int.MAX_VALUE
        val directories = mutableSetOf<Path>()

        override fun root(): DiagnosticTreeEntry {
            probes++
            return DiagnosticTreeEntry.Directory(query.path)
        }

        override fun child(directory: Path, ordinal: Int): DiagnosticTreeEntry {
            directories.add(directory)
            if (++probes >= cancelAfter) throw java.util.concurrent.CancellationException()
            return entries.getOrElse(ordinal) { DiagnosticTreeEntry.End }
        }
    }

    private fun allowance(work: Long, files: Int = 100, elapsed: Long = 0) =
        DiagnosticEnumerationAllowance(
            ResourceBudget(
                ResultLimit.parse(files).refined(),
                WorkUnitLimit.parse(work).refined(),
                ElapsedTimeLimitMillis.parse(1000).refined(),
            )
        ) {
            elapsed
        }

    private fun <T, F> Refinement<T, F>.refined(): T =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error(failure.toString())
        }
}
