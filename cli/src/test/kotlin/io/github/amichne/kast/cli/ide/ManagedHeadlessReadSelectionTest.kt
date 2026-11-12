package io.github.amichne.kast.cli.ide

import io.github.amichne.kast.appserver.ide.ExistingIdeExchange
import io.github.amichne.kast.appserver.ide.ExistingIdeFailure
import io.github.amichne.kast.appserver.ide.ExistingIdeOperation
import io.github.amichne.kast.distribution.managed.canonicalRootFixture
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ManagedHeadlessReadSelectionTest {
    @Test
    fun `existing-authority reads reject a managed state outside their HOME`(@TempDir directory: Path) {
        val home = directory.toRealPath()
        val client =
            configuredExistingIdeClient(
                home,
                mapOf(
                    "KAST_MANAGED_HEADLESS_STATE" to home.resolve("separate-state").toString(),
                    "KAST_INSTALL_IDEA_HOME" to home.resolve("idea").toString(),
                ),
            )
        assertEquals(
            ExistingIdeExchange.Rejected(ExistingIdeFailure.CONFIGURATION_REJECTED),
            client.query(canonicalRootFixture(home), ExistingIdeOperation.Status),
        )
    }
}
