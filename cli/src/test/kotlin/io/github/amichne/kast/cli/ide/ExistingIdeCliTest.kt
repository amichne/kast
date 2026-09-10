package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.cli.*
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExistingIdeCliTest {
    @TempDir lateinit var temporary: Path
    private val root = CanonicalRoot(Path.of("/workspace"))
    private val descriptor = ExistingIdeDescriptor(123)

    @Test fun `primary index commands route directly to the existing IDE`() {
        for (arguments in listOf(listOf("status"), listOf("classes", "Refinement"), listOf("supertype", "example.Child"))) {
            var calls = 0
            val result = executeExistingIdeCli(listOf("index") + arguments, root.path,
                CanonicalRootDiscoverer { CanonicalRootDiscovery.Discovered(root) },
                ExistingIdeClient { _, _ -> calls++; ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE) })
            assertEquals(1, calls)
            assertTrue(result.document.value.contains("ide-host-unavailable"))
        }
    }

    @Test fun `index runtime selection fails closed before installed bootstrap`() {
        for (argv in listOf(listOf("index"), listOf("index", "sync"), listOf("index", "unknown"), listOf("index", "--help"), listOf("ide", "classes", "C"))) {
            assertEquals(CliRuntimePath.EXISTING_IDE, selectCliRuntimePath(argv))
        }
        for (argv in listOf(emptyList(), listOf("query"), listOf("start"), listOf("index-other"))) {
            assertEquals(CliRuntimePath.INSTALLED, selectCliRuntimePath(argv))
        }
        val roots = CanonicalRootDiscoverer { fail("Internal sync reached root discovery") }
        val client = ExistingIdeClient { _, _ -> fail("Internal sync reached host") }
        assertNotEquals(0, executeExistingIdeCli(listOf("index", "sync"), root.path, roots, client).code)
        assertEquals(0, executeExistingIdeCli(listOf("index", "--help"), root.path, roots, client).code)
        for (shell in listOf("bash", "zsh", "fish")) {
            val completion = executeExistingIdeCli(listOf("index", "generate-completion", shell), root.path, roots, client)
            assertEquals(0, completion.code)
            assertTrue(completion.document.value.contains("supertype"))
        }
    }

    @Test fun `full command graph retains the same primary index action`() {
        val factory = io.github.amichne.kast.cli.command.CliCommandGraphFactory.create(io.github.amichne.kast.cli.projection.canonicalCliRequestPreparers())
            as io.github.amichne.kast.cli.command.CliCommandGraphConstruction.Created
        val parsed = factory.factory.parse(listOf("index", "supertype", "example.Child"))
            as io.github.amichne.kast.cli.command.CliCommandParsing.Parsed
        val action = parsed.action as io.github.amichne.kast.cli.command.CliAction.Local.ExistingIde
        assertEquals("example.Child", (action.operation as ExistingIdeOperation.Supertype).name.value)
    }

    @Test fun `hosted help resolves without root or socket effects`() {
        val roots = CanonicalRootDiscoverer { fail("Help attempted root discovery") }
        val client = ExistingIdeClient { _, _ -> fail("Help attempted socket access") }
        for (argv in listOf(listOf("ide", "--help"), listOf("ide", "classes", "--help"), listOf("ide", "supertype", "--help"), listOf("ide", "status", "--help"))) {
            val answer = executeExistingIdeCli(argv, Path.of("/missing"), roots, client)
            assertEquals(0, answer.code)
            assertTrue(answer.document.value.contains("IDEA"))
        }
    }

    @Test fun `invalid exact names and missing values never reach effects`() {
        val roots = CanonicalRootDiscoverer { fail("Invalid input reached root discovery") }
        val client = ExistingIdeClient { _, _ -> fail("Invalid input reached socket") }
        for (name in listOf("a.b", "*", "0Class", "x".repeat(513))) {
            assertNotEquals(0, executeExistingIdeCli(listOf("ide", "classes", name), root.path, roots, client).code)
        }
        assertNotEquals(0, executeExistingIdeCli(listOf("ide", "classes"), root.path, roots, client).code)
        val missingName = executeExistingIdeCli(listOf("ide", "classes"), root.path, roots, client)
        assertTrue(missingName.document.value.contains("diagnostic"))
        assertNotEquals(0, executeExistingIdeCli(listOf("ide", "status", "--root", "\u0000"), root.path, roots, client).code)
    }

    @Test fun `completion is generated from the hosted command family without effects`() {
        val roots = CanonicalRootDiscoverer { fail("Completion attempted root discovery") }
        val client = ExistingIdeClient { _, _ -> fail("Completion attempted socket access") }
        for (shell in listOf("bash", "zsh", "fish")) {
            val result = executeExistingIdeCli(listOf("ide", "generate-completion", shell), root.path, roots, client)
            assertEquals(0, result.code)
            assertTrue(result.document.value.contains("classes"))
            assertTrue(result.document.value.contains("status"))
            assertTrue(result.document.value.contains("supertype"))
            assertTrue(result.document.value.contains(if (shell == "fish") "-l root" else "--root"))
        }
        assertNotEquals(0, executeExistingIdeCli(listOf("ide", "generate-completion", "unknown"), root.path, roots, client).code)
    }

    @Test fun `index routing uses only the selected root and explicit existing host capability`() {
        var calls = 0
        val client = ExistingIdeClient { exact, operation ->
            assertSame(root, exact)
            assertEquals("Refinement", (operation as ExistingIdeOperation.Classes).name.value)
            calls++
            ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
        }
        val result = executeExistingIdeCli(listOf("ide", "classes", "Refinement", "--root", "/workspace"), Path.of("/other"),
            CanonicalRootDiscoverer { assertEquals(root.path, it); CanonicalRootDiscovery.Discovered(root) }, client)
        assertEquals(1, calls)
        assertNotEquals(0, result.code)
        assertTrue(result.document.value.contains("ide-host-unavailable"))
    }

    @Test fun `missing endpoint creates no state`() {
        val result = ExistingIdeSocketClient(temporary).query(root, ExistingIdeOperation.Status)
        assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE), result)
        assertFalse(Files.exists(temporary.resolve(".kast")))
    }

    @Test fun `qualified supertype command reaches only the existing host capability`() {
        var calls = 0
        val result = executeExistingIdeCli(listOf("ide", "supertype", "example.Outer.Child", "--root", "/workspace"), Path.of("/other"),
            CanonicalRootDiscoverer { assertEquals(root.path, it); CanonicalRootDiscovery.Discovered(root) },
            ExistingIdeClient { exact, operation ->
                assertSame(root, exact)
                assertEquals("example.Outer.Child", (operation as ExistingIdeOperation.Supertype).name.value)
                calls++
                ExistingIdeExchange.Rejected(ExistingIdeFailure.HOST_UNAVAILABLE)
            })
        assertEquals(1, calls)
        assertNotEquals(0, result.code)
        assertTrue(result.document.value.contains("ide-host-unavailable"))
    }

    @Test fun `qualified names reject invalid components before reaching host effects`() {
        val roots = CanonicalRootDiscoverer { fail("Invalid selection reached root discovery") }
        val client = ExistingIdeClient { _, _ -> fail("Invalid selection reached socket") }
        for (name in listOf(".Child", "example..Child", "example.Child.", "example.*", "example.0Child", "x".repeat(513), "a.".repeat(2048) + "C")) {
            assertNotEquals(0, executeExistingIdeCli(listOf("ide", "supertype", name), root.path, roots, client).code)
        }
    }

    @Test fun `supertype replies correlate exact compiler identity and detached publication`() {
        val operation = ExistingIdeOperation.Supertype((ExistingIdeQualifiedClassName.parse("example.Child") as Refinement.Refined).value)
        fun declaration(name: String): String {
            val signature = listOf("canonical-signature-v1", "class-like", name).joinToString("") { "${it.toByteArray().size}:$it" }
            val identity = java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            return """{"file":"/workspace/Classes.kt","compilerIdentity":"canonical-signature-sha256-v1|$identity","canonicalSignature":"$signature","signature":{"kind":"class_like","qualifiedIdentity":"$name"},"documentStamp":1,"vfsStamp":1,"module":"fixture","gradleBuildRoot":"/workspace","gradleProject":":fixture","sourceRoot":"src","sourceKind":"PRODUCTION","provenanceAuthority":"cached_source_folder_flag"}"""
        }
        val valid = """{"schemaVersion":1,"outcome":"published","publication":"request_local_same_source_epoch","content":"saved_committed_ide_vfs","scope":"cached_gradle_source_folders","kind":"inheritors","stage":"RESULT_DETACHED","workspaceRoot":"/workspace","host":{"ideBuild":"test","kotlinBuild":"test"},"supertype":${declaration("example.Parent")},"inheritor":${declaration("example.Child")}}"""
        assertTrue(ExistingIdeDocuments.response(valid.toByteArray(), root, operation, descriptor) is ExistingIdeExchange.Received)
        for (invalid in listOf(valid.replace("example.Child", "other.Child"), valid.replace("/workspace", "/other"), valid.replace("RESULT_DETACHED", "SEMANTIC_READ"))) {
            assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), ExistingIdeDocuments.response(invalid.toByteArray(), root, operation, descriptor))
        }
    }

    @Test fun `schema admits empty index evidence and rejects wrong names roots and duplicate fields`() {
        val operation = ExistingIdeOperation.Classes((ExistingIdeClassName.parse("Absent") as Refinement.Refined).value)
        val valid = """{"schemaVersion":1,"outcome":"published","publication":"request_local_same_source_epoch","content":"saved_committed_ide_vfs","scope":"cached_gradle_source_folders","kind":"classes","stage":"RESULT_DETACHED","workspaceRoot":"/workspace","host":{"ideBuild":"test","kotlinBuild":"test"},"name":"Absent","indexAuthority":"existing_ide_kotlin_stub_index","declarations":[]}"""
        assertTrue(ExistingIdeDocuments.response(valid.toByteArray(), root, operation, descriptor) is ExistingIdeExchange.Received)
        for (invalid in listOf(valid.replace("Absent", "Different"), valid.replace("/workspace", "/other"), valid.replace("RESULT_DETACHED", "SEMANTIC_READ"),
            valid.replace("\"name\":", "\"name\":\"Ignored\",\"name\":"), valid + "{}")) {
            assertEquals(ExistingIdeExchange.Rejected(ExistingIdeFailure.RESPONSE_REJECTED), ExistingIdeDocuments.response(invalid.toByteArray(), root, operation, descriptor))
        }
    }

    @Test fun `status binds the descriptor host identity and rejects unknown capabilities`() {
        val valid = """{"type":"KAST_IDE_HOST","protocol":1,"root":"/workspace","hostPid":123,"operations":["DESCRIBE","CLASS_LOOKUP","DIRECT_SUPERTYPE"],"indexAuthority":"existing_ide_kotlin_stub_index"}"""
        assertTrue(ExistingIdeDocuments.response(valid.toByteArray(), root, ExistingIdeOperation.Status, descriptor) is ExistingIdeExchange.Received)
        for (invalid in listOf(valid.replace("123", "124"), valid.replace("CLASS_LOOKUP", "IMPORT"), valid.replace("KAST_IDE_HOST", "KAST_IDE_ENDPOINT"))) {
            assertTrue(ExistingIdeDocuments.response(invalid.toByteArray(), root, ExistingIdeOperation.Status, descriptor) is ExistingIdeExchange.Rejected)
        }
    }
}
