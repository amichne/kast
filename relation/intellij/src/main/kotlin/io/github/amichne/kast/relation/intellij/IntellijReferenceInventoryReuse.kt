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

    fun callbackEvidence(domain: RelationScopeFingerprint): IntellijReferenceInventoryReuse

    data object Disabled : IntellijReferenceInventoryReuse {
        override fun find(request: RelationRequest, collector: IntellijRelationCollector) =
            ReferenceInventoryLookup.Miss

        override fun remember(request: RelationRequest, state: RelationProviderState) =
            ReferenceInventoryRetention.DISABLED

        override fun callbackEvidence(domain: RelationScopeFingerprint) = this
    }

    class Recent(private val limits: ReadLimits) : IntellijReferenceInventoryReuse {
        private var slot: Slot = Slot.Empty

        override fun find(request: RelationRequest, collector: IntellijRelationCollector): ReferenceInventoryLookup =
            find(request, collector, InventoryPurpose.SelectedDomain)

        override fun callbackEvidence(domain: RelationScopeFingerprint): IntellijReferenceInventoryReuse =
            EvidenceView(this, InventoryPurpose.CallbackEvidence(domain))

        @Synchronized
        private fun find(
            request: RelationRequest,
            collector: IntellijRelationCollector,
            purpose: InventoryPurpose,
        ): ReferenceInventoryLookup {
            val selected = slot
            if (selected !is Slot.Prepared || selected.key != Key.from(request, purpose))
                return ReferenceInventoryLookup.Miss
            return if (collector.retainProviderState(selected.state, preparedPartition = true))
                ReferenceInventoryLookup.Found(selected.state)
            else ReferenceInventoryLookup.Unavailable
        }

        override fun remember(request: RelationRequest, state: RelationProviderState): ReferenceInventoryRetention {
            return remember(request, state, InventoryPurpose.SelectedDomain)
        }

        @Synchronized
        private fun remember(
            request: RelationRequest,
            state: RelationProviderState,
            purpose: InventoryPurpose,
        ): ReferenceInventoryRetention {
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
                if (outcome == ReferenceInventoryRetention.RETAINED) Slot.Prepared(Key.from(request, purpose), state)
                else Slot.Empty
            return outcome
        }

        private class EvidenceView(
            private val owner: Recent,
            private val purpose: InventoryPurpose.CallbackEvidence,
        ) : IntellijReferenceInventoryReuse {
            override fun find(
                request: RelationRequest,
                collector: IntellijRelationCollector,
            ): ReferenceInventoryLookup =
                if (request.meaning == io.github.amichne.kast.relation.contract.RelationMeaning.Callers)
                    owner.find(request, collector, purpose)
                else ReferenceInventoryLookup.Miss

            override fun remember(request: RelationRequest, state: RelationProviderState): ReferenceInventoryRetention =
                if (request.meaning == io.github.amichne.kast.relation.contract.RelationMeaning.Callers)
                    owner.remember(request, state, purpose)
                else ReferenceInventoryRetention.PROVIDER_INELIGIBLE

            override fun callbackEvidence(domain: RelationScopeFingerprint): IntellijReferenceInventoryReuse =
                owner.callbackEvidence(domain)
        }
    }
}

private data class Key(
    val authority: SemanticReadAuthority,
    val subject: RelationEndpointFingerprint,
    val scope: RelationScopeFingerprint,
    val purpose: InventoryPurpose,
) {
    companion object {
        fun from(request: RelationRequest, purpose: InventoryPurpose) =
            Key(request.subject.lease, request.subject.fingerprint, request.scopeFingerprint, purpose)
    }
}

private sealed interface InventoryPurpose {
    data object SelectedDomain : InventoryPurpose

    data class CallbackEvidence(val domain: RelationScopeFingerprint) : InventoryPurpose
}

private sealed interface Slot {
    data object Empty : Slot

    data class Prepared(val key: Key, val state: RelationProviderState) : Slot
}

private const val SLOT_BYTES = 512L
