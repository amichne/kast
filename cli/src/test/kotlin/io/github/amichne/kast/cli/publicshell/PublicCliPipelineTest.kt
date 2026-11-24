package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.Refinement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublicCliPipelineTest {
    @Test
    fun `pipeline flags lower to admitted stages in argv order`() {
        val other = "result:v1:00000000-0000-0000-0000-000000000001"
        val argv =
            listOf(
                "query",
                "--all",
                "--kind",
                "function",
                "--in",
                "services/orders",
                "--source-set",
                "main",
                "--visibility",
                "public,internal",
                "--relation",
                "callers",
                "--without",
                RESULT_REFERENCE,
                "--intersect",
                other,
                "--minus",
                RESULT_REFERENCE,
                "--union",
                other,
                "--with",
                RESULT_REFERENCE,
            )
        val lowered = arguments(query(*argv.toTypedArray()))
        assertEquals(expectedPipeline(other), lowered)
        assertTrue(admission(lowered) is Refinement.Refined, "pipeline must admit: ${admission(lowered)}")
    }

    private fun expectedPipeline(other: String) =
        oracleJson.encodeToJsonElement(
            OraclePipelineEnvelope(
                OraclePipelineRun(
                    source =
                        OracleAllSource(
                            declarationKinds = listOf("FUNCTION"),
                            scope = OracleDirectory("services/orders", listOf("main")),
                        ),
                    steps =
                        listOf(
                            OracleStep(
                                "WHERE",
                                predicate = OracleVisibility(values = listOf("PUBLIC", "INTERNAL")),
                            ),
                            OracleStep("EXPAND_RELATION", relation = "CALLERS"),
                            OracleStep("JOIN", mode = OracleMode("ANTI"), right = OracleRight(RESULT_REFERENCE)),
                            OracleStep("INTERSECT", right = OracleRight(other)),
                            OracleStep("DIFFERENCE", right = OracleRight(RESULT_REFERENCE)),
                            OracleStep("UNION", right = OracleRight(other)),
                            OracleStep("JOIN", mode = OracleMode("SEMI"), right = OracleRight(RESULT_REFERENCE)),
                        ),
                )
            )
        )

    @Test
    fun `difference before relation preserves stage order and retained source`() {
        val other = "result:v1:00000000-0000-0000-0000-000000000001"
        val swapped = arguments(query("query", "--from", RESULT_REFERENCE, "--minus", other, "--relation", "callers"))
        val swappedSteps = swapped.getValue("request").jsonObject.getValue("steps").jsonArray
        assertEquals(
            listOf("DIFFERENCE", "EXPAND_RELATION"),
            swappedSteps.map { it.jsonObject.getValue("type").jsonPrimitive.content },
        )
        val source = swapped.getValue("request").jsonObject.getValue("source").jsonObject
        assertEquals("RESULT", source.getValue("type").jsonPrimitive.content)
        assertEquals(RESULT_REFERENCE, source.getValue("result").jsonPrimitive.content)
        assertTrue(admission(swapped) is Refinement.Refined)
    }

    @Test
    fun `library flag lowers to an exact package and name without a source scope`() {
        val lowered =
            arguments(
                query(
                    "query",
                    "--library",
                    "kotlin.collections",
                    "--name",
                    "listOfNotNull",
                    "--relation",
                    "references",
                )
            )
        val source = lowered.getValue("request").jsonObject.getValue("source").jsonObject
        assertEquals(setOf("type", "declarationName", "packageName"), source.keys)
        assertEquals("SEARCH_LIBRARY", source.getValue("type").jsonPrimitive.content)
        assertEquals("kotlin.collections", source.getValue("packageName").jsonPrimitive.content)
        assertTrue(admission(lowered) is Refinement.Refined)
    }

    @Test
    fun `pipeline flags reject ambiguous sources and invalid values`() {
        listOf(
                listOf("query", "--library", "kotlin"),
                listOf("query", "--library", "kotlin", "--name", "to", "--in", "."),
                listOf("query", "--library", "kotlin", "--name", "to", "--source-set", "main"),
                listOf("query", "--library", "kotlin", "--name", "to", "--kind", "function"),
                listOf("query", "--library", "kotlin", "--name", "to", "--name-match", "fuzzy"),
                listOf("query", "--library", "kotlin", "--all"),
                listOf("query", "--all", "--name", "A"),
                listOf("query", "--from", RESULT_REFERENCE, "--ref", "R"),
                listOf("query", "--from", RESULT_REFERENCE, "--in", "."),
                listOf("query", "--all", "--name-match", "fuzzy"),
                listOf("query", "--all", "--kind", "enum"),
                listOf("query", "--all", "--visibility", "public,public"),
                listOf("query", "--all", "--visibility", "Public"),
                listOf("query", "--all", "--visibility", "public,"),
                listOf("query", "--from", RESULT_REFERENCE, "--from", RESULT_REFERENCE),
                listOf("result", "read", "--ref", RESULT_REFERENCE, "--minus", RESULT_REFERENCE),
            )
            .forEach { argv ->
                assertTrue(parsePublicCliArguments(argv) is Refinement.Rejected, "must reject: $argv")
            }
    }

    @Test
    fun `relation destination flags restrict every hop`() {
        val lowered =
            arguments(
                query(
                    "query",
                    "--all",
                    "--in",
                    "legacy",
                    "--relation",
                    "references",
                    "--relation-in",
                    "app",
                    "--relation-source-set",
                    "main",
                    "--relation",
                    "callers",
                )
            )
        val domain = OracleSourceDomain(sourceSets = listOf("main"), directory = "app")
        val steps = lowered.getValue("request").jsonObject.getValue("steps")
        assertEquals(
            oracleJson.encodeToJsonElement(
                listOf(
                    OracleRelationStep(relation = "REFERENCES", expansionScope = domain),
                    OracleRelationStep(relation = "CALLERS", expansionScope = domain),
                )
            ),
            steps,
        )
        assertTrue(admission(lowered) is Refinement.Refined, "domain must admit: ${admission(lowered)}")
    }

    @Test
    fun `trace includes main and test sources by default`() {
        val defaults =
            arguments(
                query(
                    "query",
                    "--ref",
                    "exact:v5:AAAAAAAAAAAAAAAAAAAAAA",
                    "--terminal",
                    "trace",
                    "--relation-in",
                    "app",
                )
            )
        assertEquals(
            oracleJson.encodeToJsonElement(OracleSourceDomain(sourceSets = listOf("main", "test"), directory = "app")),
            defaults.getValue("request").jsonObject.getValue("terminalStep").jsonObject.getValue("expansionScope"),
        )
        assertTrue(admission(defaults) is Refinement.Refined)
    }

    @Test
    fun `relation excludes test and generated sources on request`() {
        val narrowed =
            arguments(
                query(
                    "query",
                    "--ref",
                    "exact:v5:AAAAAAAAAAAAAAAAAAAAAA",
                    "--relation",
                    "callers",
                    "--relation-production-only",
                    "--relation-exclude-generated",
                )
            )
        assertEquals(
            oracleJson.encodeToJsonElement(
                OracleSourceDomain(
                    sourceSets = listOf("main", "test"),
                    directory = null,
                    sourcePolicy = "PRODUCTION_ONLY",
                    generatedSources = "EXCLUDE",
                )
            ),
            narrowed
                .getValue("request")
                .jsonObject
                .getValue("steps")
                .jsonArray
                .single()
                .jsonObject
                .getValue("expansionScope"),
        )
        assertTrue(admission(narrowed) is Refinement.Refined)
    }

    @Test
    fun `relation omits the expansion scope without destination flags`() {
        val unscoped = arguments(query("query", "--ref", "exact:v5:AAAAAAAAAAAAAAAAAAAAAA", "--relation", "callers"))
        assertFalse(
            "expansionScope" in unscoped.getValue("request").jsonObject.getValue("steps").jsonArray.single().jsonObject
        )
    }

    @Test
    fun `relation destination flags reject without a relation or trace`() {
        listOf(
                listOf("query", "--name", "A", "--relation-in", "app"),
                listOf("query", "--all", "--relation-source-set", "main"),
                listOf("query", "--name", "A", "--relation-production-only"),
                listOf("query", "--name", "A", "--relation", "callers", "--relation-in", "a", "--relation-in", "b"),
                listOf("source", "read", "--ref", "R", "--relation-in", "app"),
            )
            .forEach { argv ->
                assertTrue(parsePublicCliArguments(argv) is Refinement.Rejected, "must reject: $argv")
            }
    }
}

