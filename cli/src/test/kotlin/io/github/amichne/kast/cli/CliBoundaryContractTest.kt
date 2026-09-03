package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.command.CliLifecycleCommand
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

class CliBoundaryContractTest {
    @Test
    fun `exactly eleven public command projections parse to canonical operations`() {
        val commands = mapOf(
            listOf("index", "sync") to CanonicalOperation.INDEX_SYNC,
            listOf("topology", "build") to CanonicalOperation.TOPOLOGY_BUILD,
            listOf("symbol", "discover", "--query", "Example", "--limit", "10") to
                CanonicalOperation.SYMBOL_DISCOVER,
            listOf("symbol", "inspect", "--candidate", "candidate") to
                CanonicalOperation.SYMBOL_INSPECT,
            listOf("source", "read", "--anchor", exactSelectorToken()) to
                CanonicalOperation.SOURCE_READ,
            listOf(
                "relation", "read", "--selector", "selector", "--relation", "references",
                "--limit", "10",
            ) to CanonicalOperation.RELATION_READ,
            listOf(
                "traversal", "run", "--selector", "selector", "--relation", "callers",
                "--maximum-depth", "2", "--maximum-results", "10",
            ) to CanonicalOperation.TRAVERSAL_RUN,
            listOf("diagnostic", "check", "--scope", ".", "--limit", "10") to
                CanonicalOperation.DIAGNOSTIC_CHECK,
            listOf(
                "change", "plan", "--intent", "add-file", "--path", "A.kt", "--content",
                "class A",
            ) to CanonicalOperation.CHANGE_PLAN,
            listOf("change", "apply", "--plan", "plan") to CanonicalOperation.CHANGE_APPLY,
            listOf("change", "recover", "--plan", "plan") to CanonicalOperation.CHANGE_RECOVER,
        )

        val factory = commandGraphFactory()
        assertEquals(CanonicalOperation.entries.toSet(), commands.values.toSet())
        commands.forEach { (argv, operation) ->
            val parsed = factory.parse(argv)
            assertTrue(parsed is CliCommandParsing.Parsed)
            val action = (parsed as CliCommandParsing.Parsed).action
            assertTrue(action is CliAction.Semantic)
            assertEquals(operation, (action as CliAction.Semantic).request.operation)
        }
        assertTrue(factory.parse(emptyList()) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("workspace", "inspect")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("symbol", "resolve")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("symbol", "describe")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("change", "verify")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("workspace", "refresh")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("up")) is CliCommandParsing.Rejected)
    }

    @Test
    fun `exactly three local lifecycle commands are admitted without semantic arguments`() {
        val commands = mapOf(
            "start" to CliLifecycleCommand.START,
            "stop" to CliLifecycleCommand.STOP,
            "status" to CliLifecycleCommand.STATUS,
        )

        val factory = commandGraphFactory()
        commands.forEach { (argument, command) ->
            val parsed = factory.parse(listOf(argument))
            assertTrue(parsed is CliCommandParsing.Parsed)
            val action = (parsed as CliCommandParsing.Parsed).action
            assertTrue(action is CliAction.Lifecycle)
            assertEquals(command, (action as CliAction.Lifecycle).command)
        }
        assertEquals(setOf("start", "status", "stop"), CliLifecycleCommand.entries.map { it.command }.toSet())
        assertTrue(factory.parse(listOf("clean")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("reindex")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("start", "unexpected")) is CliCommandParsing.Rejected)
    }

    @Test
    fun `command arguments are bounded boundary values`() {
        val factory = commandGraphFactory()

        assertTrue(
            factory.parse(
                listOf("symbol", "discover", "--query=Example", "--limit=10"),
            ) is CliCommandParsing.Parsed,
        )
        assertTrue(
            factory.parse(listOf("symbol", "discover", "")) is CliCommandParsing.Rejected,
        )
    }

    @Test
    fun `semantic rejection is data and exits successfully`() {
        val rejected = CliExit.OperationRejected(
            CliJsonDocument.generated(TestRejectedCliDocument.serializer()).create(
                TestRejectedCliDocument("rejected", "selector-stale"),
            ),
        )

        assertEquals(0, rejected.code)
    }

    @Test
    fun `root discovery returns nearest canonical settings owner`(@TempDir temporary: Path) {
        val root = Files.createDirectories(temporary.resolve("repo"))
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val nested = Files.createDirectories(root.resolve("module/src"))

        val discovered = FilesystemCanonicalRootDiscovery.discover(nested).discoveredRoot()

        assertEquals(root.toRealPath(), discovered.path)
        assertEquals(
            CanonicalRootDiscovery.Rejected(CanonicalRootFailure.ROOT_MARKER_NOT_FOUND),
            FilesystemCanonicalRootDiscovery.discover(temporary.resolve("outside").also(Files::createDirectory)),
        )
    }

    @Test
    fun `indexer launch command rejects callers without private sidecar context`(
        @TempDir temporary: Path,
    ) {
        val rootPath = Files.createDirectories(temporary.resolve("repo"))
        Files.writeString(rootPath.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"")
        val executablePath = Files.writeString(temporary.resolve("kast-indexer"), "#!/bin/sh\n")
        assertTrue(executablePath.toFile().setExecutable(true))
        val root = FilesystemCanonicalRootDiscovery.discover(rootPath).discoveredRoot()
        val executable = IndexerExecutable.admit(executablePath).refinedValue()
        val runtimeId = SemanticRuntimeId.parse("sha256:${"a".repeat(64)}").refinedValue()
        val endpoint = when (
            val resolution = RuntimeEndpoint.at(root, runtimeId, temporary.resolve("runtime.sock"))
        ) {
            is RuntimeEndpointResolution.Resolved -> resolution.endpoint
            is RuntimeEndpointResolution.Rejected -> error("Expected endpoint, got ${resolution.failure}")
        }

        assertEquals(
            IndexerLaunchCommandConstruction.Rejected(
                RuntimeEndpointFailure.LAUNCH_CONTEXT_REQUIRED,
            ),
            IndexerLaunchCommand.create(executable, root, endpoint),
        )
    }

    private fun commandGraphFactory(): CliCommandGraphFactory = when (
        val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())
    ) {
        is CliCommandGraphConstruction.Created -> construction.factory
        is CliCommandGraphConstruction.Rejected -> error("command graph: ${construction.failures}")
    }

    private fun CanonicalRootDiscovery.discoveredRoot(): CanonicalRoot = when (this) {
        is CanonicalRootDiscovery.Discovered -> root
        is CanonicalRootDiscovery.Rejected -> error("Expected discovered root, got $failure")
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }

    private fun exactSelectorToken(): String {
        val payload = "{}".toByteArray(StandardCharsets.UTF_8)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return "exact:v2:$encoded:$digest"
    }
}

@Serializable
private data class TestRejectedCliDocument(
    val status: String,
    val reason: String,
)
