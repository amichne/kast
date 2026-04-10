import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DefaultTestTagSelectionTest {
    @Test
    fun `default test runs exclude opt-in suites`() {
        val selection = DefaultTestTagSelection.from(
            includeTags = null,
            excludeTags = null,
        )

        assertEquals(emptySet<String>(), selection.included)
        assertEquals(
            linkedSetOf("concurrency", "performance", "parity"),
            selection.excluded,
        )
    }

    @Test
    fun `explicit include tags suppress default exclusions`() {
        val selection = DefaultTestTagSelection.from(
            includeTags = "concurrency",
            excludeTags = null,
        )

        assertEquals(linkedSetOf("concurrency"), selection.included)
        assertEquals(emptySet<String>(), selection.excluded)
    }

    @Test
    fun `explicit excludes merge with default opt-in exclusions`() {
        val selection = DefaultTestTagSelection.from(
            includeTags = null,
            excludeTags = "slow",
        )

        assertEquals(emptySet<String>(), selection.included)
        assertEquals(
            linkedSetOf("concurrency", "performance", "parity", "slow"),
            selection.excluded,
        )
    }
}
