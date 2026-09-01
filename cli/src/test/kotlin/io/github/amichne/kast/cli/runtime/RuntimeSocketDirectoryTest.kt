package io.github.amichne.kast.cli

import io.github.amichne.kast.distribution.contract.SemanticRuntimeId
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class RuntimeSocketDirectoryTest {
    @Test
    fun `long logical runtime directory maps to a bounded physical socket path`(
        @TempDir temporary: Path,
    ) {
        val logicalDirectory = Path.of("/tmp").resolve(
            (1..24).joinToString("/") { segment -> "configured-runtime-$segment" },
        )
        val admitted = assertInstanceOf(
            InstalledRuntimeDirectoryAdmission.Admitted::class.java,
            InstalledRuntimeDirectory.admit(
                configured = logicalDirectory.toString(),
                temporaryDirectory = "/ignored",
            ),
        )
        val socketDirectory = RuntimeSocketDirectory.from(admitted.directory)
        val runtimeId = SemanticRuntimeId.parse("sha256:${"a".repeat(64)}").let {
            (it as Refinement.Refined).value
        }
        val endpoint = assertInstanceOf(
            RuntimeEndpointResolution.Resolved::class.java,
            Sha256RuntimeEndpointLocator(socketDirectory, runtimeId).locate(
                CanonicalRoot(temporary.toRealPath()),
            ),
        ).endpoint

        assertEquals(Path.of("/tmp"), socketDirectory.path.parent)
        assertNotEquals(logicalDirectory, socketDirectory.path)
        assertTrue(
            endpoint.socketPath.toString().toByteArray(StandardCharsets.UTF_8).size <=
                RuntimeSocketDirectory.MAXIMUM_ENDPOINT_PATH_BYTES,
            endpoint.socketPath.toString(),
        )
    }
}
