package io.github.amichne.kast.appserver

import io.github.amichne.kast.distribution.contract.configuration.ConfigurationSource
import io.github.amichne.kast.distribution.contract.configuration.ResolvedKastConfiguration
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SavedConfigurationIngressTest {
    // Budget: one private root with only the selected physical file or rejected symlink.
    // No product, IDE or child process. Gradle/JUnit provisioning is outside this behavior budget.
    @Test
    fun `direct stable installation file loads saved values and preserves selector provenance`(
        @TempDir temporary: Path
    ) {
        val root = temporary.toRealPath().resolve("kast")
        val configuration = Files.createDirectories(root.resolve("installation/config")).resolve("environment")
        Files.writeString(configuration, "KAST_READ_HOST_REFERENCE_ENTRIES=8192\n")
        val environment = mapOf("KAST_CONFIGURATION_FILE" to configuration.toString())
        val loaded = InstalledSavedConfigurationIngress.load(environment) as SavedConfigurationIngress.Loaded
        assertEquals(SavedConfigurationObservation.LOADED, loaded.observation)
        assertEquals(listOf("KAST_READ_HOST_REFERENCE_ENTRIES" to "8192"), loaded.sources.savedInstallation)
        assertEquals(environment, loaded.sources.environment)
    }

    @Test
    fun `saved configuration rejects a symlinked installation ancestor`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val installation = root.resolve("physical-installation")
        val configuration = Files.createDirectories(installation.resolve("config")).resolve("environment")
        val original = "KAST_READ_HOST_REFERENCE_ENTRIES=8192\n"
        Files.writeString(configuration, original)
        val symbolic = Files.createSymbolicLink(root.resolve("current"), installation)
        val result =
            InstalledSavedConfigurationIngress.read(symbolic.resolve("config/environment").toString(), emptyMap())
                as SavedConfigurationIngress.Rejected
        assertEquals(
            SavedConfigurationIngressFailure.NOT_REGULAR,
            (result.rejection as SavedConfigurationIngressRejection.File).failure,
        )
        assertEquals(original, Files.readString(configuration))
        assertTrue(Files.isSymbolicLink(symbolic))
    }

    @Test
    fun `unselected home configuration is never read`(@TempDir temporary: Path) {
        val home = temporary.toRealPath()
        val liveLookingConfig = Files.createDirectories(home.resolve(".kast/config")).resolve("environment")
        Files.writeString(liveLookingConfig, "KAST_READ_HOST_REFERENCE_ENTRIES=secret-invalid-input\n")
        val loaded =
            InstalledSavedConfigurationIngress.load(mapOf("HOME" to home.toString()))
                as SavedConfigurationIngress.Loaded
        assertEquals(SavedConfigurationObservation.ABSENT, loaded.observation)
        val resolved = ResolvedKastConfiguration.resolve(loaded.sources) as Refinement.Refined
        assertEquals(
            ConfigurationSource.DEFAULT,
            resolved.value.inspection().single { it.key == "KAST_READ_HOST_REFERENCE_ENTRIES" }.source,
        )
    }

    @Test
    fun `explicit saved selector rejects symlink and missing files`(@TempDir temporary: Path) {
        val directory = temporary.toRealPath()
        val target = Files.writeString(directory.resolve("real"), "KAST_READ_HOST_REFERENCE_ENTRIES=8192\n")
        val alias = Files.createSymbolicLink(directory.resolve("alias"), target)
        val symbolic =
            InstalledSavedConfigurationIngress.read(alias.toString(), emptyMap()) as SavedConfigurationIngress.Rejected
        assertEquals("NOT_REGULAR", symbolic.rejection.reason())
        val missing =
            InstalledSavedConfigurationIngress.read(directory.resolve("missing").toString(), emptyMap())
                as SavedConfigurationIngress.Rejected
        assertEquals("UNAVAILABLE", missing.rejection.reason())
        assertFalse(Files.exists(directory.resolve("missing")))
    }

    @Test
    fun `workspace overlay cannot follow a symlinked configuration ancestor`(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val workspace = Files.createDirectory(root.resolve("workspace"))
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.createSymbolicLink(root.resolve("config"), outside)
        val result =
            InstalledWorkspaceConfigurationIngress.load(root, workspace, emptyMap())
                as SavedConfigurationIngress.Rejected
        assertEquals("NOT_REGULAR", result.rejection.reason())
        assertFalse(Files.exists(outside.resolve("workspaces")))
    }

    @Test
    fun `literal shell expressions are data and never executed`(@TempDir temporary: Path) {
        val directory = temporary.toRealPath()
        val marker = directory.resolve("executed")
        val target =
            Files.writeString(directory.resolve("environment"), "KAST_READ_HOST_REFERENCE_ENTRIES=\$(touch $marker)\n")
        val loaded =
            InstalledSavedConfigurationIngress.read(target.toString(), emptyMap()) as SavedConfigurationIngress.Loaded
        assertTrue(ResolvedKastConfiguration.resolve(loaded.sources) is Refinement.Rejected)
        assertFalse(Files.exists(marker))
    }
}
