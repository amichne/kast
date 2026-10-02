package io.github.amichne.kast.distribution.cli

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ManagementRootTest {
    @Test
    fun `explicit root binds the sole user installation`() {
        val selected =
            resolveManagementRoot(
                mapOf(
                    "HOME" to "/home/kast",
                    "XDG_DATA_HOME" to "/other/data",
                    "KAST_INSTALL_ROOT" to "/home/kast/.local/share/kast",
                )
            )
                as ManagementRootResolution.Selected
        assertEquals(Path.of("/home/kast/.local/share/kast"), selected.root)
    }

    @Test
    fun `alternate explicit root rejects rather than selecting another installation`() {
        assertEquals(
            ManagementRootResolution.Rejected(ManagementRootFailure.ALTERNATE_INSTALLATION),
            resolveManagementRoot(mapOf("HOME" to "/home/kast", "KAST_INSTALL_ROOT" to "/custom/kast")),
        )
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
    fun `XDG changes cannot select a second installation`() {
        val xdg =
            resolveManagementRoot(mapOf("HOME" to "/home/kast", "XDG_DATA_HOME" to "/xdg/data"))
                as ManagementRootResolution.Selected
        assertEquals(Path.of("/home/kast/.local/share/kast"), xdg.root)
        val home = resolveManagementRoot(mapOf("HOME" to "/home/kast")) as ManagementRootResolution.Selected
        assertEquals(Path.of("/home/kast/.local/share/kast"), home.root)
    }

    @Test
    fun `HOME must be admitted before deriving installation identity`() {
        assertEquals(
            ManagementRootResolution.Rejected(ManagementRootFailure.HOME_UNAVAILABLE),
            resolveManagementRoot(mapOf("KAST_INSTALL_ROOT" to "/custom/kast")),
        )
        assertEquals(
            ManagementRootResolution.Rejected(ManagementRootFailure.UNNORMALIZED_PATH),
            resolveManagementRoot(mapOf("HOME" to "/home/user/../kast")),
        )
    }
}
