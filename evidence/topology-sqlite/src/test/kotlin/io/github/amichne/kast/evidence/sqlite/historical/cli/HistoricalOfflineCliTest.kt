package io.github.amichne.kast.evidence.sqlite.historical.cli

import io.github.amichne.kast.evidence.sqlite.historical.SqliteHistoricalSnapshotStore
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.historical.HistoricalProfile
import io.github.amichne.kast.topology.contract.historical.HistoricalSnapshot
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir

class HistoricalOfflineCliTest {
    @TempDir lateinit var directory: Path

    @BeforeEach
    fun canonicalDirectory() {
        directory = directory.toRealPath()
    }

    @Test
    fun `bounded occurrence pages equal explicit witnesses and never upgrade coverage`() {
        val snapshot = HistoricalUsageOracle.snapshot()
        val artifact = publish(snapshot)
        val pages = mutableListOf<OfflineResponse.Usage>()
        var request = HistoricalUsageOracle.request(snapshot)
        do {
            val page = assertInstanceOf<OfflineResponse.Usage>(usage(artifact, request))
            pages += page
            assertEquals(request.cursor, page.pageStart)
            assertEquals(OfflineCoverageStatus.QUALIFIED, page.coverage)
            assertEquals(listOf(OfflineLimitation.UNRESOLVED_TARGET), page.qualifications.single().causes)
            assertEquals(1, page.occurrenceCount)
            assertTrue(page.work.artifactRowsExamined > 0)
            assertEquals(2, page.work.requestSelectionRowsExamined)
            request = request.copy(cursor = page.next)
        } while (request.cursor != null)
        assertEquals(HistoricalUsageOracle.expectedRows, pages.flatMap { it.occurrences }.map(::row))
        assertEquals(4, pages.size)
        assertEquals(listOf(4L, 5L, 5L, 4L), pages.map { it.work.pageRowsExamined })
    }

    @Test
    fun `exact overload and location distinction preserve complete and qualified emptiness`() {
        val snapshot = HistoricalUsageOracle.snapshot()
        val artifact = publish(snapshot)
        val complete =
            assertInstanceOf<OfflineResponse.Usage>(
                usage(
                    artifact,
                    HistoricalUsageOracle.request(
                        snapshot,
                        targets = listOf(HistoricalUsageOracle.stringApi),
                        sources = listOf("feature.main"),
                    ),
                )
            )
        assertEquals(0, complete.occurrenceCount)
        assertEquals(OfflineCoverageStatus.COMPLETE, complete.coverage)
        val qualified =
            assertInstanceOf<OfflineResponse.Usage>(
                usage(
                    artifact,
                    HistoricalUsageOracle.request(snapshot, targets = listOf(HistoricalUsageOracle.isolatedApi)),
                )
            )
        assertEquals(0, qualified.occurrenceCount)
        assertEquals(OfflineCoverageStatus.QUALIFIED, qualified.coverage)
        assertEquals(null, qualified.next)
    }

    @Test
    fun `structural profile and outside source scope return finite unavailable outcomes`() {
        val structural = HistoricalUsageOracle.snapshot(HistoricalProfile.STRUCTURE_V1)
        assertEquals(
            OfflineFailureCode.REFERENCES_NOT_CAPTURED,
            assertInstanceOf<OfflineResponse.Rejected>(
                    usage(publish(structural), HistoricalUsageOracle.request(structural))
                )
                .reason,
        )
        val snapshot = HistoricalUsageOracle.snapshot()
        assertEquals(
            OfflineFailureCode.SOURCE_OUTSIDE_CAPTURE,
            assertInstanceOf<OfflineResponse.Rejected>(
                    usage(
                        publish(snapshot),
                        HistoricalUsageOracle.request(snapshot, sources = listOf("missing.main")),
                    )
                )
                .reason,
        )
    }

    @Test
    fun `inventory and exact declarations remain bounded with family bound cursors`() {
        val artifact = publish(HistoricalUsageOracle.snapshot())
        val request = historicalJson.encodeToString(PageRequestDocument.serializer(), PageRequestDocument(2))
        val inventory =
            assertInstanceOf<OfflineResponse.Inventory>(
                historicalOfflineCommand(listOf("inventory", artifact.toString(), request))
            )
        assertEquals(listOf("contracts.main", "empty.main"), inventory.modules.map { it.id })
        val next =
            historicalJson.encodeToString(PageRequestDocument.serializer(), PageRequestDocument(2, inventory.next))
        assertEquals(
            OfflineFailureCode.CURSOR_MISMATCH,
            assertInstanceOf<OfflineResponse.Rejected>(
                    historicalOfflineCommand(listOf("declarations", artifact.toString(), next))
                )
                .reason,
        )
        val declarations =
            assertInstanceOf<OfflineResponse.Declarations>(
                historicalOfflineCommand(listOf("declarations", artifact.toString(), request))
            )
        assertEquals(listOf("sample.Api", "sample.api(Int)"), declarations.declarations.map { it.identity.compiler })
    }

