package io.github.amichne.kast.cli.rpc

import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.CliExit
import io.github.amichne.kast.cli.direct.DirectToolRegistration
import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultReference
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.registry.AgentToolName
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KastToolRpcQuerySessionProcessTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `one real child reuses bridge composition and emits canonical replies without catalog projection`() {
        Files.writeString(temporary.resolve("settings.gradle.kts"), "rootProject.name = \"query-session-fixture\"")
        val stdout = temporary.resolve("stdout.jsonl")
        val stderr = temporary.resolve("stderr.txt")
        val command =
            listOf(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=$temporary",
                "-cp",
                System.getProperty("java.class.path"),
                QuerySessionSubprocessFixture::class.java.name,
                temporary.toString(),
            )
        val process =
            ProcessBuilder(command)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .apply { environment().clear() }
                .start()
        try {
            val examples = PublicToolContract.examples(PublicToolIdentity.QUERY_SYMBOLS).examples
            val reference =
                (QueryResultReference.parse("result:v1:00000000-0000-0000-0000-000000000001") as Refinement.Refined)
                    .value
            val cursor = (QueryResultCursor.parse(43) as Refinement.Refined).value
            val read = Json.encodeToString(QuerySessionTestReadQuery(QuerySessionTestRead(reference, cursor)))
            val run = examples.getValue("runByName").value.toString()
            process.outputStream.use { it.write((run + "\n" + read + "\n").toByteArray()) }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Bounded fixture child did not finish")
            assertEquals(0, process.exitValue(), Files.readString(stderr))
            val actual = Files.readAllLines(stdout).map { Json.decodeFromString<ToolRpcReply>(it) }
            val expected = Json.decodeFromString<Map<String, ToolRpcReply>>(QueryDeliveryFixtures.serialized())
            assertEquals(listOf(expected.getValue("prefix"), expected.getValue("tail")), actual)
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}

/** Test-only subprocess composition over the real session rule and RPC bridge; no installed/native effects. */
internal object QuerySessionSubprocessFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args.single())
        val cases = Json.decodeFromString<Map<String, ToolRpcReply>>(QueryDeliveryFixtures.serialized())
        val replies = listOf(cases.getValue("prefix"), cases.getValue("tail"))
        var invoked = 0
        val name = (AgentToolName.parse(PublicToolIdentity.QUERY_SYMBOLS.toolName) as Refinement.Refined).value
        val session =
            KastDirectToolSession(
                registration = DirectToolRegistration(setOf(name)) { error("Query must not project a catalog") },
                root = { FilesystemCanonicalRootDiscovery.discover(root) },
                start = { Refinement.Refined(Unit) },
                invokePublic = { admitted ->
                    val query = (admitted.canonical as PublicToolCanonical.Query).request
                    check(if (invoked == 0) query is QueryRunRequest.Run else query is QueryRunRequest.ReadResult)
                    val reply = replies.getOrElse(invoked++) { error("Excess invocation") } as ToolRpcReply.Complete
                    CliExit.Complete(CanonicalJsonDocument.generated(JsonObject.serializer()).create(reply.document))
                },
            )
        val bridge = KastToolRpcBridge(session)
        val json = Json { encodeDefaults = true }
        KastToolRpcQuerySession(bridge::callAdmitted).run(System.`in`) { reply ->
            println(json.encodeToString<ToolRpcReply>(reply))
            System.out.flush()
        }
        check(invoked == replies.size) { "Unconsumed fixture replies" }
    }
}
