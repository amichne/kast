package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.InstalledBuildIdentity
import io.github.amichne.kast.distribution.contract.UpdatePolicy
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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
            admitBuildRequest("preview", "1.2.3", null),
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
    fun `main is not a channel selector`() {
        assertEquals(
            SelectionAdmission.Rejected(BuildSelectionFailure.SELECTOR_INVALID),
            admitBuildRequest("main", null, null),
        )
    }

    @Test
    fun `preview selects the captured product without consulting main head`() {
        assertEquals(SelectionAdmission.Admitted(BuildRequest.Main), admitBuildRequest("preview", null, null))
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
}

class LocalProductProvenanceTest {
    @Test
    fun `local producer wire shape decodes without CI identities`() {
        val record = productEntry().record
        val localWire =
            managementJson
                .encodeToString(record)
                .replace(
                    managementJson.encodeToString(record.producer),
                    managementJson.encodeToString(LocalProductProducer()),
                )
        val decoded = managementJson.decodeFromString<ProductBuildRecord>(localWire)
        assertEquals(localWire, managementJson.encodeToString(decoded))
    }

    @Test
    fun `provenance serialization preserves CI shape and requires the local discriminator`() {
        val published = productEntry().record
        val encoded = managementJson.parseToJsonElement(managementJson.encodeToString(published)).jsonObject
        val producer = encoded.getValue("producer").jsonObject
        assertEquals(setOf("runId", "runAttempt", "workflow"), producer.keys)
        assertEquals("123", producer.getValue("runId").toString())
        assertEquals("1", producer.getValue("runAttempt").toString())
        assertEquals(".github/workflows/ci.yml", producer.getValue("workflow").jsonPrimitive.content)
        val local = localProductRecord(published, "linux-x86_64")
        for (json in listOf(managementJson, Json { encodeDefaults = false })) {
            val actual = json.parseToJsonElement(json.encodeToString(local)).jsonObject
            val producers =
                listOf(
                    actual.getValue("producer"),
                    actual
                        .getValue("targets")
                        .jsonArray
                        .single()
                        .jsonObject
                        .getValue("qualification")
                        .jsonObject
                        .getValue("producer"),
                )
            for (value in producers) {
                assertEquals(setOf("kind"), value.jsonObject.keys)
                assertEquals("LOCAL", value.jsonObject.getValue("kind").jsonPrimitive.content)
            }
            assertEquals(local, json.decodeFromString<ProductBuildRecord>(json.encodeToString(local)))
        }
        for (invalid in
            listOf(
                managementJson.encodeToString(LocalProductProducer()).replace("LOCAL", "REMOTE"),
                managementJson
                    .encodeToString(published.producer)
                    .replace("workflow", "kind")
                    .replace(".github/workflows/ci.yml", "LOCAL"),
                managementJson.encodeToString(Unit),
                managementJson.encodeToString(emptyList<String>()),
                managementJson.encodeToString<String?>(null),
            )) {
            assertThrows(SerializationException::class.java) {
                managementJson.decodeFromString<ProductProvenance>(invalid)
            }
        }
    }

