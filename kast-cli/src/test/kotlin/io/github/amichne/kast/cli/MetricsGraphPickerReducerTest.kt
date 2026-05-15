package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class MetricsGraphPickerReducerTest {
    @Test
    fun `reduceRefresh returns a new instance preserving query`() {
        val initial = MetricsGraphPicker.PickerState(query = "foo", selection = 0)
        val results = listOf("io.foo.A", "io.foo.B")

        val next = MetricsGraphPicker.reduceRefresh(initial, results)

        assertNotSame(initial, next, "reducer must return a fresh instance for animation diffing")
        assertEquals("foo", next.query)
        assertEquals(results, next.results)
        assertEquals(0, next.selection)
    }

    @Test
    fun `reduceRefresh clamps selection when results shrink`() {
        val initial = MetricsGraphPicker.PickerState(query = "foo", results = listOf("a", "b", "c"), selection = 2)

        val next = MetricsGraphPicker.reduceRefresh(initial, listOf("only"))

        assertEquals(0, next.selection)
        assertEquals(listOf("only"), next.results)
    }

    @Test
    fun `reduceRefresh clamps selection to 0 when results are empty`() {
        val initial = MetricsGraphPicker.PickerState(query = "x", results = listOf("a", "b"), selection = 1)

        val next = MetricsGraphPicker.reduceRefresh(initial, emptyList())

        assertEquals(0, next.selection)
        assertEquals(emptyList<String>(), next.results)
    }

    @Test
    fun `reduceRefresh preserves selection when still in range`() {
        val initial = MetricsGraphPicker.PickerState(query = "x", results = listOf("a", "b"), selection = 1)

        val next = MetricsGraphPicker.reduceRefresh(initial, listOf("a", "b", "c"))

        assertEquals(1, next.selection)
    }
}