@Serializable private data class OraclePipelineEnvelope(val request: OraclePipelineRun)

@Serializable
private data class OraclePipelineRun(
    val source: OracleAllSource,
    val steps: List<OracleStep>,
    val type: String = "RUN",
    val terminalStep: OracleTerminal = OracleTerminal(),
)

@Serializable
private data class OracleAllSource(
    val declarationKinds: List<String>,
    val scope: OracleDirectory,
    val type: String = "ALL_DECLARATIONS",
)

@Serializable
private data class OracleDirectory(
    val relativeDirectoryPath: String,
    val sourceSetNames: List<String>,
    val type: String = "DIRECTORY",
)

@Serializable
private data class OracleStep(
    val type: String,
    val relation: String? = null,
    val predicate: OracleVisibility? = null,
    val mode: OracleMode? = null,
    val right: OracleRight? = null,
)

@Serializable private data class OracleVisibility(val values: List<String>, val type: String = "VISIBILITY")

@Serializable private data class OracleMode(val type: String)

@Serializable private data class OracleRight(val result: String, val type: String = "RESULT")

@Serializable
private data class OracleRelationStep(
    val relation: String,
    val expansionScope: OracleSourceDomain,
    val type: String = "EXPAND_RELATION",
)

@Serializable
private data class OracleSourceDomain(
    val sourceSets: List<String>,
    val directory: String?,
    val sourcePolicy: String = "PRODUCTION_AND_TEST",
    val generatedSources: String = "INCLUDE",
    val type: String = "SOURCE_DOMAIN",
)
