package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.projection.cliName
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.RelationReadRejection
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.contract.SymbolInspectRejection
import io.github.amichne.kast.protocol.contract.TraversalRunRejection
import kotlinx.serialization.json.JsonObject

/** These read failures have one closed canonical enum, shared by their wire and CLI projections. */
internal fun canonicalReadRejectionSchema(operation: CanonicalOperation): JsonObject =
    when (operation) {
        CanonicalOperation.SOURCE_READ ->
            enumSchema(SourceReadRejection.entries.map { it.cliName() }, "Source read rejection.")
        CanonicalOperation.RELATION_READ ->
            enumSchema(RelationReadRejection.entries.map { it.cliName() }, "Relation read rejection.")
        CanonicalOperation.TRAVERSAL_RUN ->
            enumSchema(TraversalRunRejection.entries.map { it.cliName() }, "Traversal rejection.")
        CanonicalOperation.SYMBOL_INSPECT ->
            enumSchema(SymbolInspectRejection.entries.map { it.cliName() }, "Symbol inspection rejection.")
        CanonicalOperation.INDEX_SYNC,
        CanonicalOperation.TOPOLOGY_BUILD,
        CanonicalOperation.QUERY_RUN,
        CanonicalOperation.SYMBOL_DISCOVER,
        CanonicalOperation.DIAGNOSTIC_CHECK,
        CanonicalOperation.CHANGE_PLAN,
        CanonicalOperation.CHANGE_APPLY,
        CanonicalOperation.CHANGE_RECOVER -> textSchema("Closed rejection reason.")
    }
