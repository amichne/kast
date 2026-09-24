package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.protocol.registry.AgentToolDefinition

/** Direct MCP owns its local one-step change tool; the App Server catalog entry is not invoked here. */
internal fun mcpVisibleDefinitions(definitions: List<AgentToolDefinition>): List<AgentToolDefinition> =
    definitions.filterNot {
        it.name.value == "change"
    }
