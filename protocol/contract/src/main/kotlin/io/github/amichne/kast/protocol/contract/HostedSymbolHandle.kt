package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement

/** Representation proof only. The original host must resolve and revalidate the retained selector. */
data class HostedSymbolHandle private constructor(val token: ProtocolText, val family: HostedSymbolHandleFamily) {
    companion object {
        fun parse(token: ProtocolText): Refinement<HostedSymbolHandle, HostedSymbolHandleFailure> {
            val parts = token.value.split(':')
            if (
                parts.size != HANDLE_COMPONENT_COUNT ||
                    !when (parts[1]) {
                        "v4" -> parts[2].isCanonicalDigest()
                        "v5" -> parts[2].matches(Regex("[A-Za-z0-9_-]{21}[AQgw]"))
                        else -> false
                    }
            ) {
                return Refinement.Rejected(HostedSymbolHandleFailure.INVALID_STRUCTURE)
            }
            val family =
                when (parts[0]) {
                    "exact" -> HostedSymbolHandleFamily.EXACT
                    "candidate" -> HostedSymbolHandleFamily.CANDIDATE
                    else -> return Refinement.Rejected(HostedSymbolHandleFailure.INVALID_STRUCTURE)
                }
            return Refinement.Refined(HostedSymbolHandle(token, family))
        }
    }
}

enum class HostedSymbolHandleFamily {
    EXACT,
    CANDIDATE,
}

enum class HostedSymbolHandleFailure {
    INVALID_STRUCTURE
}

private const val SHA256_HEX_LENGTH = 64

private const val HANDLE_COMPONENT_COUNT = 3

private fun String.isCanonicalDigest(): Boolean = length == SHA256_HEX_LENGTH && all { it in "0123456789abcdef" }