    @Test
    fun `installed local records reject wrong schemas targets identities and mixed provenance`() {
        val published = productEntry().record
        val local = localProductRecord(published, "linux-x86_64")
        val target = local.targets.single()
        assertTrue(validInstalledProductRecord(published))
        assertTrue(validInstalledProductRecord(local))
        assertTrue(validInstalledProductRecord(localProductRecord(published, "macos-aarch64")))
        for (invalid in
            listOf(
                local.copy(schemaVersion = 2),
                local.copy(schemaVersion = 3),
                local.copy(schemaVersion = 5),
                local.copy(producer = published.producer),
                local.copy(targets = listOf(target.copy(qualification = published.targets.first().qualification))),
                local.copy(targets = emptyList()),
                local.copy(targets = listOf(target, target)),
                local.copy(targets = listOf(target, localProductRecord(published, "macos-aarch64").targets.single())),
                local.copy(targets = listOf(target.copy(target = "linux-aarch64"))),
                local.copy(sourceRevision = "0".repeat(40)),
                local.copy(sourceTree = "not-a-tree"),
                local.copy(targets = listOf(target.copy(runtime = target.runtime.copy(sha256 = "invalid")))),
                published.copy(producer = LocalProductProducer()),
                published.copy(targets = listOf(target, published.targets.last())),
            )) {
            assertFalse(validInstalledProductRecord(invalid), invalid.toString())
            assertFalse(validProductRecord(invalid), invalid.toString())
        }
        val base = ProductAsset("kast-runtime-base-linux-x86_64-${"9".repeat(64)}.zip", "9".repeat(64), 42)
        val split =
            target.copy(
                runtimeBase = base,
                qualification =
                    target.qualification.copy(
                        composition =
                            NativeCompositionEvidence(
                                base.sha256,
                                "a".repeat(64),
                                "b".repeat(64),
                                target.runtime.sha256,
                            )
                    ),
            )
        assertTrue(validInstalledProductRecord(local.copy(targets = listOf(split))))
        assertFalse(validInstalledProductRecord(local.copy(targets = listOf(split.copy(runtimeBase = null)))))
        assertFalse(validInstalledProductRecord(local.copy(targets = listOf(target.copy(runtimeBase = base)))))
    }

    @Test
    fun `catalog and public release never admit local provenance`() {
        val entry = productEntry()
        val local = localProductRecord(entry.record, "linux-x86_64")
        for (record in
            listOf(
                local,
                entry.record.copy(producer = LocalProductProducer()),
                entry.record.copy(targets = listOf(local.targets.single(), entry.record.targets.last())),
            )) {
            assertFalse(validProductRecord(record))
            val catalog = managementJson.encodeToString(ProductBuildCatalog(2, listOf(entry.copy(record = record))))
            assertEquals(
                SelectionAdmission.Rejected(BuildSelectionFailure.CATALOG_UNAVAILABLE),
                AdmittedBuildCatalog.decode(catalog),
            )
            val raw = managementJson.encodeToString(record)
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).toHexString()
            val mapping = managementJson.encodeToString(PublicReleaseMapping(1, "1.2.3", digest))
            val request = (admitBuildRequest(null, "1.2.3", null) as SelectionAdmission.Admitted).value
            assertEquals(
                SelectionAdmission.Rejected(BuildSelectionFailure.ARTIFACT_INVALID),
                resolveBuildSelection(request) { url ->
                    ReleaseDocumentRead.Read(if (url.endsWith("release-mapping.json")) mapping else raw)
                },
            )
        }
    }

    @Test
    fun `installed local runtime requires a private pinned developer reference`(@TempDir temporary: Path) {
        val fixture = productFixture(temporary)
        val recordPath = fixture.installation.resolve("share/kast/build-record.json")
        val original = containerJson.decodeFromString<ProductBuildRecord>(Files.readString(recordPath))
        val local = localProductRecord(original, fixture.reference.target)
        Files.writeString(recordPath, containerJson.encodeToString(local))
        val reference =
            fixture.reference.copy(
                buildRecordSha256 = sha256(recordPath),
                policy = ProductSelectionPolicy.PinnedBuild,
            )
        writeProductReference(fixture.installation, reference)
        val admitted = InstalledProductRuntime.read(fixture.installation, fixture.runtime.manifest.platform)
        assertTrue(admitted is Refinement.Refined, admitted.toString())
        val runtime = (admitted as Refinement.Refined).value
        assertEquals(local, runtime.record)
        assertEquals(fixture.runtime.root, runtime.runtime.root)
        assertEquals(Refinement.Refined(Unit), runtime.admitCurrent())
        for (invalid in
            listOf(
                reference.copy(publicVersion = "1.2.3"),
                reference.copy(publicVersion = "1.2.3", releaseTag = "v1.2.3"),
                reference.copy(policy = ProductSelectionPolicy.TrackMain),
                reference.copy(policy = ProductSelectionPolicy.TrackStable),
                reference.copy(releaseTag = "developer-v0.0.124"),
            )) {
            writeProductReference(fixture.installation, invalid)
            assertEquals(
                Refinement.Rejected(ContainerFailure.INVALID_RECORD),
                InstalledProductRuntime.read(fixture.installation, fixture.runtime.manifest.platform),
                invalid.toString(),
            )
        }
    }

    @Test
    fun `local product repair requires its local artifact before installer or release effects`(
        @TempDir temporary: Path
    ) {
        val fixture = productFixture(temporary)
        val recordPath = fixture.installation.resolve("share/kast/build-record.json")
        val original = containerJson.decodeFromString<ProductBuildRecord>(Files.readString(recordPath))
        Files.writeString(
            recordPath,
            containerJson.encodeToString(localProductRecord(original, fixture.reference.target)),
        )
        val pinned =
            fixture.reference.copy(
                buildRecordSha256 = sha256(recordPath),
                policy = ProductSelectionPolicy.PinnedBuild,
            )
        val executable = Files.createDirectories(fixture.installation.resolve("bin")).resolve("kast")
        Files.writeString(executable, "fixture Control")
        Files.writeString(fixture.installation.resolve("share/kast/install-candidate.sh"), "fixture installer")
        writeProductReference(fixture.installation, pinned)
        writeProductManagement(fixture.installation, executable, pinned)
        val command =
            (parseManagementCommand(listOf("upgrade", "--repair")) as ManagementParsing.Selected).command
                as ManagementCommand.Upgrade
        val result =
            executeManagedUpgrade(
                fixture.installation.parent,
                temporary,
                command,
                emptyMap(),
                ManagedUpgradeExecution(
                    readRelease = { error("local repair must not read a release") },
                    installer = { _, _, _ -> error("local repair must not invoke the installer") },
                ),
            )
        assertEquals(ManagedUpgradeOutcome.Rejected(BuildSelectionFailure.LOCAL_ARTIFACT_REQUIRED), result)
    }
}

