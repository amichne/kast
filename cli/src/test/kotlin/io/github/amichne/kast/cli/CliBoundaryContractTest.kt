package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliAction
import io.github.amichne.kast.cli.command.CliCommandGraphConstruction
import io.github.amichne.kast.cli.command.CliCommandGraphFactory
import io.github.amichne.kast.cli.command.CliCommandParsing
import io.github.amichne.kast.cli.command.CliLifecycleCommand
import io.github.amichne.kast.cli.command.CliProductCommand
import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CliBoundaryContractTest {
    @Test
    fun `every public command projection parses to its canonical operation`() {
        val selector = exactSelectorToken()
        val commands =
            listOf(
                SemanticCase(
                    listOf("query", "run"),
                    """{"type":"QUERY","from":{"type":"ALL","kinds":["CLASS"],"scope":{"type":"DIRECTORY","value":".","sourceSets":["main"]}}}""",
                    CanonicalOperation.QUERY_RUN,
                ),
                SemanticCase(
                    listOf("symbol", "discover"),
                    """{"target":{"type":"name","query":"Example","kind":"symbol","match":"fuzzy"},"limit":10}""",
                    CanonicalOperation.SYMBOL_DISCOVER,
                ),
                SemanticCase(
                    listOf("symbol", "inspect"),
                    """{"target":{"type":"candidate","selector":"candidate"}}""",
                    CanonicalOperation.SYMBOL_INSPECT,
                ),
                SemanticCase(
                    listOf("source", "read"),
                    """{"anchor":{"type":"symbol","selector":"$selector"},"region":{"type":"anchor"},"entities":{"type":"none"},"text":{"type":"complete"},"entityLimit":250,"textByteLimit":65536,"page":{"type":"first"}}""",
                    CanonicalOperation.SOURCE_READ,
                ),
                SemanticCase(
                    listOf("relation", "read"),
                    """{"exactSelector":"selector","relation":"references","limit":10,"position":{"type":"start"}}""",
                    CanonicalOperation.RELATION_READ,
                ),
                SemanticCase(
                    listOf("traversal", "run"),
                    """{"exactSelector":"selector","relation":"callers","maximumDepth":2,"maximumResults":10,"position":{"type":"start"}}""",
                    CanonicalOperation.TRAVERSAL_RUN,
                ),
                SemanticCase(
                    listOf("diagnostic", "check"),
                    """{"path":".","limit":10}""",
                    CanonicalOperation.DIAGNOSTIC_CHECK,
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
    fun `query command admits public defaults and rejects evaluator syntax`() {
        val factory = commandGraphFactory()
        val command = listOf("query", "run")
        val minimal =
            factory.parse(
                command,
                CliRequestDocumentInput.Provided(
                    """{"type":"QUERY","from":{"type":"SEARCH","query":"OrderService"}}"""
                ),
            )
        assertTrue(minimal is CliCommandParsing.Parsed)
        val action = (minimal as CliCommandParsing.Parsed).action
        assertTrue(action is CliAction.Semantic)
        assertEquals(CanonicalOperation.QUERY_RUN, (action as CliAction.Semantic).request.operation)

        val retired =
            """{"from":{"type":"symbols","match":{"type":"all"},"scope":{"sourceSets":["main"],"directory":null,"packageName":null},"declarationKinds":["class"]},"steps":[],"output":{"type":"symbols","fields":["name","location"]},"execution":{"kind":"exhaustive","budget":"interactive"}}"""
        assertTrue(factory.parse(command, CliRequestDocumentInput.Provided(retired)) is CliCommandParsing.Rejected)
    }

    @Test
    fun `exactly two local lifecycle commands are admitted without semantic arguments`() {
        val commands =
            mapOf(
                "start" to CliLifecycleCommand.START,
                "stop" to CliLifecycleCommand.STOP,
            )

        val factory = commandGraphFactory()
        commands.forEach { (argument, command) ->
            val parsed = factory.parse(listOf(argument))
            assertTrue(parsed is CliCommandParsing.Parsed)
            val action = (parsed as CliCommandParsing.Parsed).action
            assertTrue(action is CliAction.Lifecycle)
            assertEquals(command, (action as CliAction.Lifecycle).command)
        }
        assertEquals(setOf("start", "stop"), factory.surface.lifecycleCommands.map { it.command }.toSet())
        listOf(listOf("status"), listOf("product", "inspect"), listOf("index", "sync"), listOf("topology", "build"))
            .forEach {
                assertTrue(factory.parse(it) is CliCommandParsing.Rejected, it.toString())
            }
        assertEquals(
            CliAction.Local.BrokerServe,
            (factory.parse(listOf("broker", "serve")) as CliCommandParsing.Parsed).action,
        )
        assertTrue(CliProductCommand.BROKER_SERVE !in factory.surface.localCommands)
        assertTrue(factory.parse(listOf("clean")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("reindex")) is CliCommandParsing.Rejected)
        assertTrue(factory.parse(listOf("start", "unexpected")) is CliCommandParsing.Rejected)
    }

    @Test
    fun `command arguments are bounded boundary values`() {
        val factory = commandGraphFactory()

        assertTrue(
            factory.parse(
                listOf("symbol", "discover"),
                CliRequestDocumentInput.Provided(
                    """{"target":{"type":"name","query":"Example","kind":"symbol","match":"fuzzy"},"limit":10}"""
                ),
            ) is CliCommandParsing.Parsed
        )
        assertTrue(factory.parse(listOf("symbol", "discover", "")) is CliCommandParsing.Rejected)
        assertTrue(
            factory.parse(
                listOf("traversal", "run"),
                CliRequestDocumentInput.Provided(
                    """{"exactSelector":"selector","relation":"callees","maximumDepth":2,"maximumResults":10,"position":{"type":"resume","continuation":"${traversalContinuationToken()}"}}"""
                ),
            ) is CliCommandParsing.Parsed
        )
        assertTrue(
            factory.parse(
                listOf("traversal", "run"),
                CliRequestDocumentInput.Provided(
                    """{"exactSelector":"selector","relation":"callees","maximumDepth":2,"maximumResults":10,"position":{"type":"resume","continuation":"bad"}}"""
                ),
            ) is CliCommandParsing.Rejected
        )
    }

    @Test
    fun `semantic rejection is data and exits successfully`() {
        val rejected =
            CliExit.OperationRejected(
                CliJsonDocument.generated(TestRejectedCliDocument.serializer())
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

    private fun traversalContinuationToken(): String {
        val payload = "checkpoint".toByteArray(StandardCharsets.UTF_8)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val digest =
            MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        return "traversal-continuation:v1:$encoded:$digest"
    }

    private data class SemanticCase(
        val argv: List<String>,
        val document: String,
        val operation: CanonicalOperation,
    )
}

@Serializable
private data class TestRejectedCliDocument(
    val status: String,
    val reason: String,
)
