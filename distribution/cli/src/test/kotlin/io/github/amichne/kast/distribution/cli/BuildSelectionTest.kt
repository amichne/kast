package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildSelectionTest {
    private val identity = InstalledBuildIdentity("v1.2.3", "1.2.3", "a".repeat(40), "b".repeat(40))

    @Test
    fun `fresh default is stable and a pin never implicitly moves`() {
        val request = (admitBuildRequest(null, null, null) as SelectionAdmission.Admitted).value
        assertEquals(
            SelectionAdmission.Admitted(BuildRequest.Stable),
            resolveRecordedPolicy(request, UpdatePolicy.TrackStable),
        )
        assertEquals(
            SelectionAdmission.Admitted(BuildRequest.Main),
            resolveRecordedPolicy(request, UpdatePolicy.TrackMain),
        )
        assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.PINNED_BUILD),
            resolveRecordedPolicy(request, UpdatePolicy.PinnedBuild(identity)),
        )
        assertEquals(
            SelectionAdmission.Admitted(BuildRequest.Stable),
            resolveRecordedPolicy(BuildRequest.Stable, UpdatePolicy.PinnedBuild(identity)),
        )
    }

    @Test
    fun `selector parsing rejects conflicts and noncanonical revisions`() {
        assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.SELECTORS_CONFLICT),
            admitBuildRequest("main", "1.2.3", null),
        )
        for (raw in listOf("abc", "A".repeat(40), "a".repeat(13), "../revision")) assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.SELECTOR_INVALID),
            admitBuildRequest(null, null, raw),
        )
        for (raw in listOf("v1.2.3", "1.2", "01.2.3", "1.2.3;touch /tmp/no")) assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.SELECTOR_INVALID),
            admitBuildRequest(null, raw, null),
        )
        assertInstanceOf(SelectionAdmission.Admitted::class.java, admitBuildRequest(null, "1.2.3", null))
    }

    @Test
    fun `one captured catalog preserves main and original build identities`() {
        val first = entry("abcdef123456" + "a".repeat(28))
        val second =
            entry("b".repeat(40))
                .copy(platformTargets = listOf("other-target"), version = "0.0.2", releaseTag = "developer-v0.0.2")
        val catalog =
            (AdmittedBuildCatalog.decode(managementJson.encodeToString(MainBuildCatalog(1, listOf(first, second))))
                    as SelectionAdmission.Admitted)
                .value
        val request = (admitBuildRequest(null, null, "abcdef123456") as SelectionAdmission.Admitted).value
        assertEquals(SelectionAdmission.Admitted(first), catalog.select(request, "macos-aarch64"))
        assertEquals("c".repeat(40), first.buildRevision)
        assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.PLATFORM_UNAVAILABLE),
            catalog.select(BuildRequest.Main, "other-target"),
        )
        assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.BINARY_UNAVAILABLE),
            catalog.select(
                (admitBuildRequest(null, null, "d".repeat(40)) as SelectionAdmission.Admitted).value,
                "macos-aarch64",
            ),
        )
    }

    @Test
    fun `ambiguous displayed prefixes never pick the first match`() {
        val builds = listOf(entry("abcdef123456" + "a".repeat(28)), entry("abcdef123456" + "b".repeat(28)))
        val catalog =
            (AdmittedBuildCatalog.decode(managementJson.encodeToString(MainBuildCatalog(1, builds)))
                    as SelectionAdmission.Admitted)
                .value
        val request = (admitBuildRequest(null, null, "abcdef123456") as SelectionAdmission.Admitted).value
        assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.AMBIGUOUS_COMMIT),
            catalog.select(request, "macos-aarch64"),
        )
    }

    @Test
    fun `preview and main alias select the captured product without consulting main head`() {
        assertEquals(admitBuildRequest("preview", null, null), admitBuildRequest("main", null, null))
        val entry = productEntry()
        val catalog = managementJson.encodeToString(ProductBuildCatalog(2, listOf(entry)))
        val reads = mutableListOf<String>()
        val result =
            resolveBuildSelection(BuildRequest.Main) { url ->
                reads += url
                assertEquals("https://raw.githubusercontent.com/amichne/kast/developer-latest/builds.json", url)
                ReleaseDocumentRead.Read(catalog)
            }
        val selected = (result as SelectionAdmission.Admitted).value as ResolvedBuildSelection.Product
        assertEquals(entry.record, selected.record)
        assertEquals(ProductSelectionPolicy.TrackMain, selected.policy)
        assertEquals(1, reads.size)
    }

    @Test
    fun `complete record rejects missing unsupported duplicate and runtime mismatched targets`() {
        val record = productEntry().record
        val first = record.targets.first()
        for (targets in
            listOf(
                record.targets.take(1),
                listOf(first, first),
                listOf(first.copy(target = "linux-aarch64"), record.targets.last()),
                listOf(first.copy(runtime = first.runtime.copy(sha256 = "9".repeat(64))), record.targets.last()),
                listOf(first.copy(qualification = first.qualification.copy(kind = "SCRIPTED")), record.targets.last()),
                listOf(
                    first.copy(qualification = first.qualification.copy(outcome = "NOT_RUN")),
                    record.targets.last(),
                ),
            )) {
            assertFalse(validProductRecord(record.copy(targets = targets)))
        }
        assertTrue(validProductRecord(record))
    }

    @Test
    fun `separated runtime records bind both target bases and native composition evidence`() {
        val entry = productEntry()
        val targets =
            entry.record.targets.map { target ->
                val digest = if (target.target == "linux-x86_64") "8".repeat(64) else "9".repeat(64)
                target.copy(
                    runtimeBase = ProductAsset("kast-runtime-base-${target.target}-$digest.zip", digest, 321),
                    qualification =
                        target.qualification.copy(
                            composition =
                                NativeCompositionEvidence(
                                    digest,
                                    "a".repeat(64),
                                    "b".repeat(64),
                                    target.runtime.sha256,
                                )
                        ),
                )
            }
        val record = entry.record.copy(schemaVersion = 3, targets = targets)
        assertTrue(validProductRecord(record))
        val catalog = managementJson.encodeToString(ProductBuildCatalog(2, listOf(entry.copy(record = record))))
        assertTrue(AdmittedBuildCatalog.decode(catalog) is SelectionAdmission.Admitted)
        assertFalse(validProductRecord(record.copy(schemaVersion = 2)))
        val first = targets.first()
        for (invalid in
            listOf(
                first.copy(runtimeBase = null),
                first.copy(qualification = first.qualification.copy(composition = null)),
                first.copy(runtimeBase = targets.last().runtimeBase),
                first.copy(
                    qualification =
                        first.qualification.copy(
                            composition =
                                NativeCompositionEvidence(
                                    "8".repeat(64),
                                    "a".repeat(64),
                                    "b".repeat(64),
                                    "c".repeat(64),
                                )
                        )
                ),
            )) assertFalse(validProductRecord(record.copy(targets = listOf(invalid, targets.last()))))
    }

    @Test
    fun `exact public version retains embedded build version and never reads latest`() {
        val record = productEntry().record
        val raw = managementJson.encodeToString(record)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).toHexString()
        val mapping = managementJson.encodeToString(PublicReleaseMapping(1, "1.2.3", digest))
        val request = (admitBuildRequest(null, "1.2.3", null) as SelectionAdmission.Admitted).value
        val reads = mutableListOf<String>()
        val selected =
            resolveBuildSelection(request) { url ->
                reads += url
                when (url) {
                    "https://github.com/amichne/kast/releases/download/v1.2.3/release-mapping.json" ->
                        ReleaseDocumentRead.Read(mapping)
                    "https://github.com/amichne/kast/releases/download/v1.2.3/build-record.json" ->
                        ReleaseDocumentRead.Read(raw)
                    else -> error("Unexpected release read: $url")
                }
            }
        val product = (selected as SelectionAdmission.Admitted).value as ResolvedBuildSelection.Product
        assertEquals("1.2.3", product.publicVersion)
        assertEquals("0.0.123", product.record.version)
        assertEquals(ProductSelectionPolicy.PinnedBuild, product.policy)
        assertEquals(2, reads.size)
    }

    @Test
    fun `exact commit rejects a catalog alias for another source revision`() {
        val entry = productEntry().copy(mainRevision = "c".repeat(40))
        val request = (admitBuildRequest(null, null, "c".repeat(40)) as SelectionAdmission.Admitted).value
        val result =
            resolveBuildSelection(request) {
                ReleaseDocumentRead.Read(managementJson.encodeToString(ProductBuildCatalog(2, listOf(entry))))
            }
        assertEquals(SelectionAdmission.Rejected(BuildSelectionFailure.CATALOG_UNAVAILABLE), result)
    }

    @Test
    fun `legacy commit never fills its missing runtime from another release`() {
        val legacy = entry("b".repeat(40))
        val request = (admitBuildRequest(null, null, "b".repeat(40)) as SelectionAdmission.Admitted).value
        var reads = 0
        val result =
            resolveBuildSelection(request) {
                reads += 1
                ReleaseDocumentRead.Read(managementJson.encodeToString(MainBuildCatalog(1, listOf(legacy))))
            }
        assertEquals(SelectionAdmission.Rejected(BuildSelectionFailure.BINARY_UNAVAILABLE), result)
        assertEquals(1, reads)
    }
}

