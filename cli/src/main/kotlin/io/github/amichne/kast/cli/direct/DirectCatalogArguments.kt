package io.github.amichne.kast.cli.direct

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.registry.PublicToolCatalog

internal data class DirectCatalogArguments(val catalog: PublicToolCatalog, val command: List<String>)

internal enum class DirectCatalogArgumentFailure { INVALID_CATALOG }

/** A session captures one explicit catalog before discovery or invocation. */
internal fun selectDirectCatalog(args: List<String>): Refinement<DirectCatalogArguments, DirectCatalogArgumentFailure> {
    val first = args.firstOrNull()
    val selection = when (first) {
        "--catalog=COMMON" -> DirectCatalogArguments(PublicToolCatalog.COMMON, args.drop(1))
        "--catalog=COMPLETE" -> DirectCatalogArguments(PublicToolCatalog.COMPLETE, args.drop(1))
        else -> DirectCatalogArguments(PublicToolCatalog.COMMON, args)
    }
    return if (selection.command.any { it.startsWith("--catalog") })
        Refinement.Rejected(DirectCatalogArgumentFailure.INVALID_CATALOG)
    else Refinement.Refined(selection)
}
