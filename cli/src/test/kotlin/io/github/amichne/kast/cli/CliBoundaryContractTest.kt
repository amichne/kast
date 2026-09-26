package io.github.amichne.kast.cli

import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.CanonicalRootFailure
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CliBoundaryContractTest {
    @Test
    fun `retired relation and traversal commands cannot parse into semantic reads`() {
        val parsed =
            commandGraphFactory()
                .parse(
                    listOf("relation", "read"),
                    CliRequestDocumentInput.Provided(Json.encodeToString(EmptyRequest.serializer(), EmptyRequest)),
                )
        assertTrue(parsed is CliCommandParsing.Rejected)
        assertTrue(commandGraphFactory().parse(listOf("traversal", "run")) is CliCommandParsing.Rejected)
    }

    @Test
    fun `every public command projection parses to its canonical operation`() {
        val selector = exactSelectorToken()
        val commands =
            listOf(
                SemanticCase(
                    listOf("source", "read"),
                    """{"anchor":{"type":"symbol","selector":"$selector"},"region":{"type":"anchor"},"entities":{"type":"none"},"text":{"type":"complete"},"entityLimit":250,"textByteLimit":65536,"page":{"type":"first"}}""",
                    CanonicalOperation.SOURCE_READ,
                ),
                SemanticCase(
                    listOf("change", "plan"),
                    """{"intent":{"kind":"add-file","relativePath":"A.kt","content":"class A"}}""",
                    CanonicalOperation.CHANGE_PLAN,
                ),
                SemanticCase(
                    listOf("change", "apply"),
                    """{"planIdentity":"plan"}""",
                    CanonicalOperation.CHANGE_APPLY,
                ),
                SemanticCase(
                    listOf("change", "recover"),
                    """{"planIdentity":"plan"}""",
                    CanonicalOperation.CHANGE_RECOVER,
                ),
            )

        val factory = commandGraphFactory()
        assertEquals(
            io.github.amichne.kast.protocol.registry.HostedOperationProjection.publicDefinitions
                .map { it.operation }
                .filterNot {
                    it in
                        setOf(
                            CanonicalOperation.WORKSPACE_LIFECYCLE,
                            CanonicalOperation.QUERY_RUN,
                            CanonicalOperation.DIAGNOSTIC_CHECK,
                            CanonicalOperation.CHANGE,
                        )
                }
                .toSet(),
            commands.map { it.operation }.toSet(),
        )
        commands.forEach { (argv, document, operation) ->
            val parsed = factory.parse(argv, CliRequestDocumentInput.Provided(document))
            assertTrue(parsed is CliCommandParsing.Parsed)
            val action = (parsed as CliCommandParsing.Parsed).action
            assertTrue(action is CliAction.Semantic)
            assertEquals(operation, (action as CliAction.Semantic).request.operation)
        }
        assertEquals(
            io.github.amichne.kast.cli.command.CliAction.Local.Inspect,
            (factory.parse(emptyList()) as CliCommandParsing.Parsed).action,
        )
        assertTrue(factory.parse(listOf("workspace", "inspect")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("symbol", "resolve")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("symbol", "describe")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("change", "verify")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("workspace", "refresh")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("up")) is CliCommandParsing.Rejected)
    }

    @Test
    fun `retired query and diagnostic commands reject before request input`() {
        val factory = commandGraphFactory()
        for (command in listOf(listOf("query", "run"), listOf("diagnostic", "check"))) {
            assertTrue(
                factory.parse(command, CliRequestDocumentInput.Deferred { error("retired command read input") })
                    is CliCommandParsing.Rejected,
                command.toString(),
            )
        }
    }

    @Test
    fun `retired lifecycle commands have no public grammar or semantic arguments`() {
        val factory = commandGraphFactory()
        for (command in listOf("start", "stop")) assertTrue(
            factory.parse(listOf(command)) is CliCommandParsing.Rejected
        )
        listOf(listOf("status"), listOf("index", "sync"), listOf("topology", "build")).forEach {
            assertTrue(factory.parse(it) is CliCommandParsing.Rejected, it.toString())
        }
        assertTrue(factory.parse(listOf("broker", "serve")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("clean")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("reindex")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("start", "unexpected")) is CliCommandParsing.Rejected)
    }

    @Test
    fun `retired symbol commands reject before request input`() {
        val factory = commandGraphFactory()
        for (command in listOf(listOf("symbol", "discover"), listOf("symbol", "inspect"))) {
            assertTrue(factory.parse(command, CliRequestDocumentInput.Deferred { error("retired route read input") }) is CliCommandParsing.Rejected)
        }
    }

    @Test
    fun `semantic rejection is data and exits successfully`() {
        val rejected =
            CliExit.OperationRejected(
                CanonicalJsonDocument.generated(TestRejectedCliDocument.serializer())
                    .create(TestRejectedCliDocument("rejected", "selector-stale"))
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

    private fun commandGraphFactory(): CliCommandGraphFactory =
        when (val construction = CliCommandGraphFactory.create(canonicalCliRequestPreparers())) {
            is CliCommandGraphConstruction.Created -> construction.factory
            is CliCommandGraphConstruction.Rejected -> error("command graph: ${construction.failures}")
        }

    private fun CanonicalRootDiscovery.discoveredRoot(): CanonicalRoot =
        when (this) {
            is CanonicalRootDiscovery.Discovered -> root
            is CanonicalRootDiscovery.Rejected -> error("Expected discovered root, got $failure")
        }

    private fun <Strong, Failure> Refinement<Strong, Failure>.refinedValue(): Strong =
        when (this) {
            is Refinement.Refined -> value
            is Refinement.Rejected -> error("Expected refined value, got $failure")
        }

    private fun exactSelectorToken(): String {
        val payload = "{}".toByteArray(StandardCharsets.UTF_8)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val digest =
            MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        return "exact:v2:$encoded:$digest"
    }

    private data class SemanticCase(
        val argv: List<String>,
        val document: String,
        val operation: CanonicalOperation,
    )
}

@Serializable private data object EmptyRequest

@Serializable
private data class TestRejectedCliDocument(
    val status: String,
    val reason: String,
)