internal fun productEntry(): ProductBuildEntry {
    val targets =
        listOf("linux-x86_64", "macos-aarch64").map { target ->
            ProductTarget(
                target,
                ProductInputs("1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64)),
                ProductAsset("kast-composed-setup-v0.0.123-$target.tar.gz", "5".repeat(64), 17),
                ProductAsset("kast-runtime-v0.0.123-$target.zip", "6".repeat(64), 19),
                NativeQualification(
                    ProductAsset("qualification-$target.json", "7".repeat(64), 23),
                    ProductProducer(456, 2, ".github/workflows/composed-linux.yml"),
                    "5".repeat(64),
                    "6".repeat(64),
                    "8".repeat(64),
                    "NATIVE",
                    "COMPLETE",
                ),
            )
        }
    return ProductBuildEntry(
        "a".repeat(40),
        "developer-v0.0.123",
        ProductBuildRecord(
            2,
            "amichne/kast",
            "a".repeat(40),
            "b".repeat(40),
            "0.0.123",
            ProductProducer(123, 1, ".github/workflows/ci.yml"),
            targets,
        ),
        "c".repeat(64),
    )
}

private fun entry(main: String) =
    MainBuildEntry(
        main,
        "c".repeat(40),
        "d".repeat(40),
        "developer-v0.0.1",
        "0.0.1",
        listOf("macos-aarch64"),
        BuildRecordReference("candidate.json", "e".repeat(64)),
        BuildRecordReference("promotion.json", "f".repeat(64)),
    )
