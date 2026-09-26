package io.github.amichne.kast.cli

import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.SourceReadRejection
import io.github.amichne.kast.protocol.wire.presentation.cliName
import kotlinx.serialization.json.JsonObject

/** These read failures have one closed canonical enum, shared by their wire and CLI projections. */
internal fun canonicalReadRejectionSchema(operation: CanonicalOperation): JsonObject =
    when (operation) {
        CanonicalOperation.SOURCE_READ ->
            unionSchema(
                enumSchema(SourceReadRejection.entries.map { it.cliName() }, "Source read rejection."),
                generatedRequestSchema(io.github.amichne.kast.protocol.contract.SourceReadFailureDetail.serializer()),
            )
        CanonicalOperation.WORKSPACE_LIFECYCLE,
        CanonicalOperation.INDEX_SYNC,
        CanonicalOperation.TOPOLOGY_BUILD,
        CanonicalOperation.QUERY_RUN,
        CanonicalOperation.DIAGNOSTIC_CHECK,
        CanonicalOperation.CHANGE,
        CanonicalOperation.CHANGE_PLAN,
        CanonicalOperation.CHANGE_APPLY,
        CanonicalOperation.CHANGE_RECOVER -> textSchema("Closed rejection reason.")
    }
