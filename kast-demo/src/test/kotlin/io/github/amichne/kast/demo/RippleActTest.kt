package io.github.amichne.kast.demo

import com.varabyte.kotter.runtime.terminal.inmemory.resolveRerenders
import com.varabyte.kotterx.test.foundation.testSession
import com.varabyte.kotterx.test.runtime.stripFormatting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RippleActTest {

    private val sampleTree = CallerNode(
        symbolName = "WorkflowEngine.execute",
        module = ":core",
        children = listOf(
            CallerNode(
                symbolName = "Scheduler.scheduleNext()",
                module = ":orchestration",
                children = listOf(
                    CallerNode("PipelineCoordinator.start()", ":integration"),
                    CallerNode("RetryPolicy.attempt()", ":orchestration"),
                ),
            ),
            CallerNode(
                symbolName = "WorkflowResource.POST /workflows/run",
                module = ":api",
                children = listOf(
                    CallerNode("AuthMiddleware.withContext()", ":api"),
                ),
            ),
            CallerNode(
                symbolName = "PipelineRunner.executePipeline()",
                module = ":integration",
                children = listOf(
                    CallerNode("BatchProcessor.processBatch()", ":integration"),
                ),
            ),
        ),
    )

    @Test
    fun `ripple act renders header with act 3 info`() = testSession { terminal ->
        section {
            renderRippleAct(sampleTree, depth = 2)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "Act 3 of 3" in it }, "Should contain act number")
        assertTrue(lines.any { "Caller Graph" in it }, "Should contain title")
        assertTrue(lines.any { "depth 2" in it }, "Should show depth")
    }

    @Test
    fun `ripple act renders root node with module label`() = testSession { terminal ->
        section {
            renderRippleAct(sampleTree, depth = 2)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "WorkflowEngine.execute" in it && "[:core]" in it },
            "Should render root node with module label. Got: $lines")
    }

    @Test
    fun `ripple act renders tree branch characters`() = testSession { terminal ->
        section {
            renderRippleAct(sampleTree, depth = 2)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "├──" in it || "├── " in it },
            "Should have branch chars for non-last children. Got: $lines")
        assertTrue(lines.any { "└──" in it || "└── " in it },
            "Should have end-branch chars for last children. Got: $lines")
    }

    @Test
    fun `ripple act renders all depth-1 children`() = testSession { terminal ->
        section {
            renderRippleAct(sampleTree, depth = 2)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "Scheduler.scheduleNext()" in it },
            "Should render Scheduler child")
        assertTrue(lines.any { "WorkflowResource.POST /workflows/run" in it },
            "Should render WorkflowResource child")
        assertTrue(lines.any { "PipelineRunner.executePipeline()" in it },
            "Should render PipelineRunner child")
    }

    @Test
    fun `ripple act renders depth-2 children`() = testSession { terminal ->
        section {
            renderRippleAct(sampleTree, depth = 2)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "PipelineCoordinator.start()" in it },
            "Should render depth-2 child. Got: $lines")
        assertTrue(lines.any { "RetryPolicy.attempt()" in it },
            "Should render depth-2 child. Got: $lines")
        assertTrue(lines.any { "BatchProcessor.processBatch()" in it },
            "Should render depth-2 child. Got: $lines")
    }

    @Test
    fun `ripple act renders summary with module and symbol counts`() = testSession { terminal ->
        section {
            renderRippleAct(sampleTree, depth = 2)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        // 4 unique modules: :core, :orchestration, :api, :integration
        assertTrue(lines.any { "4 modules" in it },
            "Should show module count. Got: $lines")
        // 8 symbols reachable (including root)
        assertTrue(lines.any { "8 symbols" in it },
            "Should show symbol count. Got: $lines")
    }

    @Test
    fun `ripple act renders hint at bottom`() = testSession { terminal ->
        section {
            renderRippleAct(sampleTree, depth = 2)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "kast demo" in it && "--symbol" in it },
            "Should render hint at bottom. Got: $lines")
    }

    @Test
    fun `ripple act handles leaf node with no children`() = testSession { terminal ->
        val leaf = CallerNode("Foo.bar()", ":core")
        section {
            renderRippleAct(leaf, depth = 1)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "Foo.bar()" in it && "[:core]" in it },
            "Should render leaf node")
        // Only 1 module, 1 symbol
        assertTrue(lines.any { "1 module" in it },
            "Should show 1 module. Got: $lines")
        assertTrue(lines.any { "1 symbol" in it },
            "Should show 1 symbol. Got: $lines")
    }
}
