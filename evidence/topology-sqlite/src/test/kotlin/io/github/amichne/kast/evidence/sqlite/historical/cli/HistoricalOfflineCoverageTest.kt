package io.github.amichne.kast.evidence.sqlite.historical.cli

import io.github.amichne.kast.evidence.sqlite.historical.SqliteHistoricalSnapshotStore
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalCoverageStatus
import io.github.amichne.kast.topology.contract.historical.HistoricalFactFamily
import io.github.amichne.kast.topology.contract.historical.HistoricalLimitation
import io.github.amichne.kast.topology.contract.historical.HistoricalProfile
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshot
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshotInput
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class HistoricalOfflineCoverageTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `file and declaration pages retain fixed coverage summary and finite aggregate causes`() {
        val snapshot = qualifiedDeclarations()
        val artifact = publish(snapshot)
        for (operation in listOf("files", "declarations")) {
            val manifest = manifest(operation, artifact)
            assertEquals("6", manifest.getValue("importedModuleCount").jsonPrimitive.content)
            assertEquals(
                listOf(
                    "MODULES|6|0|0|0|",
                    "FILES|6|0|0|0|",
                    "DECLARATIONS|5|1|0|0|UNSUPPORTED_DECLARATION",
                    "REFERENCES|4|1|0|1|ROOTLESS,UNRESOLVED_TARGET",
                ),
                summary(manifest),
            )
        }
    }

    @Test
    fun `structural declaration page preserves references not collected in its manifest`() {
        val artifact = publish(HistoricalUsageOracle.snapshot(HistoricalProfile.STRUCTURE_V1))
        assertEquals("REFERENCES|0|0|6|0|", summary(manifest("declarations", artifact)).last())
    }

    private fun qualifiedDeclarations(): HistoricalSnapshot {
        val base = HistoricalUsageOracle.snapshot()
        return HistoricalSnapshotInput(
                base.header,
                base.modules,
                base.dependencies,
                base.files,
                base.declarations,
                base.occurrences,
                base.coverage.map { coverage ->
                    if (
                        coverage.module.value == "feature.main" && coverage.family == HistoricalFactFamily.DECLARATIONS
                    ) {
                        coverage.copy(
                            status = HistoricalCoverageStatus.Qualified(HistoricalLimitation.UNSUPPORTED_DECLARATION)
                        )
                    } else coverage
                },
            )
            .admit()
            .value()
    }

    private fun publish(snapshot: HistoricalSnapshot): Path {
        val artifact = directory.toRealPath().resolve("${snapshot.identity.value}.sqlite")
        SqliteHistoricalSnapshotStore.publish(artifact, snapshot).value()
        return artifact
    }

    private fun manifest(operation: String, artifact: Path): JsonObject {
        val response = historicalOfflineCommand(listOf(operation, artifact.toString()))
        return Json.parseToJsonElement(historicalJson.encodeToString(OfflineResponse.serializer(), response))
            .jsonObject
            .getValue("manifest")
            .jsonObject
    }

    private fun summary(manifest: JsonObject) =
        manifest.getValue("familyCoverage").jsonArray.map { entry ->
            val value = entry.jsonObject
            val counts =
                listOf("family", "complete", "qualified", "notCollected", "outsideProfile").map {
                    value.getValue(it).jsonPrimitive.content
                }
            val causes = value.getValue("causes").jsonArray.map { it.jsonPrimitive.content }.sorted().joinToString(",")
            (counts + causes).joinToString("|")
        }

    private fun <T, F> Refinement<T, F>.value(): T = assertInstanceOf<Refinement.Refined<T>>(this).value
}
