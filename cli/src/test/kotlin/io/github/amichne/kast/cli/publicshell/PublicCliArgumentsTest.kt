package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.query.PublicToolContract
import io.github.amichne.kast.cli.direct.selectDirectCatalog
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolCatalog
import io.github.amichne.kast.protocol.registry.PublicToolIdentity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicCliArgumentsTest {
    @Test
    fun `flag query equals an independent full argument document`() {
        val command = query("query", "--name", "OrderService")
        assertEquals(PublicToolCatalog.COMPLETE, command.catalog)
        val expected = oracleJson.encodeToJsonElement(OracleEnvelope(OracleRun()))
        val actual = arguments(command)
        assertEquals(expected, actual)
        assertEquals(admit(expected), admit(actual))
    }

    @Test
    fun `search source preserves name scope and kinds`() {
        val search =
            arguments(
                query(
                    "query",
                    "--name",
                    "OrderService",
                    "--in",
                    "services/orders",
                    "--source-set",
                    "main",
                    "--kind",
                    "class",
                    "--kind",
                    "function",
                    "--name-match",
                    "fuzzy",
                )
            )
        val source = search.getValue("request").jsonObject.getValue("source").jsonObject
        assertEquals("SEARCH_DECLARATIONS", source.getValue("type").jsonPrimitive.content)
        assertEquals("FUZZY", source.getValue("nameMatch").jsonPrimitive.content)
        assertEquals(
            listOf("CLASS", "FUNCTION"),
            source.getValue("declarationKinds").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "services/orders",
            source.getValue("scope").jsonObject.getValue("relativeDirectoryPath").jsonPrimitive.content,
        )
        assertTrue(admission(search) is Refinement.Refined)
    }

    @Test
    fun `source set without directory uses repository root`() {
        val root = arguments(query("query", "--name", "OrderService", "--source-set", "main"))
        assertEquals(
            ".",
            root
                .getValue("request")
                .jsonObject
                .getValue("source")
                .jsonObject
                .getValue("scope")
                .jsonObject
                .getValue("relativeDirectoryPath")
                .jsonPrimitive
                .content,
        )
    }

    @Test
    fun `reference source preserves relation order`() {
        val refs = relationQuery("callers", "callees", "callers")
        val request = refs.getValue("request").jsonObject
        assertEquals(
            listOf("CALLERS", "CALLEES", "CALLERS"),
            request.getValue("steps").jsonArray.map { it.jsonObject.getValue("relation").jsonPrimitive.content },
        )
        assertEquals(
            listOf("exact:v5:AAAAAAAAAAAAAAAAAAAAAA", "exact:v5:AAAAAAAAAAAAAAAAAAAAAQ"),
            request.getValue("source").jsonObject.getValue("symbolRefs").jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(admission(refs) is Refinement.Refined)
        val reversed = relationQuery("callees", "callers", "callers")
        assertNotEquals(admit(refs), admit(reversed))
    }

    @Test
    fun `location source preserves the file and offset`() {
        val location = arguments(query("query", "--file", "src/Main.kt", "--offset", "12"))
        assertEquals(
            "AT_LOCATION",
            location
                .getValue("request")
                .jsonObject
                .getValue("source")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            "12",
            location
                .getValue("request")
                .jsonObject
                .getValue("source")
                .jsonObject
                .getValue("offset")
                .jsonPrimitive
                .content,
        )
        assertTrue(admission(location) is Refinement.Refined)
    }

    @Test
    fun `absent empty and selected fields remain different`() {
        val absent = arguments(query("query", "--name", "OrderService"))
        assertFalse(absent.getValue("request").jsonObject.getValue("terminalStep").jsonObject.containsKey("output"))
        val empty = arguments(query("query", "--name", "OrderService", "--fields="))
        assertEquals(emptyList<String>(), fields(empty))
        val selected = arguments(query("query", "--name", "OrderService", "--fields", "signature,name"))
        assertEquals(listOf("SIGNATURE", "NAME"), fields(selected))
        val explicitSymbols = arguments(query("query", "--name", "OrderService", "--output", "symbols"))
        assertEquals(listOf("NAME", "LOCATION"), fields(explicitSymbols))
        assertEquals(canonical(absent), canonical(explicitSymbols))
        assertNotEquals(canonical(absent), canonical(empty))
        listOf(absent, empty, selected, explicitSymbols).forEach { assertTrue(admission(it) is Refinement.Refined) }
    }

    @Test
    fun `invalid command combinations reject in the pure adapter`() {
        val cases =
            listOf(
                listOf("query"),
                listOf("query", "--name", "A", "--name", "B"),
                listOf("query", "--name", "A", "--ref", "R"),
                listOf("query", "--file", "A.kt"),
                listOf("query", "--ref", "R", "--in", "."),
                listOf("query", "--file", "A.kt", "--offset", "1", "--kind", "class"),
                listOf("query", "--name", "A", "--fields", "unknown"),
                listOf("query", "--name", "A", "--fields", "name,name"),
                listOf("query", "--name", "A", "--fields", "name,"),
                listOf("query", "--name", "A", "--fields", "Name"),
                listOf("query", "--name", "A", "--output", "occurrences", "--fields="),
                listOf("query", "--name", "A", "--terminal", "trace", "--output", "occurrences"),
                listOf("query", "--request", "-", "--verbose"),
                listOf("query", "--request", "x.json", "--name", "A"),
                listOf("query", "--name", "A", "--max-results", "x"),
                listOf("query", "--name", "A", "--catalog", "COMPLETE"),
                listOf("source", "read", "--ref", "R", "--max-elapsed-ms", "1"),
                listOf("result", "read", "--ref", "R", "--ref", "S"),
                listOf("result", "resume", "--request", "x.json"),
                listOf("describe", "--workspace", "."),
                listOf("describe", "--section", "input"),
                listOf("query", "--bogus"),
            )
        cases.forEach { assertTrue(parsePublicCliArguments(it) is Refinement.Rejected, it.toString()) }
    }

    @Test
    fun `catalog limits and list uniqueness remain owned by public admission`() {
        val common = query("query", "--catalog", "common", "--name", "A", "--max-elapsed-ms", "9")
        assertEquals(PublicToolCatalog.COMMON, common.catalog)
        assertTrue(admission(arguments(common), common.catalog) is Refinement.Rejected)
        val complete = query("query", "--name", "A", "--max-elapsed-ms", "9")
        assertTrue(admission(arguments(complete), complete.catalog) is Refinement.Refined)
        for (flags in
            listOf(
                listOf("--kind", "class", "--kind", "class"),
                listOf("--source-set", "main", "--source-set", "main"),
                listOf("--max-returned-bytes", "4095"),
            )) {
            val command = query(*(listOf("query", "--name", "A") + flags).toTypedArray())
            assertTrue(admission(arguments(command)) is Refinement.Rejected)
        }
        val request = arguments(query("query", "--name", "A")).getValue("request").jsonObject
        assertFalse(request.containsKey("executionBudget"))
        assertFalse(request.containsKey("executionMode"))
    }

    @Test
    fun `public complete default does not change the private common default`() {
        assertEquals(PublicToolCatalog.COMPLETE, query("query", "--name", "A").catalog)
        for (privateCommand in listOf(listOf("catalog"), listOf("call", "query_symbols"), listOf("query-session"))) {
            val privateSelection = (selectDirectCatalog(privateCommand) as Refinement.Refined).value
            assertEquals(PublicToolCatalog.COMMON, privateSelection.catalog)
            assertEquals(privateCommand, privateSelection.command)
        }
    }

    @Test
    fun `raw input stays explicit and help does not read it`() {
        assertEquals(PublicCliInput.Stdin, query("query", "--request", "-").input)
        assertEquals(PublicCliInput.File("a b.json"), query("query", "--request=a b.json").input)
        assertEquals(
            PublicCliWorkspace.Selected("a b"),
            query("--workspace", "a b", "query", "--request", "-", "--json").workspace,
        )
        assertEquals(
            Refinement.Refined(PublicCliCommand.Help(PublicCliCommandId.QUERY)),
            parsePublicCliArguments(listOf("query", "--request", "-", "--help")),
        )
        assertTrue(
            (parsePublicCliArguments(listOf("query", "plan", "--request", "-")) as Refinement.Refined).value
                is PublicCliCommand.Plan
        )
    }

    @Test
    fun `read aliases preserve separate cursors and source choices`() {
        val result =
            arguments(
                query(
                    "result",
                    "read",
                    "--ref",
                    RESULT_REFERENCE,
                    "--cursor",
                    "7",
                    "--evidence-cursor",
                    "11",
                    "--fields=",
                )
            )
        val action = result.getValue("request").jsonObject
        assertEquals("READ_RESULT", action.getValue("type").jsonPrimitive.content)
        assertEquals(RESULT_REFERENCE, action.getValue("result").jsonPrimitive.content)
        assertEquals("7", action.getValue("cursor").jsonPrimitive.content)
        assertEquals("11", action.getValue("evidence_cursor").jsonPrimitive.content)
        assertTrue(admission(result) is Refinement.Refined)
        val resume = arguments(query("result", "resume", "--continuation", OUTPUT_CONTINUATION))
        assertTrue(admission(resume) is Refinement.Refined)
        val semanticContinuation =
            arguments(query("result", "resume", "--continuation", "query:v1:00000000-0000-0000-0000-000000000000"))
        assertTrue(admission(semanticContinuation) is Refinement.Rejected)
        val source =
            arguments(
                query(
                    "source",
                    "read",
                    "--ref",
                    "exact:v5:AAAAAAAAAAAAAAAAAAAAAA",
                    "--region",
                    "enclosing-callable-body",
                    "--text",
                    "none",
                )
            )
        val sourceAction = source.getValue("request").jsonObject
        assertEquals("ENCLOSING_CALLABLE_BODY", sourceAction.getValue("region").jsonPrimitive.content)
        assertEquals("NONE", sourceAction.getValue("text").jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(admission(source) is Refinement.Refined)
    }

    @Test
    fun `baseline admission preserves caller elapsed grant and minimum output allowance`() {
        val identity = PublicToolIdentity.QUERY_SYMBOLS
        val json = Json {
            encodeDefaults = true
            explicitNulls = false
        }
        val explicitGrant =
            json.encodeToJsonElement(OracleEnvelope(OracleRun(executionBudget = OracleBudget(maxElapsedMs = 9))))
        assertTrue(PublicToolContract.admit(identity, explicitGrant, PublicToolCatalog.COMPLETE) is Refinement.Refined)
        assertTrue(PublicToolContract.admit(identity, explicitGrant, PublicToolCatalog.COMMON) is Refinement.Rejected)
        val tooSmall =
            json.encodeToJsonElement(OracleEnvelope(OracleRun(executionBudget = OracleBudget(maxReturnedBytes = 4095))))
        val minimum =
            json.encodeToJsonElement(OracleEnvelope(OracleRun(executionBudget = OracleBudget(maxReturnedBytes = 4096))))
        assertTrue(PublicToolContract.admit(identity, tooSmall) is Refinement.Rejected)
        assertTrue(PublicToolContract.admit(identity, minimum) is Refinement.Refined)
    }
}

private val oracleJson = Json {
    encodeDefaults = true
    explicitNulls = false
}
private const val RESULT_REFERENCE = "result:v1:00000000-0000-0000-0000-000000000000"
private const val OUTPUT_CONTINUATION = "query-output:v1:00000000-0000-0000-0000-000000000000"

private fun relationQuery(vararg relations: String) =
    arguments(
        query(
            *(listOf("query", "--ref", "exact:v5:AAAAAAAAAAAAAAAAAAAAAA", "--ref", "exact:v5:AAAAAAAAAAAAAAAAAAAAAQ") +
                    relations.flatMap { listOf("--relation", it) })
                .toTypedArray()
        )
    )

private fun query(vararg argv: String): PublicCliCommand.Query =
    (parsePublicCliArguments(argv.toList()) as Refinement.Refined).value as PublicCliCommand.Query

private fun arguments(query: PublicCliCommand.Query) = (query.input as PublicCliInput.Lowered).arguments

private fun admission(
    input: kotlinx.serialization.json.JsonElement,
    catalog: PublicToolCatalog = PublicToolCatalog.COMPLETE,
) = PublicToolContract.admit(PublicToolIdentity.QUERY_SYMBOLS, input, catalog)

private fun admit(input: kotlinx.serialization.json.JsonElement) =
    PublicToolContract.encode((admission(input) as Refinement.Refined).value)

private fun canonical(input: kotlinx.serialization.json.JsonElement) =
    (admission(input) as Refinement.Refined).value.canonical

private fun fields(input: kotlinx.serialization.json.JsonObject) =
    input
        .getValue("request")
        .jsonObject
        .getValue("terminalStep")
        .jsonObject
        .getValue("output")
        .jsonObject
        .getValue("fields")
        .jsonArray
        .map { it.jsonPrimitive.content }

@Serializable private data class OracleEnvelope(val request: OracleRun)

@Serializable
private data class OracleRun(
    val type: String = "RUN",
    val source: OracleSource = OracleSource(),
    val terminalStep: OracleTerminal = OracleTerminal(),
    val executionBudget: OracleBudget? = null,
)

@Serializable
private data class OracleSource(val type: String = "SEARCH_DECLARATIONS", val declarationName: String = "OrderService")

@Serializable private data class OracleTerminal(val type: String = "EMIT")

@Serializable private data class OracleBudget(val maxElapsedMs: Long? = null, val maxReturnedBytes: Long? = null)
