package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Path

class InstalledSidecarCacheRootTest {
    private val userHome = Path.of("/Users/kast-test")

    @Test
    fun `absent override derives the persistent cache below the admitted user home`() {
        val admitted = assertInstanceOf(
            InstalledSidecarCacheRootAdmission.Admitted::class.java,
            InstalledSidecarCacheRoot.admit(null, userHome),
        )

        assertEquals(
            userHome.resolve(".cache/kast/intellij-caches"),
            admitted.root.path,
        )
    }

    @Test
    fun `absolute override is normalized and retained as typed cache authority`() {
        val admitted = assertInstanceOf(
            InstalledSidecarCacheRootAdmission.Admitted::class.java,
            InstalledSidecarCacheRoot.admit("/tmp/kast-cache/../owned-cache", userHome),
        )

        assertEquals(Path.of("/tmp/owned-cache"), admitted.root.path)
    }

    @Test
    fun `blank relative and invalid overrides fail closed`() {
        listOf("", "relative/cache", "\u0000").forEach { configured ->
            assertEquals(
                InstalledSidecarCacheRootAdmission.Rejected(
                    InstalledSidecarCacheRootFailure.INVALID_PATH,
                ),
                InstalledSidecarCacheRoot.admit(configured, userHome),
            )
        }
    }
}
