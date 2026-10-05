package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.AgentToolName

/** Compiled registration authority and its deferred discovery projection belong to one owner. */
internal class DirectToolRegistration(names: Set<AgentToolName>, project: () -> List<DirectToolDocument>) {
    private val names = names.toSet()

    val catalog: List<DirectToolDocument> by lazy {
        project().also { documents ->
            check(documents.size == this.names.size)
            check(documents.map { it.name }.toSet() == this.names.map { it.value }.toSet())
        }
    }

    fun supports(raw: String): Boolean = names.any { it.value == raw }

    companion object {
        fun fromDocuments(documents: List<DirectToolDocument>) =
            DirectToolRegistration(documents.map { directToolName(it.name) }.toSet()) { documents }
    }
}

internal fun directToolName(raw: String): AgentToolName =
    when (val admitted = AgentToolName.parse(raw)) {
        is Refinement.Refined -> admitted.value
        is Refinement.Rejected -> error("An installed registration has an invalid tool name")
    }
