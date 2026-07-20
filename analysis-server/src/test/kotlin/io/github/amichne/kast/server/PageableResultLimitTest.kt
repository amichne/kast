package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.PageInfo
import io.github.amichne.kast.api.contract.PageableResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class PageableResultLimitTest {
    @Test
    fun `limit returns the original result when all items fit`() {
        val result = TestPageableResult(items = listOf(1, 2))

        assertSame(result, result.withLimit(2) { "2" })
    }

    @Test
    fun `limit truncates items and issues the next offset token`() {
        val result = TestPageableResult(items = listOf(1, 2, 3))
        var tokenItem: Int? = null

        val limited = result.withLimit(2) { item ->
            tokenItem = item
            "2"
        }

        assertEquals(2, tokenItem)
        assertEquals(
            TestPageableResult(
                items = listOf(1, 2),
                page = PageInfo(truncated = true, nextPageToken = "2"),
            ),
            limited,
        )
    }
}

private data class TestPageableResult(
    override val items: List<Int>,
    override val page: PageInfo? = null,
) : PageableResult<Int> {
    override fun withItems(items: List<Int>, page: PageInfo?): PageableResult<Int> = copy(
        items = items,
        page = page,
    )
}
