package io.github.amichne.kast.cli

import io.github.amichne.kast.indexstore.MetricsGraph
import io.github.amichne.kast.indexstore.MetricsGraphEdge
import io.github.amichne.kast.indexstore.MetricsGraphEdgeType
import io.github.amichne.kast.indexstore.MetricsGraphIndex
import io.github.amichne.kast.indexstore.MetricsGraphNode
import io.github.amichne.kast.indexstore.MetricsGraphNodeType

internal fun sampleMetricsGraph(): MetricsGraph {
    return MetricsGraph(
        focalNodeId = "root",
        nodes = listOf(
            MetricsGraphNode(
                id = "root",
                name = "io.github.amichne.kast.Root",
                type = MetricsGraphNodeType.SYMBOL,
                children = listOf("child-1", "child-2", "child-3"),
                attributes = listOf("root attr"),
            ),
            MetricsGraphNode(
                id = "child-1",
                name = "firstChild",
                type = MetricsGraphNodeType.SYMBOL,
                parentId = "root",
                attributes = listOf("first attr"),
            ),
            MetricsGraphNode(
                id = "child-2",
                name = "secondChild",
                type = MetricsGraphNodeType.SYMBOL,
                parentId = "root",
                attributes = listOf("second attr"),
            ),
            MetricsGraphNode(
                id = "child-3",
                name = "thirdChild",
                type = MetricsGraphNodeType.SYMBOL,
                parentId = "root",
                children = listOf("leaf"),
            ),
            MetricsGraphNode(
                id = "leaf",
                name = "leaf",
                type = MetricsGraphNodeType.FILE,
                parentId = "child-3",
            ),
        ),
        edges = listOf(
            MetricsGraphEdge("root", "child-1", MetricsGraphEdgeType.CONTAINS),
            MetricsGraphEdge("root", "child-2", MetricsGraphEdgeType.CONTAINS),
            MetricsGraphEdge("root", "child-3", MetricsGraphEdgeType.CONTAINS),
            MetricsGraphEdge("child-3", "leaf", MetricsGraphEdgeType.CONTAINS),
            MetricsGraphEdge("root", "child-2", MetricsGraphEdgeType.REFERENCED_BY, weight = 2),
        ),
        index = MetricsGraphIndex(
            symbolCount = 4,
            fileCount = 1,
            referenceCount = 2,
            maxDepth = 2,
        ),
    )
}
