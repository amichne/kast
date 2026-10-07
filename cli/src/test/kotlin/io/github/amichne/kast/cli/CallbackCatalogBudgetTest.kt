package io.github.amichne.kast.cli

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CallbackCatalogBudgetTest {
    @Test
    fun `complete callback catalog fits the unchanged packaged provider admission budget`() {
        val bytes = Json.encodeToString(PackagedProviderCatalog.document()).toByteArray(Charsets.UTF_8).size
        assertTrue(
            bytes <= PROVIDER_CATALOG_BYTES,
            "Packaged catalog uses $bytes bytes; admission permits $PROVIDER_CATALOG_BYTES",
        )
    }

    private companion object {
        const val PROVIDER_CATALOG_BYTES = 1_024 * 1_024
    }
}
