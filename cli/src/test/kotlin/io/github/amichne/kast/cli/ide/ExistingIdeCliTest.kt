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

    @Test fun `hosted help resolves without root or socket effects`() {
        val roots = CanonicalRootDiscoverer { fail("Help attempted root discovery") }
        val client = ExistingIdeClient { _, _ -> fail("Help attempted socket access") }
        for (argv in listOf(listOf("ide", "--help"), listOf("ide", "classes", "--help"), listOf("ide", "status", "--help"))) {
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
