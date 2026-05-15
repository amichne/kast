package io.github.amichne.kast.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MetricsGraphNavigatorTest {
    @Test
    fun `reduce navigates parent child and siblings`() {
        val navigator = MetricsGraphNavigator(sampleMetricsGraph())
        val childCursor = MetricsGraphCursor(currentNodeId = "child-2")

        assertEquals(
            MetricsGraphCursor(currentNodeId = "root"),
            navigator.reduce(childCursor, GraphAction.Parent),
        )
        assertEquals(
            MetricsGraphCursor(currentNodeId = "leaf"),
            navigator.reduce(MetricsGraphCursor(currentNodeId = "child-3"), GraphAction.FirstChild),
        )
        assertEquals(
            MetricsGraphCursor(currentNodeId = "child-1"),
            navigator.reduce(childCursor, GraphAction.PreviousSibling),
        )
        assertEquals(
            MetricsGraphCursor(currentNodeId = "child-3"),
            navigator.reduce(childCursor, GraphAction.NextSibling),
        )
    }

    @Test
    fun `reduce wraps siblings and keeps cursor on root and leaf boundaries`() {
        val navigator = MetricsGraphNavigator(sampleMetricsGraph())

        assertEquals(
            MetricsGraphCursor(currentNodeId = "child-3"),
            navigator.reduce(MetricsGraphCursor(currentNodeId = "child-1"), GraphAction.PreviousSibling),
        )
        assertEquals(
            MetricsGraphCursor(currentNodeId = "child-1"),
            navigator.reduce(MetricsGraphCursor(currentNodeId = "child-3"), GraphAction.NextSibling),
        )
        assertEquals(
            MetricsGraphCursor(currentNodeId = "root"),
            navigator.reduce(MetricsGraphCursor(currentNodeId = "root"), GraphAction.Parent),
        )
        assertEquals(
            MetricsGraphCursor(currentNodeId = "leaf"),
            navigator.reduce(MetricsGraphCursor(currentNodeId = "leaf"), GraphAction.FirstChild),
        )
    }

    @Test
    fun `reduce toggles attribute visibility without moving`() {
        val navigator = MetricsGraphNavigator(sampleMetricsGraph())

        assertEquals(
            MetricsGraphCursor(currentNodeId = "child-2", showAttributes = false),
            navigator.reduce(MetricsGraphCursor(currentNodeId = "child-2"), GraphAction.ToggleAttributes),
        )
        assertEquals(
            MetricsGraphCursor(currentNodeId = "child-2", showAttributes = true),
            navigator.reduce(
                MetricsGraphCursor(currentNodeId = "child-2", showAttributes = false),
                GraphAction.ToggleAttributes,
            ),
        )
    }

}
