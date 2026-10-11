package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.security.MessageDigest
import java.util.Collections

sealed interface RelationProviderConsumption {
    data class Confirmed(val occurrence: RelationReferenceOccurrence) : RelationProviderConsumption

    data class GraphConfirmed(val fact: RelationFact) : RelationProviderConsumption

    data object Unconfirmed : RelationProviderConsumption
}

@JvmInline
private value class RelationInventoryOrdinal(val value: Int) {
    fun advance(): RelationInventoryOrdinal = RelationInventoryOrdinal(value + 1)
}

enum class RelationProviderProgressFailure {
    EXHAUSTED,
    INVENTORY_MISMATCH,
    ORDINAL_NOT_ADVANCED,
    AUTHORITY_MISMATCH,
}

private sealed interface RelationProviderInventoryBasis {
    data object NativeDiscovery : RelationProviderInventoryBasis

    data class Published(val publication: RelationPublishedSnapshotIdentity) : RelationProviderInventoryBasis

    fun canonicalProjection(): String =
        when (this) {
            NativeDiscovery -> "native-discovery"
            is Published ->
                "published:${publication.lease.workspaceRoot.value}:${publication.lease.generation.value}:${publication.digest}"
        }

    val retainedBytes: Long
        get() =
            when (this) {
                NativeDiscovery -> 0L
                is Published -> 256L + canonicalProjection().length * 24L
            }
}

