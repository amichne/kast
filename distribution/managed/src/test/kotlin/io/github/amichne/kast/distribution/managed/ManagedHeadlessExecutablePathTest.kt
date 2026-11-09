package io.github.amichne.kast.distribution.managed

import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

class ManagedHeadlessExecutablePathTest {
    private val java = Path.of("/owned/runtime/jbr")

    @Test
    fun `cloud tools survive while bundled Java wins over caller Java`() {
        val path =
            assertInstanceOf<Refinement.Refined<ManagedHeadlessExecutablePath>>(
                    ManagedHeadlessExecutablePath.parse(
                        java,
                        "/cloud/python/bin:/foreign/java/bin:/usr/bin:/cloud/python/bin",
                    )
                )
                .value
        assertEquals("/owned/runtime/jbr/bin:/cloud/python/bin:/foreign/java/bin:/usr/bin", path.value)
    }

    @Test
    fun `missing caller path retains the minimal default`() {
        val path =
            assertInstanceOf<Refinement.Refined<ManagedHeadlessExecutablePath>>(
                    ManagedHeadlessExecutablePath.parse(java, null)
                )
                .value
        assertEquals("/owned/runtime/jbr/bin:/usr/bin:/bin", path.value)
    }

    @Test
    fun `implicit current directory relative paths and control characters reject`() {
        for (path in
            listOf(
                "",
                ":/usr/bin",
                "/usr/bin:",
                "/usr/bin::/bin",
                ".:/bin",
                "relative:/bin",
                "/a/../bin",
                "/bin\n/other",
                "/bin\u0000",
                "/bin\u007f",
            )) {
            assertEquals(
                Refinement.Rejected(ManagedHeadlessLaunchFailure.INVALID_EXECUTABLE_PATH),
                ManagedHeadlessExecutablePath.parse(java, path),
                path,
            )
        }
    }
}
