package io.github.amichne.kast.distribution.cli

import io.github.amichne.kast.distribution.contract.UpdatePolicy
import io.github.amichne.kast.distribution.managed.CanonicalRootDiscovery
import io.github.amichne.kast.distribution.managed.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstalledProductRuntimeTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `product uninstall fences admission and removes only the retired stores`() {
        val fixture = productFixture(temporary)
        val root = fixture.installation.parent
        val home = Files.createDirectory(temporary.resolve("user-home")).toRealPath()
        val executable = Files.writeString(temporary.resolve("public-control"), "fixture Control")
        writeProductRemovalFixture(fixture)
        writeProductManagement(fixture.installation, executable, fixture.reference)
        val receipt = admittedUninstallReceipt(root)
        val store = fixture.runtime.root.parent.parent
        val sentinel = Files.writeString(home.resolve("foreign"), "protected")
        uninstallInstallation(
            root,
            home,
            executeInstaller = { script, args ->
                assertEquals(fixture.installation.resolve("share/kast/install-candidate.sh"), script)
                assertEquals(
                    listOf(
                        "--remove-product",
                        "--install-root",
                        root.toString(),
                        "--uninstall-journal",
                        uninstallJournalPath(root).toString(),
                    ),
                    args,
                )
                assertTrue(Files.isRegularFile(root.resolve(SHUTDOWN_FENCE)))
                assertEquals(UninstallRetirementType.REMOVAL_REQUESTED, readUninstallRetirement(root).type)
                assertEquals(
                    Refinement.Rejected(ContainerFailure.BUSY),
                    ContainerQueryLease.duringStoreAdmission<Unit>(store) { error("removal excludes admission") },
                )
                // This adapter observation proves Kotlin orchestration, not native Python removal.
                Files.move(fixture.runtime.root, temporary.resolve("retired-runtime"))
                Files.move(fixture.installation, temporary.resolve("retired-payload"))
                persistUninstallRetirement(
                    root,
                    receipt,
                    UninstallRetirementType.CONTROL_RETIRED,
                )
                0
            },
        )
        assertFalse(Files.exists(root))
        assertFalse(Files.exists(store))
        assertFalse(Files.exists(executable))
        assertFalse(Files.exists(uninstallJournalPath(root)))
        assertEquals("protected", Files.readString(sentinel))
        assertTrue(Files.isRegularFile(store.resolveSibling(".kast-workspaces.lifetime.lock")))
    }

    @Test
    fun `removed product receipt requires exact committed uninstall journal`() {
        val fixture = productFixture(temporary)
        val root = fixture.installation.parent
        val executable = Files.writeString(temporary.resolve("public-control"), "fixture Control")
        writeProductManagement(fixture.installation, executable, fixture.reference)
        val admitted = readReceipt(root) as ReceiptRead.Read
        persistUninstallRetirement(root, admitted.receipt, UninstallRetirementType.REMOVAL_REQUESTED)
        Files.move(fixture.installation, temporary.resolve("removed-payload"))
        assertEquals(ReceiptRead.Unavailable("receipt_invalid"), readReceipt(root))
        persistUninstallRetirement(root, admitted.receipt, UninstallRetirementType.CONTROL_RETIRED)
        assertEquals(admitted, readReceipt(root))
        val tampered = admitted.receipt.copy(product = fixture.reference.copy(sourceRevision = "e".repeat(40)))
        assertEquals(
            ReceiptRead.Unavailable("receipt_invalid"),
            admitManagementReceipt(root, encodeManagementReceipt(tampered)),
        )
        Files.move(root, root.resolveSibling("foreign-root"))
        Files.createDirectory(root)
        Files.writeString(receiptPath(root), encodeManagementReceipt(admitted.receipt))
        assertEquals(ReceiptRead.Unavailable("receipt_invalid"), readReceipt(root))
    }

    @Test
    fun `repair retains an exact pin and replaces missing runtime without release discovery`() {
        val fixture = productFixture(temporary)
        val pinned = fixture.reference.copy(policy = ProductSelectionPolicy.PinnedBuild)
        val executable = Files.createDirectories(fixture.installation.resolve("bin")).resolve("kast")
        Files.writeString(executable, "fixture Control")
        Files.writeString(fixture.installation.resolve("share/kast/install-candidate.sh"), "fixture installer")
        writeProductReference(fixture.installation, pinned)
        writeProductManagement(fixture.installation, executable, pinned)
        Files.move(fixture.runtime.root, fixture.runtime.root.resolveSibling("unavailable-runtime"))
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
                    readRelease = { error("repair must not discover a different build") },
                    installer = { arguments, _, _ ->
                        val captured = Path.of(arguments[arguments.indexOf("--resolved-selection") + 1])
                        val selection =
                            containerJson.decodeFromString<ResolvedBuildSelection>(Files.readString(captured))
                                as ResolvedBuildSelection.Product
                        assertEquals(pinned.releaseTag, selection.releaseTag)
                        assertEquals(pinned.buildRecordSha256, selection.recordSha256)
                        assertEquals(ProductSelectionPolicy.PinnedBuild, selection.policy)
                        assertEquals(
                            pinned.runtimeArchiveSha256,
                            selection.record.targets.single { it.target == pinned.target }.runtime.sha256,
                        )
                        0
                    },
                ),
            )
        assertEquals(ManagedUpgradeOutcome.ChildExited(0), result)
    }

    @Test
    fun `repair conflicts and desktop upgrade selectors reject before effects`() {
        for (arguments in
            listOf(
                listOf("upgrade", "--repair", "--channel", "stable"),
                listOf("upgrade", "--idea-home", "/foreign"),
                listOf("upgrade", "--restart-ide"),
            )) {
            assertTrue(parseManagementCommand(arguments) is ManagementParsing.Print)
        }
    }

    @Test
    fun `product management receipts preserve exact identity and guidance across writes`() {
        val fixture = productFixture(temporary)
        val root = fixture.installation.parent
        val guidance =
            listOf(
                ManagedGuidance(
                    Harness.PI,
                    temporary.resolve("skills/kast").toString(),
                    listOf(GuidanceFile("SKILL.md", "e".repeat(64))),
                )
            )
        val wire =
            ProductReceiptFixture(
                3,
                root.toString(),
                temporary.resolve("bin/kast").toString(),
                "d".repeat(64),
                fixture.reference,
                emptyList(),
                guidance,
            )
        val admitted = admitManagementReceipt(root, containerJson.encodeToString(wire))
        assertTrue(admitted is ReceiptRead.Read, admitted.toString())
        val receipt = (admitted as ReceiptRead.Read).receipt
        assertEquals(UpdatePolicy.TrackMain, receipt.updatePolicy)
        assertEquals(guidance, receipt.guidance)
        writeManagementReceipt(root, receipt)
        val actual = containerJson.parseToJsonElement(Files.readString(receiptPath(root))).jsonObject
        val expected = containerJson.parseToJsonElement(containerJson.encodeToString(wire)).jsonObject
        assertEquals(expected, actual)
        assertEquals(admitted, readReceipt(root))
        assertEquals(
            ReceiptRead.Unavailable("receipt_invalid"),
            admitManagementReceipt(
                root,
                containerJson.encodeToString(
                    wire.copy(product = fixture.reference.copy(sourceRevision = "e".repeat(40)))
                ),
            ),
        )
    }

    @Test
    fun `installed selection admits only its qualified local runtime`() {
        val fixture = productFixture(temporary)
        val admitted = InstalledProductRuntime.read(fixture.installation, fixture.runtime.manifest.platform)
        assertTrue(admitted is Refinement.Refined, admitted.toString())
        val selected = (admitted as Refinement.Refined).value
        assertEquals(fixture.runtime.root, selected.runtime.root)
        assertEquals(fixture.reference.sourceRevision, selected.record.sourceRevision)
        assertEquals("0.0.123", selected.record.version)
        assertFalse(Files.exists(fixture.installation.parent.resolveSibling("kast-workspaces/containers")))

        val metadata = fixture.installation.resolve("share/kast/product-selection.json")
        Files.writeString(metadata, Files.readString(metadata) + " ")
        assertEquals(
            Refinement.Rejected(ContainerFailure.INVALID_RECORD),
            InstalledProductRuntime.read(fixture.installation, fixture.runtime.manifest.platform),
        )
    }

    @Test
    fun `self-consistent receipts cannot substitute a root target revision or runtime manifest`() {
        val fixture = productFixture(temporary)
        for (invalid in
            listOf(
                fixture.reference.copy(runtimeRoot = temporary.resolve("foreign").toString()),
                fixture.reference.copy(sourceRevision = "e".repeat(40)),
                fixture.reference.copy(runtimeArchiveSha256 = "e".repeat(64)),
                fixture.reference.copy(runtimeManifestSha256 = "e".repeat(64)),
                fixture.reference.copy(buildRecordSha256 = "e".repeat(64)),
                fixture.reference.copy(target = "linux-aarch64"),
                fixture.reference.copy(releaseTag = "v9.8.7"),
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
    fun `missing runtime rejects locally and leaves installation records untouched`() {
        val fixture = productFixture(temporary)
        val manifest = fixture.installation.resolve("installation.json")
        val original = Files.readString(manifest)
        Files.move(fixture.runtime.root, fixture.runtime.root.resolveSibling("retained-runtime"))
        assertEquals(
            Refinement.Rejected(ContainerFailure.RUNTIME_UNAVAILABLE),
            InstalledProductRuntime.read(fixture.installation, fixture.runtime.manifest.platform),
        )
        assertEquals(original, Files.readString(manifest))
        assertFalse(Files.exists(fixture.runtime.root))
    }
}

class InstalledWorkspaceAdmissionTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `missing recorded Gradle home rejects semantic launch without revoking lifecycle ownership`() {
        val fixture = productFixture(temporary)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(workspace) as CanonicalRootDiscovery.Discovered).root
        val gradle = Files.createDirectory(temporary.resolve("external-gradle")).toRealPath()
        val store = ContainerStore(fixture.runtime.root.parent.parent, fixture.runtime.manifest.platform)
        val container =
            (store.create(
                    ContainerCommand.Create("owned", null, gradle.toString(), false),
                    workspace,
                    fixture.runtime,
                ) as Refinement.Refined)
                .value
        Files.delete(gradle)
        assertEquals(
            Refinement.Rejected(ContainerFailure.INVALID_GRADLE_HOME),
            ManagedWorkspaceQuery.open(fixture.installation, root, emptyMap()),
        )
        assertTrue(store.workspace(workspace) is Refinement.Refined)
        assertEquals(
            Refinement.Refined(Unit),
            ContainerQueryLease.whileIdle(container) { Refinement.Refined(Unit) },
        )
        Files.createDirectory(gradle)
        (ManagedWorkspaceQuery.open(fixture.installation, root, emptyMap()) as Refinement.Refined).value.close()
    }

    @Test
    fun `store removal excludes first admission before any workspace exists`() {
        val fixture = productFixture(temporary)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(workspace) as CanonicalRootDiscovery.Discovered).root
        val store = fixture.runtime.root.parent.parent
        java.nio.channels.FileChannel.open(
                store.resolveSibling(".kast-workspaces.lifetime.lock"),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE,
            )
            .use { channel ->
                channel.lock().use {
                    assertEquals(
                        Refinement.Rejected(ContainerFailure.BUSY),
                        ManagedWorkspaceQuery.open(fixture.installation, root, emptyMap()),
                    )
                    assertFalse(Files.exists(store.resolve("containers")))
                }
            }
        (ManagedWorkspaceQuery.open(fixture.installation, root, emptyMap()) as Refinement.Refined).value.close()
        assertTrue(Files.exists(store.resolve("containers")))
    }

    @Test
    fun `initial admission excludes store removal but allows another admission`() {
        val store = temporary.toRealPath().resolve("kast-workspaces")
        assertEquals(
            Refinement.Refined(Unit),
            ContainerQueryLease.duringStoreAdmission(store) {
                assertEquals(
                    Refinement.Rejected(ContainerFailure.BUSY),
                    ContainerQueryLease.duringStoreRemoval<Unit>(store) {
                        error("removal crossed an initial admission")
                    },
                )
                ContainerQueryLease.duringStoreAdmission(store) { Refinement.Refined(Unit) }
            },
        )
        assertEquals(
            Refinement.Refined(Unit),
            ContainerQueryLease.duringStoreRemoval(store) { Refinement.Refined(Unit) },
        )
        assertFalse(Files.exists(store))
    }

    @Test
    fun `shutdown fencing rejects workspace creation before granting trust`() {
        val fixture = productFixture(temporary)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(workspace) as CanonicalRootDiscovery.Discovered).root
        Files.writeString(fixture.installation.parent.resolve(SHUTDOWN_FENCE), "fenced")
        assertEquals(
            Refinement.Rejected(ContainerFailure.FENCE_REJECTED),
            ManagedWorkspaceQuery.open(fixture.installation, root, emptyMap()),
        )
        assertFalse(Files.exists(fixture.runtime.root.parent.parent.resolve("containers")))
    }

    @Test
    fun `semantic admission creates exact trust once and leases each request independently`() {
        val fixture = productFixture(temporary)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(workspace) as CanonicalRootDiscovery.Discovered).root
        val store = ContainerStore(fixture.runtime.root.parent.parent, fixture.runtime.manifest.platform)
        val first = (ManagedWorkspaceQuery.open(fixture.installation, root, emptyMap()) as Refinement.Refined).value
        val record = (store.workspace(workspace) as Refinement.Refined).value
        val trust = Files.readString(record.directory.resolve("trust.xml"))
        val second = (ManagedWorkspaceQuery.open(fixture.installation, root, emptyMap()) as Refinement.Refined).value
        second.close()
        val container = (store.select(record.id.text) as Refinement.Refined).value
        assertEquals(
            Refinement.Rejected(ContainerFailure.BUSY),
            ContainerQueryLease.whileIdle<Unit>(container) {
                error("retired the other request")
            },
        )
        assertEquals(container.home, first.home)
        assertEquals(container.state.toString(), first.environment["KAST_MANAGED_HEADLESS_STATE"])
        assertEquals(trust, Files.readString(record.directory.resolve("trust.xml")))
        first.close()
        assertEquals(Refinement.Refined(Unit), ContainerQueryLease.whileIdle(container) { Refinement.Refined(Unit) })
        assertEquals(Refinement.Rejected(ContainerFailure.LEASE_CLOSED), first.prepare { error("closed preparation") })
    }

    @Test
    fun `issued authority cannot create or rebuild workspace state`() {
        val fixture = productFixture(temporary)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(workspace) as CanonicalRootDiscovery.Discovered).root
        val store = ContainerStore(fixture.runtime.root.parent.parent, fixture.runtime.manifest.platform)
        assertEquals(
            Refinement.Rejected(ContainerFailure.NOT_FOUND),
            ManagedWorkspaceQuery.openExisting(fixture.installation, root, emptyMap()),
        )
        assertFalse(Files.exists(store.containers))
        (ManagedWorkspaceQuery.open(fixture.installation, root, emptyMap()) as Refinement.Refined).value.close()
        val previous = (store.route(workspace) as Refinement.Refined).value
        assertEquals(
            Refinement.Refined(Unit),
            retireManagedWorkspaces(store, { Refinement.Refined(Unit) }) { Refinement.Refined(Unit) },
        )
        val generation = Files.readString(previous.directory.resolve("generation.json"))
        val replacement = productFixture(temporary, "replacement Host")
        assertEquals(
            Refinement.Rejected(ContainerFailure.RUNTIME_MISMATCH),
            ManagedWorkspaceQuery.openExisting(replacement.installation, root, emptyMap()),
        )
        assertEquals(generation, Files.readString(previous.directory.resolve("generation.json")))
        assertFalse(Files.exists(previous.directory.resolve("generations")))
        assertEquals(Refinement.Refined(Unit), ContainerQueryLease.whileIdle(previous) { Refinement.Refined(Unit) })
    }

    @Test
    fun `semantic use reconstructs retired generations without changing durable trust`() {
        val fixture = productFixture(temporary)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(workspace) as CanonicalRootDiscovery.Discovered).root
        val store = ContainerStore(fixture.runtime.root.parent.parent, fixture.runtime.manifest.platform)
        (ManagedWorkspaceQuery.open(fixture.installation, root, emptyMap()) as Refinement.Refined).value.close()
        val previous = (store.route(workspace) as Refinement.Refined).value
        val trust = Files.readString(previous.directory.resolve("trust.xml"))
        val durable = Files.readString(previous.directory.resolve("container.json"))
        assertEquals(
            Refinement.Refined(Unit),
            retireManagedWorkspaces(store, { Refinement.Refined(Unit) }) { Refinement.Refined(Unit) },
        )
        val replacement = productFixture(temporary, "replacement Host")
        (ManagedWorkspaceQuery.open(replacement.installation, root, emptyMap()) as Refinement.Refined).value.use { query
            ->
            assertNotEquals(previous.home, query.home)
            assertEquals(previous.gradleHome.toString(), query.environment["GRADLE_USER_HOME"])
            assertEquals(Refinement.Refined("prepared"), query.prepare { "prepared" })
            assertEquals(trust, Files.readString(previous.directory.resolve("trust.xml")))
            assertEquals(durable, Files.readString(previous.directory.resolve("container.json")))
            (ManagedWorkspaceQuery.openExisting(replacement.installation, root, emptyMap()) as Refinement.Refined)
                .value
                .use { retained ->
                    assertEquals(query.home, retained.home)
                }
        }
    }

    @Test
    fun `a foreign state selector cannot grant trust or bypass managed admission`() {
        val fixture = productFixture(temporary)
        val workspace = Files.createDirectory(temporary.resolve("workspace")).toRealPath()
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(workspace) as CanonicalRootDiscovery.Discovered).root
        assertEquals(
            Refinement.Rejected(ContainerFailure.FOREIGN_STATE),
            ManagedWorkspaceQuery.open(
                fixture.installation,
                root,
                mapOf("KAST_MANAGED_HEADLESS_STATE" to temporary.resolve("foreign").toString()),
            ),
        )
        assertFalse(Files.exists(fixture.runtime.root.parent.parent.resolve("containers")))
    }

    @Test
    fun `admission reclaims only vanished retired workspaces without leases or recovery evidence`() {
        val fixture = productFixture(temporary)
        val workspace = Files.createDirectory(temporary.resolve("vanished")).toRealPath()
        Files.writeString(workspace.resolve("settings.gradle.kts"), "")
        val root = (FilesystemCanonicalRootDiscovery.discover(workspace) as CanonicalRootDiscovery.Discovered).root
        val store = ContainerStore(fixture.runtime.root.parent.parent, fixture.runtime.manifest.platform)
        (ManagedWorkspaceQuery.open(fixture.installation, root, emptyMap()) as Refinement.Refined).value.close()
        val old = (store.select(ContainerId.forWorkspace(workspace).text) as Refinement.Refined).value
        assertEquals(Refinement.Refined(Unit), store.recordRetirement(old))
        Files.delete(workspace.resolve("settings.gradle.kts"))
        Files.delete(workspace)
        val other = Files.createDirectory(temporary.resolve("other")).toRealPath()
        Files.writeString(other.resolve("settings.gradle.kts"), "")
        val next = (FilesystemCanonicalRootDiscovery.discover(other) as CanonicalRootDiscovery.Discovered).root
        (ContainerQueryLease.acquire(old) as Refinement.Refined).value.use {
            (ManagedWorkspaceQuery.open(fixture.installation, next, emptyMap()) as Refinement.Refined).value.close()
            assertTrue(Files.exists(old.directory))
        }
        val evidence = Files.createDirectories(old.home.resolve(".kast/unknown")).resolve("pending")
        Files.writeString(evidence, "unresolved")
        (ManagedWorkspaceQuery.open(fixture.installation, next, emptyMap()) as Refinement.Refined).value.close()
        assertEquals("unresolved", Files.readString(evidence))
        Files.delete(evidence)
        Files.delete(evidence.parent)
        Files.delete(evidence.parent.parent)
        (ManagedWorkspaceQuery.open(fixture.installation, next, emptyMap()) as Refinement.Refined).value.close()
        assertFalse(Files.exists(old.directory))
        assertTrue(Files.exists(fixture.runtime.root))
        assertTrue(Files.isDirectory(other))
    }
}