class PublishedProductBuildSelectionTest {
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
        val mac = entry.record.copy(schemaVersion = 5, targets = listOf(targets.last()))
        assertTrue(validProductRecord(mac))
        assertFalse(validProductRecord(mac.copy(schemaVersion = 3)))
        assertFalse(validProductRecord(mac.copy(targets = listOf(targets.first()))))
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
    fun `stable channel reads the latest release mapping and not the rate limited API`() {
        val mapping = managementJson.encodeToString(PublicReleaseMapping(1, "1.2.3", "9".repeat(64)))
        val reads = mutableListOf<String>()
        val stable = latestStable { url ->
            reads += url
            ReleaseDocumentRead.Read(mapping)
        }
        assertEquals("1.2.3", (stable as SelectionAdmission.Admitted).value.value)
        assertEquals(
            listOf("https://github.com/amichne/kast/releases/latest/download/release-mapping.json"),
            reads,
        )
        for (invalid in listOf("{", managementJson.encodeToString(PublicReleaseMapping(2, "1.2.3", "9".repeat(64))))) {
            assertEquals(
                SelectionAdmission.Rejected(BuildSelectionFailure.ARTIFACT_INVALID),
                latestStable { ReleaseDocumentRead.Read(invalid) },
            )
        }
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

internal fun localProductRecord(record: ProductBuildRecord, platform: String): ProductBuildRecord {
    val target = record.targets.single { it.target == platform }
    return record.copy(
        schemaVersion = 4,
        producer = LocalProductProducer(),
        targets = listOf(target.copy(qualification = target.qualification.copy(producer = LocalProductProducer()))),
    )
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
