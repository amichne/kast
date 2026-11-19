package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.AdmittedPublicInvocation
import io.github.amichne.kast.appserver.PublicInvocationInput
import io.github.amichne.kast.appserver.PublicInvocationProtocol
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CoordinatorPublicInvocationsTest {
    @Test
    fun `a relative request file resolves against the caller's directory, not the coordinator's`(
        @TempDir caller: Path
    ) {
        // The coordinator's own working directory never holds the caller's file.
        assertNotEquals(Path.of("").toAbsolutePath(), caller)
        val planned = preparedPlanRequest()
        Files.writeString(caller.resolve("request.json"), planned)
        val outcome =
            CoordinatorPublicInvocations(caller.resolve("unused-product"))
                .invoke(
                    invocation(caller, listOf("query", "plan", "--request", "request.json", "--json")),
                    { _, _ -> error("request planning reaches no semantic client") },
                    noInput,
                )
        assertEquals(PublicShellExit.COMPLETE.code, outcome.exitCode, outcome.output)
        assertEquals(
            "planned",
            Json.parseToJsonElement(outcome.output).jsonObject.getValue("type").jsonPrimitive.content,
        )
    }

    @Test
    fun `hook namespaces are refused because they read the coordinator's own process state`(@TempDir caller: Path) {
        for (namespace in listOf("hook", "workspace")) {
            val outcome =
                CoordinatorPublicInvocations(caller)
                    .invoke(
                        invocation(caller, listOf(namespace, "session-start")),
                        { _, _ -> error("a refused hook reaches no semantic client") },
                        { error("a refused hook reads no input") },
                    )
            assertEquals(PublicShellExit.INPUT.code, outcome.exitCode)
            assertEquals(
                "rejected",
                Json.parseToJsonElement(outcome.output).jsonObject.getValue("type").jsonPrimitive.content,
            )
            assertTrue(outcome.diagnostics.single().contains("not served by the coordinator"))
        }
    }

    @Test
    fun `stdin is read only when the admitted command selects it`(@TempDir caller: Path) {
        var reads = 0
        val input = PublicInvocationInput { maximum ->
            reads++
            assertTrue(maximum > 0)
            preparedPlanRequest().toByteArray()
        }
        val products = CoordinatorPublicInvocations(caller)
        val flags =
            products.invoke(
                invocation(caller, listOf("query", "plan", "--name", "StringFeed", "--json")),
                unused,
                input,
            )
        assertEquals(PublicShellExit.COMPLETE.code, flags.exitCode)
        assertEquals(0, reads)
        val stdin =
            products.invoke(invocation(caller, listOf("query", "plan", "--request", "-", "--json")), unused, input)
        assertEquals(PublicShellExit.COMPLETE.code, stdin.exitCode, stdin.output)
        assertEquals(1, reads)
    }

    @Test
    fun `the caller's TOON setting selects the format and an invalid setting is an input failure`(
        @TempDir caller: Path
    ) {
        val products = CoordinatorPublicInvocations(caller)
        val arguments = listOf("query", "plan", "--name", "StringFeed")
        val json = products.invoke(invocation(caller, arguments), unused, noInput)
        val toon = products.invoke(invocation(caller, arguments, mapOf("KAST_OUTPUT_TOON" to "1")), unused, noInput)
        val invalid =
            products.invoke(invocation(caller, arguments, mapOf("KAST_OUTPUT_TOON" to "maybe")), unused, noInput)
        assertTrue(json.output.startsWith("{"))
        assertTrue(!toon.output.startsWith("{") && toon.output.contains("type: planned"), toon.output)
        assertEquals(PublicShellExit.INPUT.code, invalid.exitCode)
    }

    @Test
    fun `frontend catalog declares the served protocol, the published statuses and only help vectors`() {
        val catalog = PublicFrontendCatalog.document(listOf("lib/kast-cli.jar"))
        assertEquals(PublicInvocationProtocol.version, catalog.invocationProtocol)
        assertEquals(PublicShellExit.entries.associate { it.name to it.code }, catalog.exits)
        assertEquals(9, catalog.exits.getValue("BOOTSTRAP"))
        assertEquals(6, catalog.exits.getValue("PROTOCOL"))
        assertTrue(!Path.of(catalog.socket).isAbsolute)
        assertTrue(catalog.help.isNotEmpty())
        for (entry in catalog.help) {
            val parsed =
                (parsePublicCliArguments(entry.arguments) as io.github.amichne.kast.kernel.Refinement.Refined).value
            assertTrue(
                parsed is PublicCliCommand.Help || parsed is PublicCliCommand.NamespaceHelp,
                entry.arguments.toString(),
            )
        }
        assertTrue(catalog.help.none { it.arguments.first() in setOf("hook", "workspace") })
        assertEquals(10_000L, catalog.handshakeMilliseconds)
        assertEquals("KAST_OUTPUT_TOON", catalog.outputSetting.variable)
        assertEquals(listOf("", "0", "1"), catalog.outputSetting.admittedValues)
        assertEquals("lib/control.jsa", catalog.control.sharedArchive)
        // Training must be help: it reads no installation state, so the installer can run it in staging.
        assertEquals(listOf("--internal-entrypoint=management", "--help"), catalog.control.archiveTraining)
    }

    @Test
    fun `help under an unadmitted output setting is the shell's input rejection, as the frontend forwards it`(
        @TempDir caller: Path
    ) {
        val help = PublicFrontendCatalog.document(listOf("lib/kast-cli.jar")).help.first()
        val rejected =
            CoordinatorPublicInvocations(caller)
                .invoke(invocation(caller, help.arguments, mapOf("KAST_OUTPUT_TOON" to "yes")), unused, noInput)
        assertEquals(PublicShellExit.INPUT.code, rejected.exitCode)
        for (value in PublicFrontendCatalog.document(listOf("lib/kast-cli.jar")).outputSetting.admittedValues) {
            val answered =
                CoordinatorPublicInvocations(caller)
                    .invoke(invocation(caller, help.arguments, mapOf("KAST_OUTPUT_TOON" to value)), unused, noInput)
            assertEquals(PublicShellExit.COMPLETE.code to help.text, answered.exitCode to answered.output)
        }
    }

    /** The authored schema's first query example, as the public shell's own describe command serves it. */
    private fun preparedPlanRequest(): String {
        val reply =
            CoordinatorPublicInvocations(Path.of("/"))
                .invoke(
                    invocation(Path.of("/"), listOf("describe", "query", "--section", "examples", "--json")),
                    unused,
                    noInput,
                )
        val example = Json.parseToJsonElement(reply.output).jsonObject.getValue("content").jsonArray.first()
        return example.jsonObject.getValue("value").jsonObject.getValue("request").toString()
    }

    private fun invocation(directory: Path, arguments: List<String>, environment: Map<String, String> = emptyMap()) =
        checkNotNull(AdmittedPublicInvocation.admit(directory, arguments, environment))

    private val unused: (Path, Map<String, String>) -> io.github.amichne.kast.appserver.DaemonOperationClient =
        { _, _ ->
            error("request planning reaches no semantic client")
        }

    private val noInput = PublicInvocationInput { error("no stdin expected") }
}
