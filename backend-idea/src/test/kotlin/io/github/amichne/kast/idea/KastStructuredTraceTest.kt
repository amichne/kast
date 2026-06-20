package io.github.amichne.kast.idea

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class KastStructuredTraceTest {
    @Test
    fun `trace json includes cross-cutting workspace and invocation fields`() {
        val workspaceRoot = Files.createTempDirectory("kast-trace-workspace")
        val targetFile = Files.createDirectories(workspaceRoot.resolve("src/main/kotlin"))
            .resolve("Example.kt")
            .also { Files.writeString(it, "class Example\n") }

        val json = KastStructuredTrace.traceJson(
            eventName = "idea.apply_edits.text_edit_started",
            workspaceRoot = workspaceRoot,
            ideaProjectName = "kast-test",
            ideaProjectBasePath = workspaceRoot.toString(),
            fields = KastStructuredTraceFields(
                invocationId = "invocation-1",
                parentInvocationId = "parent-1",
                agentRole = "idea-edit-applier",
                agentInstanceId = "agent-1",
                reviewInvocationId = "review-1",
                sdkRegistrationScope = "project",
                targetFilePath = targetFile.toString(),
                moduleName = "backend-idea",
                gradleProjectPath = ":backend-idea",
            ),
            outcome = "started",
            detail = mapOf("editCount" to 1),
            processId = 42,
            threadName = "test-thread",
        )

        assertTrue(json.contains("\"type\":\"kast.idea.trace\""), json)
        assertTrue(json.contains("\"schemaVersion\":1"), json)
        assertTrue(json.contains("\"eventName\":\"idea.apply_edits.text_edit_started\""), json)
        assertTrue(json.contains("\"invocationId\":\"invocation-1\""), json)
        assertTrue(json.contains("\"parentInvocationId\":\"parent-1\""), json)
        assertTrue(json.contains("\"agentRole\":\"idea-edit-applier\""), json)
        assertTrue(json.contains("\"agentInstanceId\":\"agent-1\""), json)
        assertTrue(json.contains("\"reviewInvocationId\":\"review-1\""), json)
        assertTrue(json.contains("\"sdkRegistrationScope\":\"project\""), json)
        assertTrue(json.contains("\"ideaProjectName\":\"kast-test\""), json)
        assertTrue(json.contains("\"processId\":42"), json)
        assertTrue(json.contains("\"threadName\":\"test-thread\""), json)
        assertTrue(json.contains("\"moduleName\":\"backend-idea\""), json)
        assertTrue(json.contains("\"gradleProjectPath\":\":backend-idea\""), json)
        assertTrue(json.contains("\"detail\":{\"editCount\":1}"), json)
        assertTrue(json.contains(targetFile.toRealPath().toString()), json)
    }
}