    @Test
    fun `fresh process reads relocated read only artifact without original source or live dependencies`() {
        val snapshot = HistoricalUsageOracle.snapshot()
        val originalRoot = Files.createDirectory(directory.resolve("original-checkout"))
        val source = originalRoot.resolve("Api.kt")
        Files.writeString(source, HistoricalUsageOracle.sources.getValue("contracts/Api.kt"))
        val original = originalRoot.resolve("snapshot.sqlite")
        assertInstanceOf<Refinement.Refined<*>>(SqliteHistoricalSnapshotStore.publish(original, snapshot))
        val copied = directory.resolve("relocated.sqlite")
        Files.copy(original, copied)
        Files.delete(original)
        Files.delete(source)
        Files.delete(originalRoot)
        assertTrue(copied.toFile().setReadOnly())
        val response = freshProcess(copied, HistoricalUsageOracle.request(snapshot, size = 100))
        assertEquals("usage", response.getValue("type").jsonPrimitive.content)
        assertEquals(
            snapshot.identity.value,
            response.getValue("manifest").jsonObject.getValue("snapshot").jsonPrimitive.content,
        )
        assertEquals("QUALIFIED", response.getValue("coverage").jsonPrimitive.content)
        assertEquals(4, response.getValue("occurrences").jsonArray.size)
        assertFalse(Files.exists(originalRoot))
        assertFalse(Files.exists(directory.resolve("relocated.sqlite-wal")))
        assertFalse(Files.exists(directory.resolve("relocated.sqlite-shm")))
    }

    private fun freshProcess(artifact: Path, request: UsageRequestDocument) = run {
        val classpath = runtimeClasspath()
        assertFalse(
            classpath.any {
                it.contains("topology-intellij") || it.contains("runtime-hosted") || it.contains("app-server")
            }
        )
        val emptyPath = Files.createDirectory(directory.resolve("no-executables"))
        val command =
            listOf(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                classpath.joinToString(System.getProperty("path.separator")),
                "io.github.amichne.kast.evidence.sqlite.historical.cli.HistoricalOfflineMainKt",
                "usage",
                artifact.toString(),
                historicalJson.encodeToString(UsageRequestDocument.serializer(), request),
            )
        val builder = ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
        builder.environment().clear()
        builder.environment()["PATH"] = emptyPath.toString()
        val child = builder.start()
        assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Offline child did not exit")
        val output = child.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, child.exitValue(), output)
        Json.parseToJsonElement(output.lineSequence().first { it.startsWith('{') }).jsonObject
    }

    private fun runtimeClasspath(): List<String> = buildList {
        addAll(System.getProperty("java.class.path").split(System.getProperty("path.separator")))
        var loader: ClassLoader? = javaClass.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) addAll(loader.urLs.map { Path.of(it.toURI()).toString() })
            loader = loader.parent
        }
    }
        .distinct()

    private fun publish(snapshot: HistoricalSnapshot): Path {
        val artifact = directory.resolve("${snapshot.identity.value}.sqlite")
        assertInstanceOf<Refinement.Refined<*>>(SqliteHistoricalSnapshotStore.publish(artifact, snapshot))
        return artifact
    }

    private fun usage(artifact: Path, request: UsageRequestDocument): OfflineResponse =
        historicalOfflineCommand(
            listOf(
                "usage",
                artifact.toString(),
                historicalJson.encodeToString(UsageRequestDocument.serializer(), request),
            )
        )

    private fun row(value: UsageRowDocument): String =
        listOf(
                value.sourceModule,
                value.targetModule,
                value.sourceFile,
                value.start,
                value.end,
                value.target.compiler,
                value.target.file,
                value.target.start,
                value.target.end,
                value.relation,
                when (value.owner) {
                    is OwnerDocument.File -> "file"
                    is OwnerDocument.Declaration -> "declaration"
                    is OwnerDocument.Unavailable -> "unavailable"
                },
            )
            .joinToString("|")
}