/** One immutable, complete provider inventory and its advancing detached ordinal. */
class RelationProviderState
private constructor(
    val providerCursor: RelationProviderCursor,
    private val locators: List<RelationProviderLocator>,
    private val nextLocator: RelationInventoryOrdinal,
    private val locatorDigest: String,
    private val inventoryBytes: Long,
    private val lastConfirmation: RelationProviderConsumption,
    private val basis: RelationProviderInventoryBasis,
) {
    val provider: RelationProviderKind
        get() = providerCursor.provider

    val consumedLocatorCount: RelationProviderPosition
        get() = RelationProviderPosition.parse(nextLocator.value.toLong()).refinedInvariant()

    val retainedBytes: Long
        get() = inventoryBytes + confirmationBytes

    /** Share the actual immutable inventory owner, while charging each advancing state and proof in full. */
    fun retainedBytes(graph: RelationProviderRetainedGraph): Long =
        graph
            .inventory(locators, inventoryBytes)
            .addStorageBytes(PROVIDER_STATE_STORAGE_BYTES)
            .addStorageBytes(confirmationBytes)

    private val confirmationBytes: Long
        get() =
            when (val proof = lastConfirmation) {
                is RelationProviderConsumption.Confirmed -> proof.occurrence.retainedBytes
                is RelationProviderConsumption.GraphConfirmed ->
                    REFERENCE_INVENTORY_PROOF_BYTES +
                        2L *
                            (proof.fact.subject.detachedTextUnits() +
                                proof.fact.source.detachedTextUnits() +
                                proof.fact.target.detachedTextUnits() +
                                proof.fact.canonicalProjection().length)
                RelationProviderConsumption.Unconfirmed -> 0L
            }

    val prepared: List<RelationProviderLocator>
        get() = locators.subList(nextLocator.value, locators.size)

    val hasUnfinishedWork: Boolean
        get() = nextLocator.value < locators.size

    fun confirmUnfinishedWork(): Refinement<RelationProviderState, RelationProviderProgressFailure> =
        if (hasUnfinishedWork) Refinement.Refined(this)
        else Refinement.Rejected(RelationProviderProgressFailure.EXHAUSTED)

    /** Even an empty published inventory retains the exact lease that established its snapshot authority. */
    fun confirmAuthority(
        authority: SemanticReadIdentity
    ): Refinement<RelationProviderState, RelationProviderProgressFailure> =
        when (val origin = basis) {
            RelationProviderInventoryBasis.NativeDiscovery -> Refinement.Refined(this)
            is RelationProviderInventoryBasis.Published ->
                if (origin.publication.lease.identity == authority) Refinement.Refined(this)
                else Refinement.Rejected(RelationProviderProgressFailure.AUTHORITY_MISMATCH)
        }

    /** The same immutable inventory proof must survive every resumed semantic page. */
    fun advanceFrom(
        previous: RelationProviderState
    ): Refinement<RelationProviderState, RelationProviderProgressFailure> =
        when {
            locators !== previous.locators -> Refinement.Rejected(RelationProviderProgressFailure.INVENTORY_MISMATCH)
            nextLocator.value <= previous.nextLocator.value ->
                Refinement.Rejected(RelationProviderProgressFailure.ORDINAL_NOT_ADVANCED)
            else -> confirmUnfinishedWork()
        }

    fun alreadyConfirmed(locator: RelationProviderLocator): Boolean =
        locator is RelationProviderLocator.Reference &&
            when (val proof = lastConfirmation) {
                is RelationProviderConsumption.Confirmed ->
                    proof.occurrence.occurrence.file == locator.file &&
                        proof.occurrence.occurrence.range == locator.range
                is RelationProviderConsumption.GraphConfirmed ->
                    proof.fact.occurrence.file == locator.file && proof.fact.occurrence.range == locator.range
                RelationProviderConsumption.Unconfirmed -> false
            }

    fun consume(
        confirmation: RelationProviderConsumption = RelationProviderConsumption.Unconfirmed
    ): RelationProviderState {
        check(nextLocator.value < locators.size)
        val confirmedLocation =
            when (confirmation) {
                is RelationProviderConsumption.Confirmed -> confirmation.occurrence.occurrence
                is RelationProviderConsumption.GraphConfirmed -> confirmation.fact.occurrence
                RelationProviderConsumption.Unconfirmed -> null
            }
        if (confirmedLocation != null) {
            check(
                confirmedLocation.file == locators[nextLocator.value].file &&
                    confirmedLocation.range == locators[nextLocator.value].range
            )
        }
        val proof = if (confirmation != RelationProviderConsumption.Unconfirmed) confirmation else lastConfirmation
        return RelationProviderState(
            providerCursor.advance(locators[nextLocator.value].descriptor),
            locators,
            nextLocator.advance(),
            locatorDigest,
            inventoryBytes,
            proof,
            basis,
        )
    }

    /** Inventories are hashed once; every consumed-item fingerprint is constant-size. */
    fun canonicalProjection(): String =
        "relation-provider-order-v1:${provider.name}:${basis.canonicalProjection()}:" +
            "$locatorDigest:${nextLocator.value}:" +
            when (val proof = lastConfirmation) {
                is RelationProviderConsumption.Confirmed -> proof.occurrence.canonicalProjection()
                is RelationProviderConsumption.GraphConfirmed -> proof.fact.canonicalProjection()
                RelationProviderConsumption.Unconfirmed -> "unconfirmed"
            }

    companion object {
        /** Called only after the original native ReferencesSearch reports exact exhaustion. */
        fun references(values: List<RelationProviderLocator.Reference>): RelationProviderState =
            inventory(
                RelationProviderKind.INTELLIJ_REFERENCES_V2,
                values,
                compareBy(
                    { it.file.stableValue },
                    { it.range.startInclusive },
                    { it.range.endExclusive },
                    { it.descriptor.value },
                ),
            )

        /** Called only after the original native DefinitionsScopedSearch reports exact exhaustion. */
        fun definitions(values: List<RelationProviderLocator.Definition>): RelationProviderState =
            inventory(RelationProviderKind.INTELLIJ_DEFINITIONS_V2, values, descriptorOrder())

        /** Called only after the selected subject's native call traversal reports exact exhaustion. */
        fun callees(values: List<RelationProviderLocator.Callee>): RelationProviderState =
            inventory(RelationProviderKind.INTELLIJ_CALLEES_V2, values, descriptorOrder())

        /** Called only after one exact published snapshot has yielded its complete admitted relation facts. */
        fun publishedFacts(
            publication: RelationPublishedSnapshotIdentity,
            facts: List<RelationFact>,
        ): Refinement<RelationProviderState, PublishedRelationFactFailure> {
            val values = mutableListOf<RelationProviderLocator.PublishedFact>()
            for (fact in facts) {
                when (val admitted = RelationProviderLocator.PublishedFact.admit(publication, fact)) {
                    is Refinement.Refined -> values += admitted.value
                    is Refinement.Rejected -> return admitted
                }
            }
            return Refinement.Refined(
                inventory(
                    RelationProviderKind.PUBLISHED_TOPOLOGY_V1,
                    values,
                    compareBy({ it.fact }, { it.descriptor.value }),
                    RelationProviderInventoryBasis.Published(publication),
                )
            )
        }

        private fun <Locator : RelationProviderLocator> descriptorOrder(): Comparator<Locator> =
            compareBy({ it.descriptor.value }, { it.canonicalIdentity() })

        private fun <Locator : RelationProviderLocator> inventory(
            provider: RelationProviderKind,
            values: List<Locator>,
            order: Comparator<Locator>,
            basis: RelationProviderInventoryBasis = RelationProviderInventoryBasis.NativeDiscovery,
        ): RelationProviderState {
            val ordered = values.sortedWith(order)
            val normalized = hashSetOf<RelationProviderItemDescriptor>()
            val snapshot =
                Collections.unmodifiableList(
                    if (provider == RelationProviderKind.INTELLIJ_DEFINITIONS_V2)
                        ordered.filter {
                            it !is RelationProviderLocator.Definition.Normalized || normalized.add(it.descriptor)
                        }
                    else ordered.distinctBy { it.canonicalIdentity() }
                )
            val inventoryDigest = digest(snapshot.joinToString("\u0000") { it.canonicalIdentity() })
            val cursor =
                RelationProviderCursor.start(provider)
                    .advance(
                        RelationProviderItemDescriptor.parse("prepared-inventory:${provider.name}:$inventoryDigest")
                            .refinedInvariant()
                    )
            return RelationProviderState(
                cursor,
                snapshot,
                RelationInventoryOrdinal(0),
                inventoryDigest,
                REFERENCE_INVENTORY_STRUCTURE_BYTES + locatorBytes(snapshot) + basis.retainedBytes,
                RelationProviderConsumption.Unconfirmed,
                basis,
            )
        }

        private fun locatorBytes(values: List<RelationProviderLocator>) = values.sumOf { it.retainedBytes }

        private fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") {
                "%02x".format(it)
            }
    }
}

private const val REFERENCE_INVENTORY_STRUCTURE_BYTES = 512L
private const val REFERENCE_INVENTORY_PROOF_BYTES = 1_024L
