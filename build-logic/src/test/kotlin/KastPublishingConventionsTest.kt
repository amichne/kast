import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KastPublishingConventionsTest {
    @Test
    fun `deriveKastModuleName converts artifact ids to title words`() {
        assertEquals(
            "Kast Analysis Api",
            deriveKastModuleName("kast-analysis-api"),
        )
        assertEquals(
            "Kast Index Store",
            deriveKastModuleName("kast.index_store"),
        )
    }
}
