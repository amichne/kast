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
    @Test
    fun `unselected home configuration is never read`(@TempDir temporary: Path) {
        val home = temporary.toRealPath()
        val liveLookingConfig = Files.createDirectories(home.resolve(".kast/config")).resolve("environment")
        Files.writeString(liveLookingConfig, "KAST_INDEXER_MAX_HEAP=secret-invalid-input\n")
        val loaded =
            InstalledSavedConfigurationIngress.load(mapOf("HOME" to home.toString()))
                as SavedConfigurationIngress.Loaded
        assertEquals(SavedConfigurationObservation.ABSENT, loaded.observation)
        val resolved = ResolvedKastConfiguration.resolve(loaded.sources) as Refinement.Refined
        assertEquals(
            ConfigurationSource.DEFAULT,
            resolved.value.inspection().single { it.key == "KAST_INDEXER_MAX_HEAP" }.source,
        )
    }

    @Test
    fun `explicit saved selector rejects symlink and missing files`(@TempDir temporary: Path) {
        val directory = temporary.toRealPath()
        val target = Files.writeString(directory.resolve("real"), "KAST_INDEXER_MAX_HEAP=8g\n")
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
        val target = Files.writeString(directory.resolve("environment"), "KAST_INDEXER_MAX_HEAP=\$(touch $marker)\n")
        val loaded =
            InstalledSavedConfigurationIngress.read(target.toString(), emptyMap()) as SavedConfigurationIngress.Loaded
        assertTrue(ResolvedKastConfiguration.resolve(loaded.sources) is Refinement.Rejected)
        assertFalse(Files.exists(marker))
    }
}
