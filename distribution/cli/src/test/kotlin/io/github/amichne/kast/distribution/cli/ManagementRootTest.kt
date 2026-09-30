package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManagementRootTest {
    @Test
    fun `explicit install root selects the same custom installation as the installer`() {
        val selected =
            resolveManagementRoot(
                mapOf(
                    "HOME" to "/home/kast",
                    "XDG_DATA_HOME" to "/default/data",
                    "KAST_INSTALL_ROOT" to "/custom/kast",
                )
            )
                as ManagementRootResolution.Selected
        assertEquals(Path.of("/custom/kast"), selected.root)
        val internal = ManagementRootResolution.Selected.admit("/custom/kast") as ManagementRootResolution.Selected
        assertEquals(selected.root, internal.root)
    }

    @Test
    fun `relative explicit root rejects without selecting the default installation`() {
        assertEquals(
            ManagementRootResolution.Rejected(ManagementRootFailure.RELATIVE_PATH),
            resolveManagementRoot(mapOf("HOME" to "/home/kast", "KAST_INSTALL_ROOT" to "relative/kast")),
        )
    }

    @Test
    fun `empty explicit root rejects without selecting the default installation`() {
        assertEquals(
            ManagementRootResolution.Rejected(ManagementRootFailure.EMPTY_PATH),
            resolveManagementRoot(mapOf("HOME" to "/home/kast", "KAST_INSTALL_ROOT" to "")),
        )
    }

    @Test
    fun `unnormalized explicit root rejects without selecting the default installation`() {
        assertEquals(
            ManagementRootResolution.Rejected(ManagementRootFailure.UNNORMALIZED_PATH),
            resolveManagementRoot(mapOf("HOME" to "/home/kast", "KAST_INSTALL_ROOT" to "/custom/../kast")),
        )
    }

    @Test
    fun `absent explicit root retains XDG and home default selection`() {
        val xdg =
            resolveManagementRoot(mapOf("HOME" to "/home/kast", "XDG_DATA_HOME" to "/xdg/data"))
                as ManagementRootResolution.Selected
        assertEquals(Path.of("/xdg/data/kast"), xdg.root)
        val home = resolveManagementRoot(mapOf("HOME" to "/home/kast")) as ManagementRootResolution.Selected
        assertEquals(Path.of("/home/kast/.local/share/kast"), home.root)
    }
}