@Serializable
private data class ProductReceiptFixture(
    val schemaVersion: Int,
    val installationRoot: String,
    val executable: String,
    val executableSha256: String,
    val product: InstalledProductReference,
    val registrations: List<ManagedRegistration>,
    val guidance: List<ManagedGuidance>,
)

internal fun writeProductManagement(installation: Path, executable: Path, reference: InstalledProductReference) {
    val receipt =
        ProductReceiptFixture(
            3,
            installation.parent.toString(),
            executable.toString(),
            sha256(executable),
            reference,
            emptyList(),
            emptyList(),
        )
    Files.writeString(receiptPath(installation.parent), containerJson.encodeToString(receipt))
}

internal data class InstalledRuntimeFixture(
    val installation: Path,
    val runtime: AdmittedContainerRuntime,
    val reference: InstalledProductReference,
)

internal fun writeProductRemovalFixture(fixture: InstalledRuntimeFixture) {
    for ((path, bytes) in
        listOf(
            "share/kast/install-candidate.sh" to "fixture installer",
            "share/kast/libexec/kast-service" to "fixture service",
            io.github.amichne.kast.distribution.contract.INSTALLATION_SHUTDOWN_CAPABILITY to "1\n",
        )) {
        val destination = fixture.installation.resolve(path)
        Files.createDirectories(destination.parent)
        Files.writeString(destination, bytes)
    }
    writeProductReference(fixture.installation, fixture.reference)
}

