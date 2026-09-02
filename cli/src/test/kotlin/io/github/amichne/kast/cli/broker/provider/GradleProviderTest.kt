package io.github.amichne.kast.cli.broker.provider

import io.github.amichne.kast.cli.broker.core.Broker
import io.github.amichne.kast.cli.broker.core.BrokerDispatch
import io.github.amichne.kast.cli.broker.core.BrokerDispatchRequest
import io.github.amichne.kast.cli.broker.core.BrokerFailure
import io.github.amichne.kast.cli.broker.core.BrokerInvocationContext
import io.github.amichne.kast.cli.broker.core.BrokerLimits
import io.github.amichne.kast.cli.broker.core.ProviderFailureCode
import io.github.amichne.kast.cli.broker.core.ProviderNamespace
import io.github.amichne.kast.cli.broker.core.ToolAddress
import io.github.amichne.kast.cli.broker.core.ToolName
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.Validation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class GradleProviderTest {
    @Test
    fun `Gradle tools bind only the admitted wrapper arguments`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        executable(workspace.resolve("gradlew"))
        val executor = RecordingProcessExecutor()
        val broker = Broker.create(
            listOf(GradleProvider.registration(executor).validatedValue()),
            BrokerLimits.defaults(),
        ).validatedValue()

        val dispatch = broker.dispatch(
            BrokerDispatchRequest(
                ToolAddress(namespace("gradle"), toolName("dependencies")),
                buildJsonObject {
                    put("configuration", "runtimeClasspath")
                    put("project", ":cli")
                },
                context(workspace),
            ),
        )

        assertEquals(
            listOf(
                "--console=plain",
                "--no-daemon",
                ":cli:dependencies",
                "--configuration",
                "runtimeClasspath",
            ),
            executor.requests.single().arguments,
        )
        assertEquals(
            "{\"exitCode\":0,\"stderr\":\"\",\"stdout\":\"ok\"}",
            (dispatch as BrokerDispatch.Completed).presentation.content.single().text,
        )
    }

    @Test
    fun `Gradle wrapper absence is finite provider failure`(
        @TempDir temporary: Path,
    ) = runBlocking {
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        val broker = Broker.create(
            listOf(GradleProvider.registration(RecordingProcessExecutor()).validatedValue()),
            BrokerLimits.defaults(),
        ).validatedValue()

        val dispatch = broker.dispatch(
            BrokerDispatchRequest(
                ToolAddress(namespace("gradle"), toolName("inspect")),
                buildJsonObject {},
                context(workspace),
            ),
        ) as BrokerDispatch.Rejected

        assertEquals(
            ProviderFailureCode.GRADLE_WRAPPER_UNAVAILABLE,
            (dispatch.failure as BrokerFailure.ProviderInvocationRejected).code,
        )
    }

    private class RecordingProcessExecutor : BrokerProcessExecutor {
        val requests = mutableListOf<BrokerProcessRequest>()

        override suspend fun execute(request: BrokerProcessRequest): BrokerProcessExecution {
            requests += request
            return BrokerProcessExecution.Completed(0, "ok", "")
        }
    }

    private fun executable(path: Path) {
        Files.writeString(path, "#!/bin/sh\nexit 0\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    }

    private fun context(cwd: Path): BrokerInvocationContext = BrokerInvocationContext.admit(
        "thread-1",
        "turn-1",
        "call-1",
        cwd,
    ).refinedValue()

    private fun namespace(value: String): ProviderNamespace =
        ProviderNamespace.admit(value).refinedValue()

    private fun toolName(value: String): ToolName = ToolName.admit(value).refinedValue()

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> throw AssertionError("Expected refinement, received $failure")
    }

    private fun <Strong, Failure> Validation<Strong, Failure>.validatedValue(): Strong = when (this) {
        is Validation.Validated -> value
        is Validation.Rejected -> throw AssertionError("Expected validation, received $failures")
    }
}
