package io.github.amichne.kast.cli.runtime.bootstrap

import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapCodec
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapPhase
import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapState
import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SidecarBootstrapStateFileTest {
    @Test
    fun `passive reader preserves phase and refuses oversized or redirected evidence`(@TempDir temporary: Path) {
        val path = temporary.toRealPath().resolve("bootstrap-state")
        val attempt = (SemanticRuntimeBootstrapAttemptId.admit(
            "123e4567-e89b-42d3-a456-426614174000",
        ) as Refinement.Refined).value
        val state = SemanticRuntimeBootstrapState.Starting(attempt, SemanticRuntimeBootstrapPhase.INDEXING)
        val encoded = SemanticRuntimeBootstrapCodec.encode(state)
        Files.writeString(path, encoded)
        assertEquals(SidecarBootstrapStateObservation.Observed(state), SidecarBootstrapStateFile.observe(path))
        assertEquals(encoded, Files.readString(path), "passive observation must never mutate the file")
        Files.writeString(path, " ".repeat(16 * 1024) + encoded)
        assertEquals(
            SidecarBootstrapStateObservation.Rejected(SidecarBootstrapStateFileFailure.PathRejected),
            SidecarBootstrapStateFile.observe(path),
        )
        Files.delete(path)
        val other = temporary.toRealPath().resolve("other")
        Files.writeString(other, encoded)
        Files.createSymbolicLink(path, other)
        assertInstanceOf(SidecarBootstrapStateObservation.Rejected::class.java, SidecarBootstrapStateFile.observe(path))
        assertEquals(encoded, Files.readString(other))
    }
}
