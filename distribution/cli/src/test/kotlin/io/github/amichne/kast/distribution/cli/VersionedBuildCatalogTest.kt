package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class VersionedBuildCatalogTest {
    @Test
    fun `main captures the versioned target entry once and preserves its exact encoded shape`() {
        val requests = mutableListOf<String>()
        val entry = Entry()
        val result =
            resolveBuildSelection(BuildRequest.Main) { url ->
                requests.add(url)
                ReleaseDocumentRead.Read(managementJson.encodeToString(Catalog(builds = listOf(entry))))
            }
        val admitted = assertInstanceOf(SelectionAdmission.Admitted::class.java, result)
        val selected = admitted.value as ResolvedBuildSelection
        val encoded = managementJson.parseToJsonElement(managementJson.encodeToString(selected)).jsonObject
        assertEquals(listOf(V2_URL), requests)
        assertEquals(managementJson.parseToJsonElement(managementJson.encodeToString(entry)), encoded["catalogEntry"])
    }

    @Test
    fun `a present malformed versioned catalog never falls back to a legacy view`() {
        val requests = mutableListOf<String>()
        val result = captureMainCatalog { url ->
            requests.add(url)
            ReleaseDocumentRead.Read(managementJson.encodeToString(Catalog(schemaVersion = 99)))
        }
        assertEquals(SelectionAdmission.Rejected(BuildSelectionFailure.CATALOG_UNAVAILABLE), result)
        assertEquals(listOf(V2_URL), requests)
    }

    @Test
    fun `versioned history missing a requested commit cannot borrow a legacy entry`() {
        val requests = mutableListOf<String>()
        val request = (admitBuildRequest(null, null, "b".repeat(40)) as SelectionAdmission.Admitted).value
        val result =
            resolveBuildSelection(request) { url ->
                requests.add(url)
                ReleaseDocumentRead.Read(managementJson.encodeToString(Catalog()))
            }
        assertEquals(SelectionAdmission.Rejected(BuildSelectionFailure.BINARY_UNAVAILABLE), result)
        assertEquals(listOf(V2_URL), requests)
    }

    @Test
    fun `transport rejection does not authorize legacy fallback`() {
        val requests = mutableListOf<String>()
        val result = captureMainCatalog { url ->
            requests.add(url)
            ReleaseDocumentRead.Unavailable(
                ReleaseReadObservation.Unavailable(
                    ReleaseReadStage.RESPONSE,
                    ReleaseReadFailure.HTTP_REJECTED,
                )
            )
        }
        assertEquals(SelectionAdmission.Rejected(BuildSelectionFailure.CATALOG_UNAVAILABLE), result)
        assertEquals(listOf(V2_URL), requests)
    }

    @Test
    fun `only an actual absent versioned view permits an unchanged legacy entry`() {
        val requests = mutableListOf<String>()
        val legacy =
            LegacyMainBuildEntry(
                "a".repeat(40),
                "b".repeat(40),
                "c".repeat(40),
                "developer-v0.0.4999",
                "0.0.4999",
                listOf("macos-aarch64"),
                BuildRecordReference("candidate.json", "d".repeat(64)),
                BuildRecordReference("promotion.json", "e".repeat(64)),
            )
        val result =
            resolveBuildSelection(BuildRequest.Main) { url ->
                requests.add(url)
                if (url == V2_URL)
                    ReleaseDocumentRead.Unavailable(
                        ReleaseReadObservation.Unavailable(
                            ReleaseReadStage.RESPONSE,
                            ReleaseReadFailure.NOT_FOUND,
                        )
                    )
                else ReleaseDocumentRead.Read(managementJson.encodeToString(MainBuildCatalog(1, listOf(legacy))))
            }
        val selected = (result as SelectionAdmission.Admitted).value
        val fields =
            managementJson
                .parseToJsonElement(managementJson.encodeToString(selected))
                .jsonObject
                .getValue("catalogEntry")
                .jsonObject
        assertEquals(
            setOf(
                "mainRevision",
                "buildRevision",
                "sourceTree",
                "releaseTag",
                "version",
                "platformTargets",
                "candidate",
                "promotion",
            ),
            fields.keys,
        )
        assertEquals(listOf(V2_URL, V2_URL.replace("builds-v2.json", "builds.json")), requests)
    }

    @Test
    fun `reordered missing duplicated swapped and mixed target records reject without fallback`() {
        val valid = Entry()
        val first = valid.hostTargets.first()
        for (targets in
            listOf(
                valid.hostTargets.reversed(),
                listOf(first),
                listOf(first, first),
                listOf(first.copy(record = valid.hostTargets.last().record), valid.hostTargets.last()),
                listOf(first.copy(ideBuild = "263.1.2"), valid.hostTargets.last()),
            )) {
            var reads = 0
            val result = captureMainCatalog {
                reads++
                ReleaseDocumentRead.Read(
                    managementJson.encodeToString(Catalog(builds = listOf(valid.copy(hostTargets = targets))))
                )
            }
            assertEquals(SelectionAdmission.Rejected(BuildSelectionFailure.CATALOG_UNAVAILABLE), result)
            assertEquals(1, reads)
        }
    }

    @Serializable private data class Catalog(val schemaVersion: Int = 2, val builds: List<Entry> = listOf(Entry()))

    @Serializable
    private data class Entry(
        val mainRevision: String = "a".repeat(40),
        val buildRevision: String = "a".repeat(40),
        val sourceTree: String = "c".repeat(40),
        val releaseTag: String = "developer-v0.0.5000",
        val version: String = "0.0.5000",
        val platformTargets: List<String> = listOf("macos-aarch64"),
        val candidate: BuildRecordReference = BuildRecordReference("candidate-v2.json", "d".repeat(64)),
        val promotion: BuildRecordReference = BuildRecordReference("promotion-v2.json", "e".repeat(64)),
        val hostTargets: List<Target> =
            listOf(
                Target(
                    "262",
                    "262.11879.100",
                    "262.11879.100-IJ",
                    BuildRecordReference("kast-host-release-v0.0.5000.json", "f".repeat(64)),
                ),
                Target(
                    "263",
                    "263.7307.200",
                    "263.7307.200-IJ",
                    BuildRecordReference("kast-host-release-v0.0.5000-idea-263.json", "1".repeat(64)),
                ),
            ),
    )

    @Serializable
    private data class Target(
        val intellijReleaseLine: String,
        val ideBuild: String,
        val kotlinPluginBuild: String,
        val record: BuildRecordReference,
    )

    companion object {
        private const val V2_URL = "https://raw.githubusercontent.com/amichne/kast/developer-latest/builds-v2.json"
    }
}
