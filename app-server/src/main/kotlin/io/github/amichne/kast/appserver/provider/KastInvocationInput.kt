package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.query.PublicToolCanonical
import io.github.amichne.kast.appserver.schema.ValidatedJsonValue
import io.github.amichne.kast.protocol.contract.CanonicalOperation
import io.github.amichne.kast.protocol.contract.QueryExecutionModeDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest

/** Keep the admitted tool syntax or exact schema proof until the IDEA invocation boundary. */
internal sealed interface KastInvocationInput {
    data class Facade(val request: io.github.amichne.kast.appserver.query.AdmittedPublicTool) : KastInvocationInput

    data class Canonical(
        val operation: CanonicalOperation,
        val arguments: ValidatedJsonValue,
    ) : KastInvocationInput
}

internal fun KastInvocationInput.executionMode(): QueryExecutionModeDocument =
    when (this) {
        is KastInvocationInput.Facade ->
            when (val canonical = request.canonical) {
                is PublicToolCanonical.Query ->
                    when (val query = canonical.request) {
                        is QueryRunRequest.Run -> query.executionMode
                        is QueryRunRequest.ReadResult,
                        is QueryRunRequest.Resume -> QueryExecutionModeDocument.BOUNDED
                    }
                is PublicToolCanonical.Source,
                is PublicToolCanonical.Diagnostics,
                is PublicToolCanonical.Change -> QueryExecutionModeDocument.BOUNDED
            }
        is KastInvocationInput.Canonical -> QueryExecutionModeDocument.BOUNDED
    }
