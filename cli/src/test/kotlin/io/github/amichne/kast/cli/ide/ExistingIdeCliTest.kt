package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.appserver.ide.CanonicalRootDiscoverer
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.ExistingIdeClassName
import io.github.amichne.kast.appserver.ide.ExistingIdeClient
import io.github.amichne.kast.appserver.ide.ExistingIdeDescriptor
import io.github.amichne.kast.appserver.ide.ExistingIdeDocuments
import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.appserver.ide.ExistingIdeQualifiedClassName
import io.github.amichne.kast.appserver.ide.ExistingIdeSocketClient
import io.github.amichne.kast.appserver.ide.admitLiveEvidence
import io.github.amichne.kast.appserver.ide.canonicalRootFixture
import io.github.amichne.kast.cli.*
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExistingIdeCliTest {
    @TempDir lateinit var temporary: Path
    private val root = canonicalRootFixture(Path.of("/workspace"))
    private val host = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val descriptor = ExistingIdeDescriptor(123, host)

    @Test
    fun `query response evidence must belong to the admitted live host and root`() {
        fun live(path: String, nonce: java.util.UUID) =
            io.github.amichne.kast.kernel.EvidenceBasis.Live(
                (io.github.amichne.kast.kernel.LiveReadEvidence.create(
                        path,
                        nonce,
                        1,
                        io.github.amichne.kast.kernel.LiveReadContentView.SAVED_PSI_COMMITTED,
                        1,
                    ) as Refinement.Refined)
                    .value
            )
        assertEquals(Refinement.Refined(Unit), admitLiveEvidence(live("/workspace", host), root, descriptor))
        for (basis in
            listOf(
                live("/other", host),
                live("/workspace", java.util.UUID.fromString("00000000-0000-0000-0000-000000000002")),
                io.github.amichne.kast.kernel.EvidenceBasis.Published(
                    (io.github.amichne.kast.kernel.EvidenceGeneration.parse(1) as Refinement.Refined).value
                ),
            )) assertEquals(
            Refinement.Rejected(ExistingIdeFailure.RESPONSE_REJECTED),
            admitLiveEvidence(basis, root, descriptor),
        )
    }

    @Test
    fun `IDE commands route directly to the existing IDE`() {
        var calls = 0
        val result =
            executeExistingIdeCli(
                listOf("ide", "status"),
                root.path,
                CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                ExistingIdeClient { _, _ ->
                    calls++
                    ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
                },
            )
        assertEquals(1, calls)
        assertTrue(result.document.value.contains("ide-host-unavailable"))
    }

    @Test
    fun `index runtime selection fails closed before installed bootstrap`() {
        for (argv in
            listOf(
                listOf("ide"),
                listOf("ide", "sync"),
                listOf("ide", "unknown"),
                listOf("ide", "--help"),
                listOf("ide", "classes", "C"),
            )) {
            assertEquals(CliRuntimePath.EXISTING_IDE, selectCliRuntimePath(argv))
        }
        for (argv in listOf(emptyList(), listOf("start"), listOf("index-other"))) {
            assertEquals(CliRuntimePath.INSTALLED, selectCliRuntimePath(argv))
        }
        val roots = CanonicalRootDiscoverer { fail("Internal sync reached root discovery") }
        val client = ExistingIdeClient { _, _ -> fail("Internal sync reached host") }
        assertNotEquals(0, executeExistingIdeCli(listOf("ide", "sync"), root.path, roots, client).code)
        val help = executeExistingIdeCli(listOf("ide", "--help"), root.path, roots, client)
        assertEquals(0, help.code)
        assertFalse(help.document.value.contains("trust-broker"))
        assertNotEquals(0, executeExistingIdeCli(listOf("ide", "trust-broker"), root.path, roots, client).code)
        for (shell in listOf("bash", "zsh", "fish")) {
            val completion =
                executeExistingIdeCli(listOf("ide", "generate-completion", shell), root.path, roots, client)
            assertEquals(0, completion.code)
            assertFalse(completion.document.value.contains("supertype"))
        }
    }

    @Test
    fun `normal semantic read families select the existing IDE before bootstrap`() {
        for (arguments in
            listOf(
                listOf("query", "run"),
                listOf("symbol", "discover"),
                listOf("symbol", "inspect"),
                listOf("source", "read"),
                listOf("relation", "read"),
                listOf("traversal", "run"),
                listOf("diagnostic", "check"),
            )) {
            assertEquals(CliRuntimePath.EXISTING_IDE, selectCliRuntimePath(arguments))
            assertEquals(CliRuntimePath.EXISTING_IDE, selectCliRuntimePath(listOf("--") + arguments))
        }
    }

    @Test
    fun `full command graph retains passive IDE status`() {
        val factory =
            io.github.amichne.kast.cli.command.CliCommandGraphFactory.create(
                io.github.amichne.kast.protocol.wire.presentation.canonicalCliRequestPreparers()
            ) as io.github.amichne.kast.cli.command.CliCommandGraphConstruction.Created
        val parsed =
            factory.factory.parse(listOf("ide", "status"))
                as io.github.amichne.kast.cli.command.CliCommandParsing.Parsed
        val action = parsed.action as io.github.amichne.kast.cli.command.CliAction.Local.ExistingIde
        assertEquals(ExistingIdeOperation.Status, action.operation)
    }

    @Test
    fun `hosted help resolves without root or socket effects`() {
        val roots = CanonicalRootDiscoverer { fail("Help attempted root discovery") }
        val client = ExistingIdeClient { _, _ -> fail("Help attempted socket access") }
        for (argv in
            listOf(
                listOf("ide", "--help"),
                listOf("ide", "status", "--help"),
                listOf("ide", "refresh", "--help"),
            )) {
            val answer = executeExistingIdeCli(argv, Path.of("/missing"), roots, client)
            assertEquals(0, answer.code)
            assertTrue(answer.document.value.isNotBlank())
        }
    }

    @Test
    fun `retired duplicate IDE reads never reach effects`() {
        val roots = CanonicalRootDiscoverer { fail("Retired command reached root discovery") }
        val client = ExistingIdeClient { _, _ -> fail("Retired command reached socket") }
        for (arguments in listOf(listOf("classes", "Refinement"), listOf("supertype", "example.Child"))) {
            val result = executeExistingIdeCli(listOf("ide") + arguments, root.path, roots, client)
            assertNotEquals(0, result.code)
            assertTrue(result.document.value.contains("diagnostic"))
        }
        assertNotEquals(
            0,
            executeExistingIdeCli(listOf("ide", "status", "--root", "\u0000"), root.path, roots, client).code,
        )
    }

    @Test
    fun `legacy endpoint selectors retain exact name admission`() {
        for (name in listOf("a.b", "*", "0Class", "x".repeat(513))) {
            assertTrue(ExistingIdeClassName.parse(name) is Refinement.Rejected)
        }
        for (name in
            listOf(
                ".Child",
                "example..Child",
                "example.Child.",
                "example.*",
                "example.0Child",
                "x".repeat(513),
                "a.".repeat(2048) + "C",
            )) {
            assertTrue(ExistingIdeQualifiedClassName.parse(name) is Refinement.Rejected)
        }
    }

    @Test
    fun `completion is generated from the hosted command family without effects`() {
        val roots = CanonicalRootDiscoverer { fail("Completion attempted root discovery") }
        val client = ExistingIdeClient { _, _ -> fail("Completion attempted socket access") }
        for (shell in listOf("bash", "zsh", "fish")) {
            val result = executeExistingIdeCli(listOf("ide", "generate-completion", shell), root.path, roots, client)
            assertEquals(0, result.code)
            assertFalse(result.document.value.contains("classes"))
            assertTrue(result.document.value.contains("status"))
            assertFalse(result.document.value.contains("supertype"))
            assertTrue(result.document.value.contains(if (shell == "fish") "-l root" else "--root"))
        }
        assertNotEquals(
            0,
            executeExistingIdeCli(listOf("ide", "generate-completion", "unknown"), root.path, roots, client).code,
        )
    }

    @Test
    fun `status routing uses only the selected root and explicit existing host capability`() {
        var calls = 0
        val client = ExistingIdeClient { exact, operation ->
            assertSame(root, exact)
            assertEquals(ExistingIdeOperation.Status, operation)
            calls++
            ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
        }
        val result =
            executeExistingIdeCli(
                listOf("ide", "status", "--root", "/workspace"),
                Path.of("/other"),
                CanonicalRootDiscoverer {
                    assertEquals(root.path, it)
                    CanonicalRootDiscovery.Discovered(root)
                },
                client,
            )
        assertEquals(1, calls)
        assertNotEquals(0, result.code)
        assertTrue(result.document.value.contains("ide-host-unavailable"))
    }

    @Test
    fun `missing endpoint creates no state`() {
        val result = ExistingIdeSocketClient(temporary).query(root, ExistingIdeOperation.Status)
        assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE), result)
        assertFalse(Files.exists(temporary.resolve(".kast")))
    }

    @Test
    fun `supertype replies correlate exact compiler identity and detached publication`() {
        val operation =
            ExistingIdeOperation.Supertype(
                (ExistingIdeQualifiedClassName.parse("example.Child") as Refinement.Refined).value
            )
        fun declaration(name: String): String {
            val signature =
                listOf("canonical-signature-v1", "class-like", name).joinToString("") { "${it.toByteArray().size}:$it" }
            val identity =
                java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") {
                    "%02x".format(it)
                }
            return """{"file":"/workspace/Classes.kt","compilerIdentity":"canonical-signature-sha256-v1|$identity","canonicalSignature":"$signature","signature":{"kind":"class_like","qualifiedIdentity":"$name"},"documentStamp":1,"vfsStamp":1,"module":"fixture","gradleBuildRoot":"/workspace","gradleProject":":fixture","sourceRoot":"src","sourceKind":"PRODUCTION","provenanceAuthority":"cached_source_folder_flag"}"""
        }
        val valid =
            """{"schemaVersion":1,"outcome":"published","publication":"request_local_same_source_epoch","content":"saved_committed_ide_vfs","scope":"cached_gradle_source_folders","kind":"inheritors","stage":"RESULT_DETACHED","workspaceRoot":"/workspace","host":{"ideBuild":"test","kotlinBuild":"test"},"supertype":${declaration("example.Parent")},"inheritor":${declaration("example.Child")}}"""
        assertTrue(
            ExistingIdeDocuments.response(valid.toByteArray(), root, operation, descriptor)
                is ExistingIdeExchange.Received
        )
        for (invalid in
            listOf(
                valid.replace("example.Child", "other.Child"),
                valid.replace("/workspace", "/other"),
                valid.replace("RESULT_DETACHED", "SEMANTIC_READ"),
            )) {
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED),
                ExistingIdeDocuments.response(invalid.toByteArray(), root, operation, descriptor),
            )
        }
    }

    @Test
    fun `schema admits empty index evidence and rejects wrong names roots and duplicate fields`() {
        val operation = ExistingIdeOperation.Classes((ExistingIdeClassName.parse("Absent") as Refinement.Refined).value)
        val valid =
            """{"schemaVersion":1,"outcome":"published","publication":"request_local_same_source_epoch","content":"saved_committed_ide_vfs","scope":"cached_gradle_source_folders","kind":"classes","stage":"RESULT_DETACHED","workspaceRoot":"/workspace","host":{"ideBuild":"test","kotlinBuild":"test"},"name":"Absent","indexAuthority":"existing_ide_kotlin_stub_index","declarations":[]}"""
        assertTrue(
            ExistingIdeDocuments.response(valid.toByteArray(), root, operation, descriptor)
                is ExistingIdeExchange.Received
        )
        for (invalid in
            listOf(
                valid.replace("Absent", "Different"),
                valid.replace("/workspace", "/other"),
                valid.replace("RESULT_DETACHED", "SEMANTIC_READ"),
                valid.replace("\"name\":", "\"name\":\"Ignored\",\"name\":"),
                valid + "{}",
            )) {
            assertEquals(
                ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED),
                ExistingIdeDocuments.response(invalid.toByteArray(), root, operation, descriptor),
            )
        }
    }

    @Test
    fun `status binds the descriptor host identity and rejects unknown capabilities`() {
        val valid =
            """{"type":"KAST_IDE_HOST","protocol":3,"root":"/workspace","hostPid":123,"host":"$host","querySchema":"kast.query.run.v2","operations":["DESCRIBE","CLASS_LOOKUP","DIRECT_SUPERTYPE","QUERY_RUN","SYMBOL_DISCOVER","SYMBOL_INSPECT","SOURCE_READ","RELATION_READ","TRAVERSAL_RUN","DIAGNOSTIC_CHECK","CHANGE_PLAN","CHANGE_APPROVAL_PREPARE","CHANGE_APPLY","CHANGE_RECOVER"],"indexAuthority":"existing_ide_kotlin_stub_index"}"""
        assertTrue(
            ExistingIdeDocuments.response(valid.toByteArray(), root, ExistingIdeOperation.Status, descriptor)
                is ExistingIdeExchange.Received
        )
        for (invalid in
            listOf(
                valid.replace("123", "124"),
                valid.replace("CLASS_LOOKUP", "IMPORT"),
                valid.replace("KAST_IDE_HOST", "KAST_IDE_ENDPOINT"),
            )) {
            assertTrue(
                ExistingIdeDocuments.response(invalid.toByteArray(), root, ExistingIdeOperation.Status, descriptor)
                    is ExistingIdeExchange.Rejected
            )
        }
    }
}
