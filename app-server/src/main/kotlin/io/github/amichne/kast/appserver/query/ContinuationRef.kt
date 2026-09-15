package io.github.amichne.kast.appserver.query

import io.github.amichne.kast.protocol.contract.ProtocolText
import kotlinx.serialization.Serializable

/**
 * An opaque public continuation input, distinct from a symbol reference in the generated syntax.
 *
 * The tool schema retains its existing length bound and nullable start control. Only the original
 * continuation owner can admit the retained pipeline and lifetime; this type does not decode either.
 */
@JvmInline
@Serializable
internal value class ContinuationRef(val token: ProtocolText)
