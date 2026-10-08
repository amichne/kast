package io.github.amichne.kast.cli.installation

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.github.amichne.kast.distribution.managed.InstalledHostPluginTarget
import io.github.amichne.kast.distribution.managed.SelectedIdeInstallation
import io.github.amichne.kast.distribution.managed.SelectedIdeLaunch
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.wire.presentation.CanonicalJsonDocument
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationIdeSelectionTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `committed report retains saved target after mutable vendor profile changes`() {
        val fixture = fixture()
        val saved = SelectedIdeInstallation.resolve(fixture.ide) as SelectedIdeLaunch.Resolved
        val target =
            SelectedIdeInstallation.admitPluginTarget(fixture.plugin.toString(), fixture.home) as Refinement.Refined
        val recorded = SelectedIdeInstallation.recordInstalledHostTarget(saved, target.value)
        val record = fixture.installation.resolve("config/selected-ide.json")
        Files.createDirectories(record.parent)
        Files.writeString(record, Json.encodeToString<SelectedIdeLaunch>(recorded))
        Files.writeString(
            fixture.ide.resolve("Resources/product-info.json"),
            Json { encodeDefaults = true }.encodeToString(Product("NewProfile")),
        )
        val retained = installationReportIdeLaunch(fixture.request, fixture.installation, InstallationActivation.Ready)
        assertEquals(recorded, retained)
        val report =
            VerifiedInstallationPlan(fixture.request, fixture.request.controlDigest, fixture.installation)
                .report(InstallationActivation.Ready)
        assertEquals(recorded, report.ideaLaunch)
        val encoded = CanonicalJsonDocument.generated(InstallationReport.serializer()).create(report).value
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(
                    checkNotNull(javaClass.getResource("/management/installation-report.schema.json")).readText()
                )
        assertTrue(schema.validate(encoded, InputFormat.JSON).isEmpty(), encoded)
    }

    @Test
    fun `control-only selection preserves recorded target over the new request profile`() {
        val fixture = fixture()
        val launch = SelectedIdeInstallation.resolve(fixture.ide) as SelectedIdeLaunch.Resolved
        val target =
            SelectedIdeInstallation.admitPluginTarget(fixture.plugin.toString(), fixture.home) as Refinement.Refined
        val prior = SelectedIdeInstallation.recordInstalledHostTarget(launch, target.value)
        val request =
            InstallationRequest.parse(
                fixture.environment +
                    ("KAST_INSTALL_CONTROL_ONLY" to "1") +
                    ("KAST_INSTALL_IDEA_PLUGIN_ROOT" to
                        fixture.plugin.parent.parent.resolve("NewProfile/plugins").toString())
            ) as Refinement.Refined
        val saved = fixture.installation.resolve("config/selected-ide.json")
        Files.createDirectories(saved.parent)
        Files.writeString(saved, Json.encodeToString<SelectedIdeLaunch>(prior))
        assertEquals(
            prior,
            installationReportIdeLaunch(request.value, fixture.installation, InstallationActivation.Planned),
        )
        val selected = retainInstallationIdeLaunch(launch, prior, request.value) as Refinement.Refined
        assertEquals(prior, selected.value)
        val unspecified =
            retainInstallationIdeLaunch(
                launch,
                SelectedIdeInstallation.recordInstalledHostTarget(launch, InstalledHostPluginTarget.Unrecorded),
                request.value,
            )
                as Refinement.Refined
        assertEquals(
            InstalledHostPluginTarget.Unrecorded,
            (unspecified.value as SelectedIdeLaunch.Resolved).hostPluginTarget,
        )
    }

    @Test
    fun `saved selection writable by another owner cannot supply installed target proof`() {
        val fixture = fixture()
        val launch = SelectedIdeInstallation.resolve(fixture.ide)
        val target =
            SelectedIdeInstallation.admitPluginTarget(fixture.plugin.toString(), fixture.home) as Refinement.Refined
        val recorded = SelectedIdeInstallation.recordInstalledHostTarget(launch, target.value)
        val record = Files.createDirectories(fixture.installation.resolve("config")).resolve("selected-ide.json")
        Files.writeString(record, Json.encodeToString<SelectedIdeLaunch>(recorded))
        Files.setPosixFilePermissions(record, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"))
        assertEquals(
            SelectedIdeLaunch.Unavailable(
                io.github.amichne.kast.distribution.managed.IdeLaunchFailure.SAVED_SELECTION_REJECTED
            ),
            installationReportIdeLaunch(fixture.request, fixture.installation, InstallationActivation.Ready),
        )
        assertEquals(recorded, Json.decodeFromString<SelectedIdeLaunch>(Files.readString(record)))
    }

    @Test
    fun `same-version paired reuse rejects a different installer Host target without rewriting saved proof`() {
        val fixture = fixture()
        prepareIdeControlFacts(fixture.ide)
        val request = pairedReuseRequest(fixture, fixture.plugin)
        val initial = executeFixtureInstallation(request)
        assertInstanceOf(InstallationOutcome.Complete::class.java, initial, initial.toString())
        discardFixtureReplacementAfterSetup(request.installRoot.value)
        val record = fixture.installation.resolve("config/selected-ide.json")
        val saved = Files.readString(record)
        val newRoot = fixture.plugin.parent.parent.resolve("NewProfile/plugins")
        Files.writeString(
            fixture.ide.resolve("Resources/product-info.json"),
            Json { encodeDefaults = true }.encodeToString(Product("NewProfile")),
        )
        val changedRequest =
            InstallationRequest.parse(
                fixture.environment +
                    mapOf(
                        "KAST_INSTALL_CONTROL_ROOT" to request.controlRoot.value.toString(),
                        "KAST_INSTALL_CONTROL_ARCHIVE" to request.controlArchive.value.toString(),
                        "KAST_INSTALL_CONTROL_SHA256" to request.controlDigest.value,
                        "KAST_INSTALL_IDEA_PLUGIN_ROOT" to newRoot.toString(),
                    )
            ) as Refinement.Refined
        val outcome = executeFixtureInstallation(changedRequest.value)
        assertEquals(InstallationOutcome.Rejected(InstallationFailure.CONFIGURATION_REJECTED), outcome)
        assertEquals(saved, Files.readString(record))
    }

    @Test
    fun `paired installation rejects unavailable selected IDE metadata before recording an unremovable Host`() {
        val fixture = fixture()
        Files.delete(fixture.ide.resolve("Resources/product-info.json"))
        val selected = selectInstallationIdeLaunch(fixture.request, InstallationConfigurationSelection.FreshDefault)
        assertEquals(Refinement.Rejected(InstallationFailure.CONFIGURATION_REJECTED), selected)
        assertTrue(Files.notExists(fixture.installation))
    }

    private fun pairedReuseRequest(fixture: Fixture, plugin: Path): InstallationRequest =
        releaseRequest(
            temporary.toRealPath(),
            fixture.request.installRoot.value,
            fixture.request.binDirectory.value,
            fixture.home,
            fixture.home.resolve(".codex"),
            "1.2.3",
            environmentOverrides =
                mapOf(
                    "KAST_INSTALL_IDEA_HOME" to fixture.ide.toString(),
                    "KAST_INSTALL_JAVA_HOME" to fixture.ide.resolve("jbr/Contents/Home").toString(),
                    "KAST_INSTALL_IDEA_PLUGIN_ROOT" to plugin.toString(),
                ),
        )

    private fun prepareIdeControlFacts(ide: Path) {
        Files.createDirectories(ide.resolve("plugins/Kotlin"))
        Files.writeString(ide.resolve("Resources/build.txt"), "IU-262.1234.1")
        val java = Files.createDirectories(ide.resolve("jbr/Contents/Home/bin")).resolve("java")
        Files.writeString(java.parent.parent.resolve("release"), "JAVA_VERSION=\"25.0.1\"\n")
        Files.writeString(java, "#!/bin/sh\nexit 0\n")
        assertTrue(java.toFile().setExecutable(true))
    }

    @Test
    fun `unavailable current IDE metadata preserves the admitted install-time launch and Host target`() {
        val fixture = fixture()
        val launch = SelectedIdeInstallation.resolve(fixture.ide)
        val target =
            SelectedIdeInstallation.admitPluginTarget(fixture.plugin.toString(), fixture.home) as Refinement.Refined
        val prior = SelectedIdeInstallation.recordInstalledHostTarget(launch, target.value)
        Files.delete(fixture.ide.resolve("Resources/product-info.json"))
        val current = SelectedIdeInstallation.resolve(fixture.ide)
        assertTrue(current is SelectedIdeLaunch.Unavailable)
        val request =
            InstallationRequest.parse(fixture.environment + ("KAST_INSTALL_CONTROL_ONLY" to "1")) as Refinement.Refined
        val selected = retainInstallationIdeLaunch(current, prior, request.value) as Refinement.Refined
        assertEquals(prior, selected.value)
    }

    @Test
    fun `current encoded selections and legacy launch-only input preserve explicit target qualifications`() {
        val expected =
            Json.parseToJsonElement(
                    checkNotNull(javaClass.getResource("/installation/selected-ide-encoded-shapes.json")).readText()
                )
                .jsonArray
        val schema =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(checkNotNull(javaClass.getResource("/management/selected-ide.schema.json")).readText())
        for (shape in expected) {
            val decoded = Json.decodeFromString<SelectedIdeLaunch>(shape.toString())
            val encoded = Json.encodeToString<SelectedIdeLaunch>(decoded)
            assertEquals(shape, Json.parseToJsonElement(encoded))
            assertTrue(schema.validate(encoded, InputFormat.JSON).isEmpty(), encoded)
        }
        val legacy = checkNotNull(javaClass.getResource("/installation/selected-ide-legacy.json")).readText()
        assertTrue(schema.validate(legacy, InputFormat.JSON).isEmpty())
        val decoded = Json.decodeFromString<SelectedIdeLaunch>(legacy) as SelectedIdeLaunch.Resolved
        assertEquals(InstalledHostPluginTarget.Unrecorded, decoded.hostPluginTarget)
        assertEquals(expected[1], Json.parseToJsonElement(Json.encodeToString<SelectedIdeLaunch>(decoded)))
    }

    private fun fixture(): Fixture {
        val home = Files.createDirectory(temporary.resolve("home")).toRealPath()
        val ide = Files.createDirectories(temporary.resolve("Selected.app/Contents")).toRealPath()
        val metadata = Files.createDirectories(ide.resolve("Resources")).resolve("product-info.json")
        Files.writeString(metadata, Json { encodeDefaults = true }.encodeToString(Product("OldProfile")))
        val launcher = Files.createDirectories(ide.resolve("MacOS")).resolve("idea")
        Files.writeString(launcher, "fixture launcher")
        assertTrue(launcher.toFile().setExecutable(true))
        val plugin = home.resolve("Library/Application Support/JetBrains/OldProfile/plugins")
        val installation = home.resolve(".local/share/kast/installation")
        val environment =
            mapOf(
                "HOME" to home.toString(),
                "CODEX_HOME" to home.resolve(".codex").toString(),
                "KAST_INSTALL_ROOT" to installation.parent.toString(),
                "KAST_BIN_DIR" to home.resolve(".local/bin").toString(),
                "KAST_INSTALL_IDEA_HOME" to ide.toString(),
                "KAST_INSTALL_IDEA_PLUGIN_ROOT" to plugin.toString(),
                "KAST_INSTALL_JAVA_HOME" to ide.resolve("jbr/Contents/Home").toString(),
                "KAST_INSTALL_CONTROL_ROOT" to home.resolve("control").toString(),
                "KAST_INSTALL_CONTROL_ARCHIVE" to home.resolve("control.tar.gz").toString(),
                "KAST_INSTALL_CONTROL_SHA256" to "a".repeat(64),
                "KAST_INSTALL_VERSION" to "1.2.3",
                "KAST_INSTALL_MODE" to "apply",
            )
        val request = InstallationRequest.parse(environment) as Refinement.Refined
        return Fixture(home, ide, plugin, installation, environment, request.value)
    }

    private data class Fixture(
        val home: Path,
        val ide: Path,
        val plugin: Path,
        val installation: Path,
        val environment: Map<String, String>,
        val request: ControlInstallRequest,
    )

    @Serializable
    private data class Product(
        val dataDirectoryName: String,
        val buildNumber: String = "262.1234.1",
        val launch: List<Launch> = listOf(Launch()),
    )

    @Serializable
    private data class Launch(
        val os: String = "macOS",
        val arch: String = "aarch64",
        val launcherPath: String = "../MacOS/idea",
    )
}
