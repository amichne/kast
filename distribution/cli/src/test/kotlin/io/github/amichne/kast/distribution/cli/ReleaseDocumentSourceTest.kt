package io.github.amichne.kast.distribution.cli

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReleaseDocumentSourceTest {
    @Test
    fun `unsupported URL retains open stage and finite failure without payloads`() {
        assertUnavailable("unsupported://private.invalid/secret", ReleaseReadFailure.URL_REJECTED)
    }

    @Test
    fun `relative URI retains open stage and finite failure without payloads`() {
        assertUnavailable("relative/private-secret", ReleaseReadFailure.INVALID_ARGUMENT)
    }

    private fun assertUnavailable(url: String, failure: ReleaseReadFailure) {
        val observations = mutableListOf<ReleaseReadObservation>()
        val expected = ReleaseReadObservation.Unavailable(ReleaseReadStage.OPEN, failure)
        assertEquals(ReleaseDocumentRead.Unavailable(expected), readReleaseDocumentObserved(url, observations::add))
        assertEquals(listOf(expected), observations)
        assertEquals(
            expected,
            managementJson.decodeFromString<ReleaseReadObservation>(
                managementJson.encodeToString<ReleaseReadObservation>(expected)
            ),
        )
    }
}