internal fun productFixture(temporary: Path, host: String = "fixture-not-a-plugin"): InstalledRuntimeFixture {
    val installation = Files.createDirectories(temporary.toRealPath().resolve("kast/installation"))
    val platform = (ContainerPlatform.current() as Refinement.Refined).value
    val store = ContainerStore(temporary.toRealPath().resolve("kast-workspaces"), platform)
    val runtime = containerRuntimeFixture(store, host, version = "0.0.123")
    val original = productEntry().record
    val record =
        original.copy(
            targets =
                original.targets.map { target ->
                    if (target.target != store.platform.publicName) target
                    else
                        target.copy(
                            runtime = target.runtime.copy(sha256 = runtime.archiveSha256),
                            qualification = target.qualification.copy(runtimeSha256 = runtime.archiveSha256),
                        )
                }
        )
    val metadata = Files.createDirectories(installation.resolve("share/kast"))
    Files.writeString(metadata.resolve("build-record.json"), containerJson.encodeToString(record))
    val target = record.targets.single { it.target == platform.publicName }
    val reference =
        InstalledProductReference(
            "developer-v0.0.123",
            null,
            record.version,
            record.sourceRevision,
            record.sourceTree,
            sha256(metadata.resolve("build-record.json")),
            store.platform.publicName,
            target.bundle.sha256,
            target.qualification.receipt.sha256,
            runtime.root.toString(),
            runtime.archiveSha256,
            sha256(runtime.root.resolve("manifest.json")),
            ProductSelectionPolicy.TrackMain,
        )
    writeProductReference(installation, reference)
    return InstalledRuntimeFixture(installation, runtime, reference)
}

private fun writeProductReference(installation: Path, reference: InstalledProductReference) {
    Files.writeString(
        installation.resolve("share/kast/product-selection.json"),
        containerJson.encodeToString(reference),
    )
    val files =
        listOf(
                "share/kast/build-record.json",
                "share/kast/product-selection.json",
                "share/kast/install-candidate.sh",
                "share/kast/libexec/kast-service",
                io.github.amichne.kast.distribution.contract.INSTALLATION_SHUTDOWN_CAPABILITY,
            )
            .filter { Files.exists(installation.resolve(it)) }
            .map {
                BundledPayload(it, "sha256:${sha256(installation.resolve(it))}", 420)
            }
    Files.writeString(
        installation.resolve("installation.json"),
        containerJson.encodeToString(BundledManifest(3, installation.toString(), files)),
    )
}
