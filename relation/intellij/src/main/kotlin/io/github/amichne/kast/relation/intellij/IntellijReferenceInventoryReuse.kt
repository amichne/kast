package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.relation.contract.RelationEndpointFingerprint
import io.github.amichne.kast.relation.contract.RelationProviderKind
import io.github.amichne.kast.relation.contract.RelationProviderState
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationScopeFingerprint
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority

internal sealed interface ReferenceInventoryLookup {
    data object Miss : ReferenceInventoryLookup

    data class Found(val state: RelationProviderState) : ReferenceInventoryLookup

    data object Unavailable : ReferenceInventoryLookup
}

internal enum class ReferenceInventoryRetention {
    DISABLED,
    RETAINED,
    PROVIDER_INELIGIBLE,
    CURSOR_ALREADY_ADVANCED,
    CAPACITY_EXCEEDED,
}

/** One invocation-owned detached inventory, never a semantic-result cache or a continuation store. */
internal sealed interface IntellijReferenceInventoryReuse {
    fun find(request: RelationRequest, collector: IntellijRelationCollector): ReferenceInventoryLookup

    fun remember(request: RelationRequest, state: RelationProviderState): ReferenceInventoryRetention

    data object Disabled : IntellijReferenceInventoryReuse {
        override fun find(request: RelationRequest, collector: IntellijRelationCollector) =
            ReferenceInventoryLookup.Miss

        override fun remember(request: RelationRequest, state: RelationProviderState) =
            ReferenceInventoryRetention.DISABLED
    }

    class Recent(private val limits: ReadLimits) : IntellijReferenceInventoryReuse {
        private var slot: Slot = Slot.Empty

        @Synchronized
        override fun find(request: RelationRequest, collector: IntellijRelationCollector): ReferenceInventoryLookup {
            val selected = slot
            if (selected !is Slot.Prepared || selected.key != Key.from(request)) return ReferenceInventoryLookup.Miss
            return if (collector.retainProviderState(selected.state, preparedPartition = true))
                ReferenceInventoryLookup.Found(selected.state)
            else ReferenceInventoryLookup.Unavailable
        }

        @Synchronized
        override fun remember(request: RelationRequest, state: RelationProviderState): ReferenceInventoryRetention {
            val outcome =
                when {
                    state.provider != RelationProviderKind.INTELLIJ_REFERENCES_V2 ->
                        ReferenceInventoryRetention.PROVIDER_INELIGIBLE
                    state.consumedLocatorCount.value != 0L -> ReferenceInventoryRetention.CURSOR_ALREADY_ADVANCED
                    state.retainedBytes >
                        limits[ReadLimitParameter.QUERY_CHECKPOINT_BYTES].value.toLong() - SLOT_BYTES ->
                        ReferenceInventoryRetention.CAPACITY_EXCEEDED
                    else -> ReferenceInventoryRetention.RETAINED
                }
            slot =
                if (outcome == ReferenceInventoryRetention.RETAINED) Slot.Prepared(Key.from(request), state)
                else Slot.Empty
            return outcome
        }
    }
}

private data class Key(
    val authority: SemanticReadAuthority,
    val subject: RelationEndpointFingerprint,
    val scope: RelationScopeFingerprint,
) {
    companion object {
        fun from(request: RelationRequest) =
            Key(request.subject.lease, request.subject.fingerprint, request.scopeFingerprint)
    }
}

private sealed interface Slot {
    data object Empty : Slot

    data class Prepared(val key: Key, val state: RelationProviderState) : Slot
}

private const val SLOT_BYTES = 512L
