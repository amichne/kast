package io.github.amichne.kast.symbol.contract

import java.security.MessageDigest
import java.util.Base64

/** Snapshot-local declaration equality. This value cannot restore or broaden a scoped read capability. */
@JvmInline
value class CanonicalSymbolId private constructor(val value: String) {
    companion object {
        fun from(selector: SymbolSelector): CanonicalSymbolId {
            val fields =
                listOf(
                    "symbol-id-v1",
                    selector.lease.identity.workspaceRoot.value,
                    selector.lease.identity.revisionKey.value,
                    selector.file.stableValue,
                    selector.range.startInclusive.toString(),
                    selector.range.endExclusive.toString(),
                    selector.kind.name,
                    selector.compilerIdentity.value,
                )
            val canonical = buildString {
                fields.forEach { field ->
                    append(field.toByteArray(Charsets.UTF_8).size)
                    append(':')
                    append(field)
                }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return CanonicalSymbolId("sym:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
        }
    }
}
