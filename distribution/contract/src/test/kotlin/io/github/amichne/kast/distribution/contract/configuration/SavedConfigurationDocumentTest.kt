package io.github.amichne.kast.distribution.contract.configuration

import io.github.amichne.kast.kernel.Refinement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class SavedConfigurationDocumentTest {
    @Test
    fun `literal parser retains duplicate assignments for closed resolution rejection`() {
        val parsed =
            SavedConfigurationDocument.parse("KAST_INDEXER_MAX_HEAP=8g\nKAST_INDEXER_MAX_HEAP=4g\n".toByteArray())
                as Refinement.Refined
        val result =
            ResolvedKastConfiguration.resolve(parsed.value.configurationSources(mapOf("KAST_INDEXER_MAX_HEAP" to "1g")))
                as Refinement.Rejected
        assertEquals(ConfigurationFailure.DUPLICATE_ASSIGNMENT, result.failure.reason)
    }

    @Test
    fun `malformed encoding and oversized documents fail before key resolution`() {
        assertEquals(
            Refinement.Rejected(SavedConfigurationDocumentFailure.INVALID_ENCODING),
            SavedConfigurationDocument.parse(byteArrayOf(0xC3.toByte(), 0x28)),
        )
        assertEquals(
            Refinement.Rejected(SavedConfigurationDocumentFailure.TOO_LARGE),
            SavedConfigurationDocument.parse(ByteArray(SavedConfigurationDocument.MAXIMUM_BYTES + 1)),
        )
        assertEquals(
            Refinement.Rejected(SavedConfigurationDocumentFailure.MALFORMED_RECORD),
            SavedConfigurationDocument.parse("export KAST_INDEXER_MAX_HEAP\n".toByteArray()),
        )
    }

    @Test
    fun `secret assignment values never appear in parser diagnostics`() {
        val parsed = SavedConfigurationDocument.parse("UNKNOWN=secret-canary\n".toByteArray()) as Refinement.Refined
        assertFalse(parsed.value.toString().contains("secret-canary"))
        val result =
            ResolvedKastConfiguration.resolve(parsed.value.configurationSources(emptyMap())) as Refinement.Rejected
        assertEquals(ConfigurationFailure.UNKNOWN_KEY, result.failure.reason)
        assertFalse(result.failure.toString().contains("secret-canary"))
    }
}
