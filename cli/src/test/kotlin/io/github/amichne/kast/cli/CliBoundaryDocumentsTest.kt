package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliCommandFailure
import io.github.amichne.kast.cli.command.CliLifecycleCommand
import io.github.amichne.kast.cli.projection.CliBoundaryDocuments
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapFailure
import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliBoundaryDocumentsTest {
    @Test
    fun `generated lifecycle document preserves fields and canonical artifact order`(
        @TempDir temporary: Path,
    ) {
        val endpoint = endpoint(temporary)

        val document = CliBoundaryDocuments.lifecycleComplete(
            CliLifecycleCommand.STOP,
            endpoint,
            RuntimeLifecycleState.STOPPED,
            setOf(RuntimePersistentState, RuntimeEndpointMarker.SOCKET),
        )

        assertEquals(
            "{\"command\":\"stop\",\"status\":\"complete\",\"runtime\":\"stopped\"," +
                "\"root\":\"${endpoint.root.path}\",\"runtimeId\":\"${endpoint.runtimeId.value}\"," +
                "\"removed\":[\"socket\",\"state\"]}",
            document.value,
        )
    }

    @Test
    fun `generated boundary documents preserve distinct rejection schemas`() {
        assertEquals(
            "{\"status\":\"rejected\",\"boundary\":\"protocol\"," +
                "\"reason\":\"response-decoding-rejected\"}",
            CliBoundaryDocuments.boundaryRejected(
                CliBoundaryExitStatus.PROTOCOL,
                "response-decoding-rejected",
            ).value,
        )
        assertEquals(
            "{\"status\":\"rejected\",\"boundary\":\"usage\"," +
                "\"reason\":\"arguments-rejected\",\"diagnostic\":\"invalid option\"}",
            CliBoundaryDocuments.usageRejected(
                CliCommandFailure.ARGUMENTS_REJECTED,
                textDocument("invalid option"),
            ).value,
        )

    }

    @Test
    fun `runtime rejection preserves phase attempt and fixed corrective action in one JSON document`() {
        val attempt = (io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId.admit(
            "123e4567-e89b-42d3-a456-426614174000",
        ) as Refinement.Refined).value
        val rejected = io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState.Rejected(
            attempt,
            SemanticRuntimeBootstrapFailure.GRADLE_JVM_UNAVAILABLE,
            io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapPhase.GRADLE_JVM_SELECTION,
        )
        val raw = CliBoundaryDocuments.runtimeRejected(
            RuntimeAdmissionFailure.IntellijBootstrap(rejected),
        ).value
        val document = kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
        val bootstrap = document.getValue("bootstrap") as kotlinx.serialization.json.JsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive("selecting-gradle-jvm"), bootstrap["phase"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive(attempt.value), bootstrap["attemptId"])
        assertEquals(kotlinx.serialization.json.JsonPrimitive("Select a Gradle-compatible project JVM, then run kast start again."), bootstrap["correctiveAction"])
        assertEquals(1, raw.lines().size)
    }

    private fun textDocument(raw: String): CliTextDocument = when (
        val admission = CliTextDocument.admit(raw)
    ) {
        is CliTextDocumentAdmission.Admitted -> admission.document
        is CliTextDocumentAdmission.Rejected -> error(admission.failure)
    }

    private fun endpoint(temporary: Path): RuntimeEndpoint {
        val rootPath = Files.createDirectory(temporary.resolve("repo"))
        Files.writeString(rootPath.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val root = when (val discovery = FilesystemCanonicalRootDiscovery.discover(rootPath)) {
            is CanonicalRootDiscovery.Discovered -> discovery.root
            is CanonicalRootDiscovery.Rejected -> error(discovery.failure)
        }
        val runtimeId = SemanticRuntimeId.parse("sha256:${"a".repeat(64)}").refined()
        return when (
            val resolution = RuntimeEndpoint.at(root, runtimeId, temporary.resolve("runtime.sock"))
        ) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> error(resolution.failure)
        }
    }

    private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error(failure.toString())
    }
}
