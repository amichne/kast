package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement
import java.io.ByteArrayInputStream
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublicJqPolicyTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `projection options coexist with stdin without entering the semantic request`() {
        val command =
            parse("query", "plan", "--request", "-", "--jq", ".request", "--jq-max-bytes", "4096")
                as PublicCliCommand.Plan
        assertEquals(PublicCliInput.Stdin, command.input)
        val projection = command.projection as PublicOutputProjection.Jq
        assertEquals(".request", projection.options.program)
        assertEquals(4096, projection.options.limits.bytes)
        val plain = parse("query", "plan", "--name", "Order") as PublicCliCommand.Plan
        val filtered = parse("query", "plan", "--name", "Order", "--jq", ".") as PublicCliCommand.Plan
        assertEquals(plain.input, filtered.input)
    }

    @Test
    fun `invalid companion options help filters and duplicate programs reject`() {
        val base = listOf("query", "plan", "--name", "Order")
        for (suffix in
            listOf(
                listOf("--jq-executable", "/bin/jq"),
                listOf("--jq", ""),
                listOf("--jq", "\uD800"),
                listOf("--jq", ".", "--jq-timeout-ms", "0"),
                listOf("--jq", ".", "--jq-max-bytes", "67108865"),
                listOf("--jq", ".", "--jq-executable", "./jq"),
                listOf("--jq", ".", "--help"),
                listOf("--jq", ".", "--jq", "."),
            )) {
            assertTrue(parsePublicCliArguments(base + suffix) is Refinement.Rejected, suffix.toString())
        }
    }

    @Test
    fun `syntax rejection occurs before workspace composition without exposing stderr payloads`() {
        val executor = scripted(listOf(PublicJqCapture(21, "")))
        val prepared =
            preparePublicCli(
                listOf("query", "--name", "Order", "--jq", ".["),
                ByteArrayInputStream(byteArrayOf()),
                temporary,
                session = { _, _ -> error("workspace effects forbidden") },
                jqExecutor = executor,
            )
        assertEquals(PublicShellExit.INPUT, prepared.exit)
        assertTrue(prepared.text.contains("PROGRAM_INVALID"), prepared.text)
        assertTrue(prepared.text.contains("COMPILE"), prepared.text)
        executor.settled()
    }

    @Test
    fun `single result preserves null false nested arrays and numerical tokens`() {
        for (value in
            listOf(
                "null",
                "false",
                "9223372036854775807",
                "1.2345678901234567890123456789",
                Json.encodeToString(listOf(listOf(1, 2), emptyList())),
            )) {
            val executor = scripted(listOf(PublicJqCapture(0, ""), PublicJqCapture(0, "$value\n")))
            val result = engine(executor).project(PublicPreparedOutput("null", PublicShellExit.COMPLETE))
            assertTrue(result is Refinement.Refined)
            val prepared = (result as Refinement.Refined).value
            assertEquals(value, prepared.text)
            assertTrue(prepared.content is PublicOutputContent.Projection)
            executor.settled()
        }
    }

    @Test
    fun `empty and multiple streams reject and late runtime errors discard their prefix`() {
        for ((capture, cause) in
            listOf(
                PublicJqCapture(23, "") to PublicJqFailure.OUTPUT_CARDINALITY,
                PublicJqCapture(23, "") to PublicJqFailure.OUTPUT_CARDINALITY,
                PublicJqCapture(22, "1\n") to PublicJqFailure.EVALUATION_FAILED,
                PublicJqCapture(0, "invalid") to PublicJqFailure.OUTPUT_INVALID,
            )) {
            val executor = scripted(listOf(PublicJqCapture(0, ""), capture))
            val result = engine(executor).project(PublicPreparedOutput("null", PublicShellExit.COMPLETE))
            assertEquals(cause, (result as Refinement.Rejected).failure.failure)
            executor.settled()
        }
    }

    @Test
    fun `nonzero statuses bypass evaluation and preserve the original evidence object`() {
        for (exit in
            listOf(
                PublicShellExit.WORKSPACE,
                PublicShellExit.RUNTIME,
                PublicShellExit.SEMANTIC,
                PublicShellExit.DELIVERY,
                PublicShellExit.ELAPSED,
            )) {
            val executor = scripted(listOf(PublicJqCapture(0, "")))
            val original = PublicPreparedOutput("original failure evidence", exit)
            assertSame(original, (engine(executor).project(original) as Refinement.Refined).value)
            executor.settled()
        }
    }

    @Test
    fun `final TOON bytes include indentation growth and the trailing newline`() {
        var value = NestedProjection(value = "x")
        repeat(20) { value = NestedProjection(child = value) }
        val document = Json.encodeToString(value)
        val command =
            parse(
                "query",
                "plan",
                "--name",
                "Order",
                "--jq",
                ".",
                "--jq-max-bytes",
                (document.toByteArray().size + 1).toString(),
            )
        val limits = (command.projection as PublicOutputProjection.Jq).options.limits
        val prepared = PublicPreparedOutput(document, PublicShellExit.COMPLETE, PublicOutputContent.Projection(limits))
        val rendered =
            presentPublicOutput(
                prepared,
                io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat.TOON,
            )
        assertEquals(PublicShellExit.IO, rendered.exit)
        assertTrue(rendered.text.contains("BYTE_LIMIT"), rendered.text)
    }

    @Test
    fun `filter and presentation failures preserve retained occurrence recovery without source payloads`() {
        val fixture = checkNotNull(javaClass.getResource("/query-presentation-native.json")).readText()
        val reply =
            Json.parseToJsonElement(fixture)
                .jsonObject
                .getValue("cases")
                .jsonObject
                .getValue("occurrences")
                .jsonObject
                .getValue("reply")
        val original =
            preparePublicReply(
                io.github.amichne.kast.cli.rpc.ToolRpcReply.Complete(reply.jsonObject.getValue("document").jsonObject)
            )
        assertEquals(PublicShellExit.COMPLETE, original.exit)
        val failed = scripted(listOf(PublicJqCapture(0, ""), PublicJqCapture(22, "1\n")))
        val rejection = (engine(failed).project(original) as Refinement.Rejected).failure
        assertEquals("result:v1:c9f421f8-a7c1-4b38-b737-21fd8f7e2fc1", rejection.recovery?.request?.result?.value)
        assertEquals(
            io.github.amichne.kast.protocol.wire.presentation.QueryCompletionPublicReadOutput.Occurrences,
            rejection.recovery?.request?.output,
        )
        assertTrue(rejection.prepared().text.contains("READ_RESULT"))
        assertTrue(!rejection.prepared().text.contains("live"))
        failed.settled()

        val decimal =
            scripted(
                listOf(
                    PublicJqCapture(0, ""),
                    PublicJqCapture(0, "1.2345678901234567890123456789\n"),
                )
            )
        val projected = (engine(decimal).project(original) as Refinement.Refined).value
        val rendered =
            presentPublicOutput(
                projected,
                io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat.TOON,
            )
        assertEquals(PublicShellExit.PROTOCOL, rendered.exit)
        assertTrue(rendered.text.contains("OUTPUT_VALUE_LOSS"))
        assertTrue(rendered.text.contains("result:v1:c9f421f8-a7c1-4b38-b737-21fd8f7e2fc1"))
        decimal.settled()
    }

    private fun engine(executor: ScriptedJq): PublicJqEngine {
        val command = parse("query", "plan", "--name", "Order", "--jq", ".")
        val options = (command.projection as PublicOutputProjection.Jq).options
        return (PublicJqEngine.prepare(options, temporary, executor) as Refinement.Refined).value
    }

    private fun parse(vararg words: String): PublicCliCommand =
        (parsePublicCliArguments(words.toList()) as Refinement.Refined).value

    private fun scripted(captures: List<PublicJqCapture>) = ScriptedJq(captures)
}

@Serializable private data class NestedProjection(val child: NestedProjection? = null, val value: String? = null)

private class ScriptedJq(private val captures: List<PublicJqCapture>) : PublicJqExecutor {
    private var index = 0

    override fun execute(request: PublicJqProcessRequest): Refinement<PublicJqCapture, PublicJqRejection> {
        check(index < captures.size) { "unexpected jq call: ${request.stage}" }
        assertEquals(listOf(PublicJqStage.COMPILE, PublicJqStage.FILTER)[index], request.stage)
        if (request.stage == PublicJqStage.COMPILE) assertEquals("", request.input)
        return Refinement.Refined(captures[index++])
    }

    fun settled() = assertEquals(captures.size, index)
}
