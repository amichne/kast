package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BoundedSourceFileCollectorTest {
    @Test
    fun `source scope is deterministic duplicate free and excludes ignored observations`() {
        val inputs = listOf(source("B.kt"), ProjectSourceEntry.Ignored, source("A.kt"), source("B.kt"))
        for (order in listOf(inputs, inputs.reversed())) {
            val collector = collector()
            order.forEach { assertTrue(collector.accept(it, 0)) }
            assertEquals(
                Refinement.Refined(listOf(Path.of("/workspace/src/A.kt"), Path.of("/workspace/src/B.kt"))),
                collector.finish(),
            )
        }
    }

    @Test
    fun `file work and elapsed limits reject instead of publishing truncated scopes`() {
        val files = collector(files = 1)
        assertTrue(files.accept(source("A.kt"), 0))
        assertFalse(files.accept(source("B.kt"), 0))
        assertLimit(files)
        val work = collector(work = 1)
        assertTrue(work.accept(ProjectSourceEntry.Ignored, 0))
        assertFalse(work.accept(ProjectSourceEntry.Ignored, 0))
        assertLimit(work)
        val time = collector()
        assertFalse(time.accept(source("A.kt"), 2_000_000_000))
        assertLimit(time)
    }

    @Test
    fun `a path outside the requested subtree cannot enter the result`() {
        val collector = collector()
        assertFalse(collector.accept(ProjectSourceEntry.Source(Path.of("/workspace/other/A.kt")), 0))
        assertEquals(Refinement.Rejected(ProjectSourceFileFailure.INVALID_SCOPE), collector.finish())
        assertFalse(collector.accept(source("A.kt"), 0))
    }

    private fun assertLimit(collector: BoundedSourceFileCollector) {
        assertEquals(Refinement.Rejected(ProjectSourceFileFailure.LIMIT_EXCEEDED), collector.finish())
        assertFalse(collector.accept(source("C.kt"), 0))
    }

    private fun source(name: String) = ProjectSourceEntry.Source(Path.of("/workspace/src/$name"))

    private fun collector(files: Int = 3, work: Long = 10) =
        BoundedSourceFileCollector(
            Path.of("/workspace/src"),
            ResourceBudget(
                (ResultLimit.parse(files) as Refinement.Refined).value,
                (WorkUnitLimit.parse(work) as Refinement.Refined).value,
                (ElapsedTimeLimitMillis.parse(2_000) as Refinement.Refined).value,
            ),
        )
}
