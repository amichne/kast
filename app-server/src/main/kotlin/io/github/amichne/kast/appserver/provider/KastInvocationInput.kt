package io.github.amichne.kast.appserver.provider

import io.github.amichne.kast.appserver.query.AdmittedPublicQuery
import io.github.amichne.kast.appserver.schema.ValidatedJsonValue
import io.github.amichne.kast.protocol.contract.CanonicalOperation

/** Keep the admitted query or exact schema proof until the subprocess boundary. */
internal sealed interface KastInvocationInput {
    data class Facade(val request: io.github.amichne.kast.appserver.query.AdmittedPublicTool) : KastInvocationInput

    data class Query(val request: AdmittedPublicQuery) : KastInvocationInput

    data class Canonical(
        val operation: CanonicalOperation,
        val arguments: ValidatedJsonValue,
    ) : KastInvocationInput
}
