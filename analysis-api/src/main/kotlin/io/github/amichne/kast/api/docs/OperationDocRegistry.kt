package io.github.amichne.kast.api.docs

import io.github.amichne.kast.api.docs.internal.mutationOperationDocs
import io.github.amichne.kast.api.docs.internal.readOperationDocs
import io.github.amichne.kast.api.docs.internal.systemOperationDocs

/**
 * Registry of editorial documentation for every JSON-RPC operation.
 *
 * This object is the single source of truth for prose that accompanies each
 * operation in generated docs. It is intentionally separate from the OpenAPI
 * spec generator so editorial content can be refined without touching the
 * schema pipeline.
 */
object OperationDocRegistry {
    private val entries: Map<String, OperationDoc> = (
        systemOperationDocs() + readOperationDocs() + mutationOperationDocs()
    ).associateBy(OperationDoc::operationId)

    /** Returns the [OperationDoc] for the given [operationId], or null. */
    fun get(operationId: String): OperationDoc? = entries[operationId]

    /** Returns all registered operation docs. */
    fun all(): Collection<OperationDoc> = entries.values

    /** Returns all registered operation IDs. */
    fun operationIds(): Set<String> = entries.keys
}
