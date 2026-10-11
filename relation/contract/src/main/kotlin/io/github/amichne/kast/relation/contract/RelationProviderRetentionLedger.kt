package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

/** Request-local ordinal: carries no path, descriptor, payload or semantic authority. */
@JvmInline
value class RelationRetainedInventoryOwnerId private constructor(val value: Long) {
    internal companion object {
        fun issue(ordinal: Long): RelationRetainedInventoryOwnerId {
            check(ordinal > 0L)
            return RelationRetainedInventoryOwnerId(ordinal)
        }
    }
}

@JvmInline
value class RelationRetainedReferenceCount private constructor(val value: Long) {
    internal fun advance(): RelationRetainedReferenceCount = RelationRetainedReferenceCount(value.addStorageBytes(1L))

    internal companion object {
        val First = RelationRetainedReferenceCount(1L)
    }
}

/** One immutable inventory payload charge and every incoming reference to that exact JVM owner. */
class RelationInventoryRetentionCharge
internal constructor(
    val owner: RelationRetainedInventoryOwnerId,
    val inventory: RelationByteCount,
    val references: RelationRetainedReferenceCount,
) {
    val accountingMetadata: RelationByteCount = OWNER_LEDGER_BYTES.ledgerBytes()
    val referenceCells: RelationByteCount =
        (if (references.value > Long.MAX_VALUE / REFERENCE_BYTES) Long.MAX_VALUE
            else references.value * REFERENCE_BYTES)
            .ledgerBytes()
    val total: RelationByteCount =
        inventory.value.addStorageBytes(accountingMetadata.value).addStorageBytes(referenceCells.value).ledgerBytes()

    internal fun referenced(): RelationInventoryRetentionCharge =
        RelationInventoryRetentionCharge(owner, inventory, references.advance())
}

/** Immutable bounded snapshot. Each entry replaces the existing identity-map value within its 512-byte allowance. */
class RelationProviderRetentionLedger internal constructor(charges: List<RelationInventoryRetentionCharge>) {
    val charges: List<RelationInventoryRetentionCharge> = Collections.unmodifiableList(charges.toList())
    val total: RelationByteCount =
        charges.fold(0L) { sum, charge -> sum.addStorageBytes(charge.total.value) }.ledgerBytes()
}

internal fun Long.ledgerBytes(): RelationByteCount =
    when (val parsed = RelationByteCount.parse(this)) {
        is Refinement.Refined -> parsed.value
        is Refinement.Rejected -> error("Inventory accounting produced negative storage")
    }

internal const val OWNER_LEDGER_BYTES = 512L
internal const val REFERENCE_BYTES = 8L
