package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSources
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationStableConfigurationMigrationTest {
    @Test
    fun `migration observation contains only the known key version and admitted public count`() {
        val actual = Json.parseToJsonElement(InstallationConfigurationMigrationObservation(512).toJson()).jsonObject
        assertEquals(setOf("event", "sourceVersion", "key", "retiredModuleCount"), actual.keys)
        assertEquals("kast_installation_configuration_migration", actual.getValue("event").jsonPrimitive.content)
        assertEquals("0.50.2", actual.getValue("sourceVersion").jsonPrimitive.content)
        assertEquals("KAST_READ_MODEL_MODULES", actual.getValue("key").jsonPrimitive.content)
        assertEquals("512", actual.getValue("retiredModuleCount").jsonPrimitive.content)
    }

    @Test
    fun `stable materialized module default migrates only during installation`(@TempDir temporary: Path) {
        val prior = prior(temporary, "0.50.2")
        val environment = prior.resolve("config/environment")
        val original = "KAST_APP_SERVER_PUBLIC_ENDPOINT=private\nKAST_DEBUG=1\nKAST_READ_MODEL_MODULES=256\n"
        Files.writeString(environment, original)
        val selected =
            assertInstanceOf(
                Refinement.Refined::class.java,
                selectInstallationConfiguration(InstallationPublicEndpointSelection.Unspecified, prior),
            )
        val configuration = assertInstanceOf(InstallationConfigurationSelection.Prior::class.java, selected.value)
        val values = configuration.admission.configuration.launchEnvironment().variables
        assertEquals("1", values["KAST_DEBUG"])
        assertFalse(values.containsKey("KAST_READ_MODEL_MODULES"))
        assertEquals(original, Files.readString(environment))
        assertInstanceOf(
            Refinement.Rejected::class.java,
            ResolvedKastConfiguration.resolve(
                ConfigurationSources(savedInstallation = listOf("KAST_READ_MODEL_MODULES" to "256"))
            ),
        )
    }

    @Test
    fun `valid stable override preserves its migration fact`(@TempDir temporary: Path) {
        val prior = prior(temporary, "0.50.2")
        Files.writeString(prior.resolve("config/environment"), "KAST_DEBUG=1\nKAST_READ_MODEL_MODULES=512\n")
        val selected =
            selectInstallationConfiguration(InstallationPublicEndpointSelection.Unspecified, prior)
                as Refinement.Refined
        val configuration = selected.value as InstallationConfigurationSelection.Prior
        val migration = configuration.migration as InstallationConfigurationMigration.StableModuleCount
        assertEquals(512, migration.count.value)
        assertEquals("1", configuration.admission.configuration.launchEnvironment().variables["KAST_DEBUG"])
    }

    @Test
    fun `migration rejects unknown duplicate malformed and out of range inputs`(@TempDir temporary: Path) {
        val prior = prior(temporary, "0.50.2")
        for (content in
            listOf(
                "KAST_READ_MODEL_MODULES=256\nKAST_UNKNOWN_SETTING=1\n",
                "KAST_READ_MODEL_MODULES=256\nKAST_READ_MODEL_MODULES=256\n",
                "malformed record\nKAST_READ_MODEL_MODULES=256\n",
                "KAST_READ_MODEL_MODULES=0\n",
                "KAST_READ_MODEL_MODULES=2147483647\n",
                "KAST_READ_MODEL_MODULES=+512\n",
            )) {
            Files.writeString(prior.resolve("config/environment"), content)
            assertInstanceOf(
                Refinement.Rejected::class.java,
                selectInstallationConfiguration(InstallationPublicEndpointSelection.Unspecified, prior),
            )
            assertEquals(content, Files.readString(prior.resolve("config/environment")))
        }
    }

    @Test
    fun `another prior version cannot authorize the retired setting`(@TempDir temporary: Path) {
        val prior = prior(temporary, "1.2.3")
        Files.writeString(prior.resolve("config/environment"), "KAST_READ_MODEL_MODULES=256\n")
        assertInstanceOf(
            Refinement.Rejected::class.java,
            selectInstallationConfiguration(InstallationPublicEndpointSelection.Unspecified, prior),
        )
    }

    private fun prior(temporary: Path, version: String): Path {
        val root = Files.createDirectory(temporary.toRealPath().resolve("installation"))
        Files.createDirectory(root.resolve("config"))
        val manifest =
            InstallationManifest(
                semanticVersion = version,
                installationRoot = root.toString(),
                payloadIdentity = "sha256:" + "a".repeat(64),
                controlSha256 = "sha256:" + "a".repeat(64),
                codexHome = temporary.resolve("codex").toString(),
                configuration = root.resolve("config/environment").toString(),
                workspaceRegistry = root.resolve("config/workspaces.json").toString(),
                stateRoot = root.resolve("state").toString(),
                externalAnchors = emptyList(),
                payloadFiles = emptyList(),
            )
        Files.writeString(
            root.resolve("installation.json"),
            installationManifestJson.encodeToString(InstallationManifest.serializer(), manifest),
        )
        return root
    }
}
