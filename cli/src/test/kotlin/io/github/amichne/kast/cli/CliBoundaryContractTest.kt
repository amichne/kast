package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CliBoundaryContractTest {
    @Test
    fun `exactly eleven public command projections parse to canonical operations`() {
        val commands = mapOf(
            listOf("workspace", "inspect") to CanonicalOperation.WORKSPACE_INSPECT,
            listOf("symbol", "discover") to CanonicalOperation.SYMBOL_DISCOVER,
            listOf("symbol", "resolve") to CanonicalOperation.SYMBOL_RESOLVE,
            listOf("symbol", "describe") to CanonicalOperation.SYMBOL_DESCRIBE,
            listOf("relation", "read") to CanonicalOperation.RELATION_READ,
            listOf("traversal", "run") to CanonicalOperation.TRAVERSAL_RUN,
            listOf("diagnostic", "check") to CanonicalOperation.DIAGNOSTIC_CHECK,
            listOf("change", "plan") to CanonicalOperation.CHANGE_PLAN,
            listOf("change", "apply") to CanonicalOperation.CHANGE_APPLY,
            listOf("change", "verify") to CanonicalOperation.CHANGE_VERIFY,
            listOf("change", "recover") to CanonicalOperation.CHANGE_RECOVER,
        )

        assertEquals(CanonicalOperation.entries.toSet(), commands.values.toSet())
        commands.forEach { (argv, operation) ->
            val invocation = CliCommandParser.parse(argv).parsedInvocation()
            assertEquals(operation, invocation.operation)
            assertTrue(invocation.arguments.values.isEmpty())
        }
        assertEquals(
            CliCommandParsing.Rejected(CliCommandFailure.MissingCommand),
            CliCommandParser.parse(emptyList()),
        )
        assertEquals(
            CliCommandParsing.Rejected(CliCommandFailure.UnknownCommand),
            CliCommandParser.parse(listOf("workspace", "refresh")),
        )
        assertEquals(
            CliCommandParsing.Rejected(CliCommandFailure.UnknownCommand),
            CliCommandParser.parse(listOf("up")),
        )
    }

    @Test
    fun `command arguments are bounded boundary values`() {
        val parsed = CliCommandParser.parse(
            listOf("symbol", "discover", "--query", "Example"),
        ).parsedInvocation()

        assertEquals(listOf("--query", "Example"), parsed.arguments.values.map(CliArgument::value))
        assertEquals(
            CliCommandParsing.Rejected(
                CliCommandFailure.InvalidArgument(CliArgumentFailure.BLANK),
            ),
            CliCommandParser.parse(listOf("symbol", "discover", "")),
        )
    }

    @Test
    fun `semantic rejection is data and exits successfully`() {
        val rejected = CliExit.OperationRejected(
            CliJsonDocument.from(
                kotlinx.serialization.json.buildJsonObject {
                    put("status", "rejected")
                    put("reason", "selector-stale")
                },
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
    fun `indexer launch command retains exact root and canonical socket flag`(@TempDir temporary: Path) {
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

        val command = when (val construction = IndexerLaunchCommand.create(executable, root, endpoint)) {
            is IndexerLaunchCommandConstruction.Created -> construction.command
            is IndexerLaunchCommandConstruction.Rejected ->
                error("Expected command, got ${construction.failure}")
        }

        assertEquals(
            listOf(
                executablePath.toRealPath().toString(),
                "--workspace-root=${root.path}",
                "--socket-path=${temporary.resolve("runtime.sock")}",
                "--runtime-id=${runtimeId.value}",
            ),
            command.arguments,
        )
    }

    private fun CliCommandParsing.parsedInvocation(): CliInvocation = when (this) {
        is CliCommandParsing.Parsed -> invocation
        is CliCommandParsing.Local -> error("Expected semantic invocation, got $command")
        is CliCommandParsing.Rejected -> error("Expected parsed invocation, got $failure")
    }

    private fun CanonicalRootDiscovery.discoveredRoot(): CanonicalRoot = when (this) {
        is CanonicalRootDiscovery.Discovered -> root
        is CanonicalRootDiscovery.Rejected -> error("Expected discovered root, got $failure")
    }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong = when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Expected refined value, got $failure")
    }
}
