package io.github.amichne.kast.demo

import com.varabyte.kotter.runtime.terminal.inmemory.resolveRerenders
import com.varabyte.kotterx.test.foundation.testSession
import com.varabyte.kotterx.test.runtime.stripFormatting
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResolutionActTest {

    private val sampleResult = ResolutionResult(
        fqn = "WorkflowEngine.execute",
        declarationFile = "core/src/main/kotlin/WorkflowEngine.kt",
        declarationLine = 42,
        typeSignature = "suspend (context: ExecutionContext) → Result<Unit>",
        refs = listOf(
            ResolvedReference("orchestration/Scheduler.kt", 87, ReferenceKind.CALL, "WorkflowEngine", ":orchestrate"),
            ResolvedReference("orchestration/Scheduler.kt", 143, ReferenceKind.CALL, "WorkflowEngine", ":orchestrate"),
            ResolvedReference("api/WorkflowResource.kt", 31, ReferenceKind.CALL, "WorkflowEngine", ":api"),
            ResolvedReference("test/WorkflowEngineTest.kt", 19, ReferenceKind.CALL, "WorkflowEngine", ":core"),
            ResolvedReference("test/WorkflowEngineTest.kt", 67, ReferenceKind.CALL, "WorkflowEngine", ":core"),
            ResolvedReference("integration/PipelineRunner.kt", 204, ReferenceKind.CALL, "WorkflowEngine", ":integration"),
        ),
        totalGrepHits = 38,
    )

    @Test
    fun `resolution act renders header with act 2 info`() = testSession { terminal ->
        section {
            renderResolutionAct(sampleResult)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "Act 2 of 3" in it }, "Should contain act number")
        assertTrue(lines.any { "Symbol Resolution" in it }, "Should contain title")
    }

    @Test
    fun `resolution act renders declaration info`() = testSession { terminal ->
        section {
            renderResolutionAct(sampleResult)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "Declared in" in it && "WorkflowEngine.kt" in it && "42" in it },
            "Should show declaration file and line. Got: $lines")
        assertTrue(lines.any { "Type" in it && "suspend" in it },
            "Should show type signature. Got: $lines")
    }

    @Test
    fun `resolution act renders reference table with all rows`() = testSession { terminal ->
        section {
            renderResolutionAct(sampleResult)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        // Header
        assertTrue(lines.any { "File" in it && "Line" in it && "Kind" in it },
            "Should have table header row. Got: $lines")
        // Data rows
        assertTrue(lines.any { "Scheduler.kt" in it && "87" in it },
            "Should have Scheduler.kt:87 row")
        assertTrue(lines.any { "WorkflowResource.kt" in it && "31" in it },
            "Should have WorkflowResource.kt:31 row")
        assertTrue(lines.any { "PipelineRunner.kt" in it && "204" in it },
            "Should have PipelineRunner.kt:204 row")
    }

    @Test
    fun `resolution act renders delta summary with noise reduction`() = testSession { terminal ->
        section {
            renderResolutionAct(sampleResult)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "38" in it && "text matches" in it },
            "Should show total grep hits. Got: $lines")
        assertTrue(lines.any { "6" in it && "actual references" in it },
            "Should show actual reference count. Got: $lines")
        assertTrue(lines.any { "84%" in it },
            "Should show noise eliminated percentage. Got: $lines")
    }

    @Test
    fun `resolution act shows ripple hint when enabled`() = testSession { terminal ->
        section {
            renderResolutionAct(sampleResult, rippleEnabled = true)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.any { "Enter" in it && "caller graph" in it },
            "Should show ripple hint when enabled. Got: $lines")
    }

    @Test
    fun `resolution act hides ripple hint when disabled`() = testSession { terminal ->
        section {
            renderResolutionAct(sampleResult, rippleEnabled = false)
        }.run()

        val lines = terminal.resolveRerenders().stripFormatting()
        assertTrue(lines.none { "Enter" in it && "caller graph" in it },
            "Should not show ripple hint when disabled. Got: $lines")
    }
}
