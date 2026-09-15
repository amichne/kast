package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.Serializable

/**
 * An exact-reference input serialized as the existing opaque string, not as a token envelope.
 *
 * Construction retains bounded text and the caller's requested reference family, not proof of issuance.
 * The canonical reference owner still rejects wrong-family, malformed, stale, and foreign references.
 */
@JvmInline
@Serializable
internal value class ExactSymbolRef(val token: ProtocolText)
