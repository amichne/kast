package io.github.amichne.kast.cli.installation

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LegacyProfileSelectionTest {
    private val idea = Path.of("/Applications/IDE.app/Contents")
    private val properties =
        LegacyProfileProperties(
            ideaHome = null,
            configPath = null,
            pluginsPath = null,
            selector = "IntelliJIdea2026.2",
            vendor = "JetBrains",
            userHome = "/Users/owner",
            operatingSystem = "Mac OS X",
            customizer = null,
        )

    @Test
    fun `captured mac defaults establish profile without explicit config property`() {
        assertEquals(
            Refinement.Refined(
                LegacyProfilePaths(
                    idea,
                    Path.of("/Users/owner/Library/Application Support/JetBrains/IntelliJIdea2026.2"),
                    LegacyProfileSelectionKind.DEFAULT_MACOS,
                )
            ),
            selectLegacyProfile(idea, properties),
        )
    }

    @Test
    fun `explicit config overrides the default selector and preserves selected home`() {
        assertEquals(
            Refinement.Refined(
                LegacyProfilePaths(
                    idea,
                    Path.of("/Users/owner/custom-config"),
                    LegacyProfileSelectionKind.EXPLICIT_CONFIG,
                )
            ),
            selectLegacyProfile(idea, properties.copy(configPath = "/Users/owner/custom-config")),
        )
        assertEquals(
            Refinement.Rejected(LegacyProfileSelectionFailure.HOME_MISMATCH),
            selectLegacyProfile(idea, properties.copy(ideaHome = "/other/IDE")),
        )
    }

    @Test
    fun `unknown default facts and path customizers reject rather than guess`() {
        assertEquals(
            Refinement.Rejected(LegacyProfileSelectionFailure.DEFAULT_FACTS_UNAVAILABLE),
            selectLegacyProfile(idea, properties.copy(vendor = null)),
        )
        assertEquals(
            Refinement.Rejected(LegacyProfileSelectionFailure.UNSUPPORTED_OS),
            selectLegacyProfile(idea, properties.copy(operatingSystem = "Linux")),
        )
        assertEquals(
            Refinement.Rejected(LegacyProfileSelectionFailure.CUSTOMIZER_UNSUPPORTED),
            selectLegacyProfile(idea, properties.copy(customizer = "custom.Paths")),
        )
    }

    @Test
    fun `independent plugin override and unsafe selector reject`() {
        assertEquals(
            Refinement.Rejected(LegacyProfileSelectionFailure.PLUGINS_OVERRIDE),
            selectLegacyProfile(idea, properties.copy(pluginsPath = "/other/plugins")),
        )
        assertEquals(
            Refinement.Rejected(LegacyProfileSelectionFailure.DEFAULT_FACTS_UNAVAILABLE),
            selectLegacyProfile(idea, properties.copy(selector = "../foreign")),
        )
    }
}
