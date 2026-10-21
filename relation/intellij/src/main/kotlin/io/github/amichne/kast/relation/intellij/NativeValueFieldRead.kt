package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.relation.contract.ValueTransfer

/** A projected field can exclude a different tracked field without an unresolved frontier. */
internal sealed interface NativeValueFieldRead {
    data class Transfer(val value: ValueTransfer) : NativeValueFieldRead

    data object DifferentField : NativeValueFieldRead
}
