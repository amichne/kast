package io.github.amichne.kast.distribution.contract

import io.github.amichne.kast.distribution.contract.bootstrap.SemanticRuntimeBootstrapAttemptId
import io.github.amichne.kast.kernel.Refinement
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WireRuntimeQualificationLimitTest {
    @Test
    fun `qualification rejects an encoded document above its byte limit`() {
        val attempt =
            (SemanticRuntimeBootstrapAttemptId.admit("00000000-0000-4000-8000-000000000001") as Refinement.Refined)
                .value
        val identity =
            (WireRuntimeIdentity.admit(Path.of("/" + "é".repeat(9_000)), "sha256:" + "a".repeat(64), attempt)
                    as Refinement.Refined)
                .value
        val request = WireRuntimeQualification.request(identity)
        assertTrue(request.length < 16_384)
        assertTrue(request.toByteArray(Charsets.UTF_8).size > 16_384)
        assertEquals(WirePeerQualification.REJECTED, WireRuntimeQualification.admitRequest(request, identity))
    }
}
