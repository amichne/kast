package io.github.amichne.kast.evidence.sqlite.historical.cli

import io.github.amichne.kast.evidence.sqlite.historical.SqliteHistoricalSnapshotStore
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.topology.contract.historical.HistoricalCoverageStatus
import io.github.amichne.kast.topology.contract.historical.HistoricalFactFamily
import io.github.amichne.kast.topology.contract.historical.HistoricalFile
import io.github.amichne.kast.topology.contract.historical.HistoricalFileProvenance
import io.github.amichne.kast.topology.contract.historical.HistoricalLimitation
import io.github.amichne.kast.topology.contract.historical.HistoricalName
import io.github.amichne.kast.topology.contract.historical.HistoricalOccurrence
import io.github.amichne.kast.topology.contract.historical.HistoricalOccurrenceOwner
import io.github.amichne.kast.topology.contract.historical.HistoricalOwnershipUnavailableCause
import io.github.amichne.kast.topology.contract.historical.HistoricalReferenceContext
import io.github.amichne.kast.topology.contract.historical.HistoricalReferenceKind
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInput
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

/** Synthetic admission proves storage and projection, not native compiler execution. */
class HistoricalGeneratedEvidenceTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `generated content and unavailable owner survive storage and typed JSON without named ownership`() {
        assertEquals("api", GENERATED_SOURCE.substring(40, 43))
        assertEquals(
            CONTENT,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(GENERATED_SOURCE.toByteArray())),
        )
        val snapshot = generatedSnapshot()
        val artifact = directory.toRealPath().resolve("generated.sqlite")
        assertInstanceOf<Refinement.Refined<*>>(SqliteHistoricalSnapshotStore.publish(artifact, snapshot))
        assertGeneratedFile(encoded(historicalOfflineCommand(listOf("files", artifact.toString()))))
        val request =
            HistoricalUsageOracle.request(
                snapshot,
                targets = listOf(HistoricalUsageOracle.integerApi),
                sources = listOf("feature.main"),
            )
        val usage =
            encoded(
                historicalOfflineCommand(
                    listOf(
                        "usage",
                        artifact.toString(),
                        historicalJson.encodeToString(UsageRequestDocument.serializer(), request),
                    )
                )
            )
        assertUnavailableOccurrence(usage)
    }

    private fun assertGeneratedFile(files: JsonObject) {
        val generated =
            files
                .getValue("files")
                .jsonArray
                .single {
                    it.jsonObject.getValue("path").jsonPrimitive.content == GENERATED_PATH
                }
                .jsonObject
        assertEquals(CONTENT, generated.getValue("content").jsonPrimitive.content)
        val provenance = generated.getValue("provenance").jsonObject
        assertEquals(setOf("type", "producer"), provenance.keys)
        assertEquals("generated", provenance.getValue("type").jsonPrimitive.content)
        assertEquals("fixture-generator-v1", provenance.getValue("producer").jsonPrimitive.content)
    }

    private fun assertUnavailableOccurrence(usage: JsonObject) {
        assertEquals("QUALIFIED", usage.getValue("coverage").jsonPrimitive.content)
        val causes = usage.getValue("qualifications").jsonArray.single().jsonObject.getValue("causes").jsonArray
        assertEquals(setOf("OWNER_UNAVAILABLE", "GENERATED_INPUT"), causes.map { it.jsonPrimitive.content }.toSet())
        val occurrence = usage.getValue("occurrences").jsonArray.single().jsonObject
        assertEquals(GENERATED_PATH, occurrence.getValue("sourceFile").jsonPrimitive.content)
        assertEquals("40", occurrence.getValue("start").jsonPrimitive.content)
        assertEquals("43", occurrence.getValue("end").jsonPrimitive.content)
        assertEquals("CODE", occurrence.getValue("context").jsonPrimitive.content)
        val owner = occurrence.getValue("owner").jsonObject
        assertEquals(setOf("type", "cause"), owner.keys)
        assertEquals("unavailable", owner.getValue("type").jsonPrimitive.content)
        assertEquals("UNSUPPORTED_DECLARATION", owner.getValue("cause").jsonPrimitive.content)
    }

    private fun generatedSnapshot() =
        HistoricalUsageOracle.snapshot().let { base ->
            val module = base.modules.single { it.id.value == "feature.main" }.id
            val path = WorkspaceSourcePath.parse(GENERATED_PATH).value()
            HistoricalSnapshotInput(
                    base.header,
                    base.modules,
                    base.dependencies,
                    base.files +
                        HistoricalFile(
                            path,
                            WorkspaceSourceContentHash.parse(CONTENT).value(),
                            module,
                            HistoricalFileProvenance.Generated(HistoricalName.parse("fixture-generator-v1").value()),
                        ),
                    base.declarations,
                    listOf(
                        HistoricalOccurrence(
                            path,
                            ExactDeclarationTextRange.parse(40, 43).value(),
                            HistoricalUsageOracle.integerApi,
                            HistoricalOccurrenceOwner.Unavailable(
                                HistoricalOwnershipUnavailableCause.UNSUPPORTED_DECLARATION
                            ),
                            HistoricalReferenceKind.REFERENCE,
                            HistoricalReferenceContext.CODE,
                        )
                    ),
                    base.coverage.map { coverage ->
                        if (coverage.module == module && coverage.family == HistoricalFactFamily.REFERENCES) {
                            coverage.copy(
                                status =
                                    HistoricalCoverageStatus.Qualified(
                                        HistoricalLimitation.OWNER_UNAVAILABLE,
                                        setOf(HistoricalLimitation.GENERATED_INPUT),
                                    )
                            )
                        } else coverage
                    },
                )
                .admit()
                .value()
        }

    private fun encoded(response: OfflineResponse) =
        Json.parseToJsonElement(historicalJson.encodeToString(OfflineResponse.serializer(), response)).jsonObject

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value

    private companion object {
        const val GENERATED_PATH = "feature/generated/Supplied.kt"
        const val GENERATED_SOURCE = "package generated\nval supplied = sample.api(4)\n"
        const val CONTENT = "639f7854abdce4b1eaaadbca09e80f445d7b5f16d77c39bb5327186bcd57e49d"
    }
}
