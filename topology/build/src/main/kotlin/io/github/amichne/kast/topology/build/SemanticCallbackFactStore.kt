package io.github.amichne.kast.topology.build

import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.CallbackParameterSummary
import io.github.amichne.kast.relation.contract.CompleteCallbackSupplierInventory
import io.github.amichne.kast.relation.contract.CompleteNamedRelationPartition
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationScopeFingerprint
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import io.github.amichne.kast.relation.contract.ValueDeclarationIdentity
import io.github.amichne.kast.relation.contract.requiredEndpoints
import io.github.amichne.kast.relation.contract.requiredSourceFiles
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.topology.contract.SemanticDependencySnapshot
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.LiveSemanticReadFailure
import java.nio.file.Path

/**
 * Project-owned optional detached facts. Lock order is semantic owner, then this store; no native access under either.
 */
class SemanticCallbackFactStore(limits: ReadLimits) {
    private sealed interface Lifetime {
        data object Empty : Lifetime

        data class Owned(val authority: LiveSemanticReadAuthority) : Lifetime

        data object Retired : Lifetime
    }

    private data class FormalKey(
        val declaration: ValueDeclarationIdentity,
        val parameter: RelationOccurrence,
        val position: ValueArgumentPosition,
        val scope: SymbolSearchScope,
        val constraints: SymbolDiscoveryConstraints,
    )

    private data class Entry(
        val snapshot: SemanticDependencySnapshot,
        val summary: CallbackParameterSummary,
        val bytes: Long,
    )

    private data class SupplierKey(val formal: FormalKey, val domain: RelationScopeFingerprint)

    private data class SupplierEntry(
        val snapshot: SemanticDependencySnapshot,
        val inventory: CompleteCallbackSupplierInventory,
        val bytes: Long,
    )

    private data class NamedKey(
        val subject: ValueDeclarationIdentity,
        val meaning: RelationMeaning,
        val domain: RelationScopeFingerprint,
    )

    private data class NamedEntry(
        val snapshot: SemanticDependencySnapshot,
        val partition: CompleteNamedRelationPartition,
        val bytes: Long,
    )

    private val namedEntries = linkedMapOf<NamedKey, NamedEntry>()

    private val maximumEntries = limits[ReadLimitParameter.QUERY_CONTINUATION_ENTRIES].value
    private val maximumBytes = limits[ReadLimitParameter.QUERY_CONTINUATION_BYTES].value.toLong()
    private var lifetime: Lifetime = Lifetime.Empty
    private val entries = linkedMapOf<FormalKey, Entry>()
    private val supplierEntries = linkedMapOf<SupplierKey, SupplierEntry>()
    private var retainedBytes = 0L

    fun publish(snapshot: SemanticDependencySnapshot, summary: CallbackParameterSummary): SemanticCallbackPublication =
        when (val guarded = snapshot.authority.withCurrentOwner { publishCurrent(snapshot, summary) }) {
            is Refinement.Refined -> guarded.value
            is Refinement.Rejected ->
                SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.Authority(guarded.failure))
        }

    fun find(snapshot: SemanticDependencySnapshot, formal: CallbackParameterIdentity): SemanticCallbackLookup =
        when (val guarded = snapshot.authority.withCurrentOwner { findCurrent(snapshot, formal) }) {
            is Refinement.Refined -> guarded.value
            is Refinement.Rejected ->
                SemanticCallbackLookup.Rejected(SemanticCallbackStoreFailure.Authority(guarded.failure))
        }

    @Synchronized
    private fun publishCurrent(
        snapshot: SemanticDependencySnapshot,
        summary: CallbackParameterSummary,
    ): SemanticCallbackPublication {
        when (val owner = admitOwner(snapshot.authority)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return SemanticCallbackPublication.Rejected(owner.failure)
        }
        if (summary.formal.callable.lease !== snapshot.authority)
            return SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.BasisMismatch)
        val inventoried =
            snapshot.inventory.files.mapTo(hashSetOf()) {
                Path.of(snapshot.authority.workspaceRoot.value).resolve(it.path.value).toString()
            }
        if (
            summary.requiredEndpoints().any { it.lease !== snapshot.authority } ||
                summary.requiredSourceFiles().any { it.stableValue !in inventoried }
        )
            return SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.SourceOutsideInventory)
        val key = summary.formal.key()
        val bytes = summary.retainedBytes + snapshot.retainedBytes
        val existing = entries[key]
        val remainder = retainedBytes - (existing?.bytes ?: 0L)
        if (existing == null && entries.size + supplierEntries.size + namedEntries.size >= maximumEntries)
            return SemanticCallbackPublication.CapacityExceeded
        if (bytes < 0 || bytes > maximumBytes - remainder) return SemanticCallbackPublication.CapacityExceeded
        entries[key] = Entry(snapshot, summary, bytes)
        retainedBytes = remainder + bytes
        return SemanticCallbackPublication.Published
    }

    @Synchronized
    private fun findCurrent(
        snapshot: SemanticDependencySnapshot,
        formal: CallbackParameterIdentity,
    ): SemanticCallbackLookup {
        when (val owner = admitOwner(snapshot.authority)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return SemanticCallbackLookup.Rejected(owner.failure)
        }
        if (formal.callable.lease !== snapshot.authority)
            return SemanticCallbackLookup.Rejected(SemanticCallbackStoreFailure.BasisMismatch)
        val key = formal.key()
        val entry = entries[key] ?: return SemanticCallbackLookup.Missing
        return when (val proof = snapshot.reuseFrom(entry.snapshot)) {
            is Refinement.Refined ->
                if (snapshot.authority === entry.snapshot.authority) SemanticCallbackLookup.Current(entry.summary)
                else SemanticCallbackLookup.Reusable(entry.summary, proof.value)
            is Refinement.Rejected -> {
                entries.remove(key)
                retainedBytes -= entry.bytes
                SemanticCallbackLookup.Invalidated(proof.failure)
            }
        }
    }

    private fun admitOwner(authority: LiveSemanticReadAuthority): Refinement<Unit, SemanticCallbackStoreFailure> =
        when (val current = lifetime) {
            Lifetime.Empty -> {
                lifetime = Lifetime.Owned(authority)
                Refinement.Refined(Unit)
            }
            Lifetime.Retired -> Refinement.Rejected(SemanticCallbackStoreFailure.Retired)
            is Lifetime.Owned ->
                when (val same = authority.requireSameOwner(current.authority)) {
                    is Refinement.Refined -> same
                    is Refinement.Rejected -> Refinement.Rejected(SemanticCallbackStoreFailure.Authority(same.failure))
                }
        }

    fun publishSuppliers(
        snapshot: SemanticDependencySnapshot,
        inventory: CompleteCallbackSupplierInventory,
    ): SemanticCallbackPublication =
        when (val guarded = snapshot.authority.withCurrentOwner { publishSuppliersCurrent(snapshot, inventory) }) {
            is Refinement.Refined -> guarded.value
            is Refinement.Rejected ->
                SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.Authority(guarded.failure))
        }

    @Synchronized
    private fun publishSuppliersCurrent(
        snapshot: SemanticDependencySnapshot,
        inventory: CompleteCallbackSupplierInventory,
    ): SemanticCallbackPublication {
        when (val owner = admitOwner(snapshot.authority)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return SemanticCallbackPublication.Rejected(owner.failure)
        }
        if (snapshot.inventory.closure.modules != snapshot.inventory.closure.graph.modules)
            return SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.SupplierUniverseIncomplete)
        if (
            inventory.root.callable.lease !== snapshot.authority ||
                inventory.requiredEndpoints().any { it.lease !== snapshot.authority }
        )
            return SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.BasisMismatch)
        val sources =
            snapshot.inventory.files.mapTo(hashSetOf()) {
                Path.of(snapshot.authority.workspaceRoot.value).resolve(it.path.value).toString()
            }
        if (inventory.requiredSourceFiles().any { it.stableValue !in sources })
            return SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.SourceOutsideInventory)
        val key = SupplierKey(inventory.root.key(), inventory.domain)
        val bytes = inventory.retainedBytes + snapshot.retainedBytes
        val remainder = retainedBytes - (supplierEntries[key]?.bytes ?: 0L)
        if (key !in supplierEntries && entries.size + supplierEntries.size + namedEntries.size >= maximumEntries)
            return SemanticCallbackPublication.CapacityExceeded
        if (bytes < 0 || bytes > maximumBytes - remainder) return SemanticCallbackPublication.CapacityExceeded
        supplierEntries[key] = SupplierEntry(snapshot, inventory, bytes)
        retainedBytes = remainder + bytes
        return SemanticCallbackPublication.Published
    }

    fun findSuppliers(
        snapshot: SemanticDependencySnapshot,
        root: CallbackParameterIdentity,
        domain: RelationScopeFingerprint,
    ): SemanticCallbackSupplierLookup =
        when (val guarded = snapshot.authority.withCurrentOwner { findSuppliersCurrent(snapshot, root, domain) }) {
            is Refinement.Refined -> guarded.value
            is Refinement.Rejected ->
                SemanticCallbackSupplierLookup.Rejected(SemanticCallbackStoreFailure.Authority(guarded.failure))
        }

    @Synchronized
    private fun findSuppliersCurrent(
        snapshot: SemanticDependencySnapshot,
        root: CallbackParameterIdentity,
        domain: RelationScopeFingerprint,
    ): SemanticCallbackSupplierLookup {
        when (val owner = admitOwner(snapshot.authority)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return SemanticCallbackSupplierLookup.Rejected(owner.failure)
        }
        if (snapshot.inventory.closure.modules != snapshot.inventory.closure.graph.modules)
            return SemanticCallbackSupplierLookup.Rejected(SemanticCallbackStoreFailure.SupplierUniverseIncomplete)
        if (root.callable.lease !== snapshot.authority)
            return SemanticCallbackSupplierLookup.Rejected(SemanticCallbackStoreFailure.BasisMismatch)
        val key = SupplierKey(root.key(), domain)
        val entry = supplierEntries[key] ?: return SemanticCallbackSupplierLookup.Missing
        return when (val proof = snapshot.reuseFrom(entry.snapshot)) {
            is Refinement.Refined ->
                if (snapshot.authority === entry.snapshot.authority)
                    SemanticCallbackSupplierLookup.Current(entry.inventory)
                else SemanticCallbackSupplierLookup.Reusable(entry.inventory, proof.value)
            is Refinement.Rejected -> {
                supplierEntries.remove(key)
                retainedBytes -= entry.bytes
                SemanticCallbackSupplierLookup.Invalidated(proof.failure)
            }
        }
    }

    fun publishNamed(
        snapshot: SemanticDependencySnapshot,
        partition: CompleteNamedRelationPartition,
    ): SemanticCallbackPublication =
        when (val guarded = snapshot.authority.withCurrentOwner { publishNamedCurrent(snapshot, partition) }) {
            is Refinement.Refined -> guarded.value
            is Refinement.Rejected ->
                SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.Authority(guarded.failure))
        }

    @Synchronized
    private fun publishNamedCurrent(
        snapshot: SemanticDependencySnapshot,
        partition: CompleteNamedRelationPartition,
    ): SemanticCallbackPublication {
        when (val owner = admitOwner(snapshot.authority)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return SemanticCallbackPublication.Rejected(owner.failure)
        }
        if (
            partition.meaning == RelationMeaning.Callers &&
                snapshot.inventory.closure.modules != snapshot.inventory.closure.graph.modules
        )
            return SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.CallerUniverseIncomplete)
        if (partition.endpoints.any { it.lease !== snapshot.authority })
            return SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.BasisMismatch)
        val sources =
            snapshot.inventory.files.mapTo(hashSetOf()) {
                Path.of(snapshot.authority.workspaceRoot.value).resolve(it.path.value).toString()
            }
        if (partition.sourceFiles.any { it.stableValue !in sources })
            return SemanticCallbackPublication.Rejected(SemanticCallbackStoreFailure.SourceOutsideInventory)
        val key = NamedKey(partition.subject.valueIdentity, partition.meaning, partition.domain)
        val bytes = partition.retainedBytes + snapshot.retainedBytes
        val remainder = retainedBytes - (namedEntries[key]?.bytes ?: 0L)
        if (key !in namedEntries && entries.size + supplierEntries.size + namedEntries.size >= maximumEntries)
            return SemanticCallbackPublication.CapacityExceeded
        if (bytes < 0 || bytes > maximumBytes - remainder) return SemanticCallbackPublication.CapacityExceeded
        namedEntries[key] = NamedEntry(snapshot, partition, bytes)
        retainedBytes = remainder + bytes
        return SemanticCallbackPublication.Published
    }

    fun findNamed(snapshot: SemanticDependencySnapshot, request: RelationRequest): SemanticNamedRelationLookup =
        when (val guarded = snapshot.authority.withCurrentOwner { findNamedCurrent(snapshot, request) }) {
            is Refinement.Refined -> guarded.value
            is Refinement.Rejected ->
                SemanticNamedRelationLookup.Rejected(SemanticCallbackStoreFailure.Authority(guarded.failure))
        }

    @Synchronized
    private fun findNamedCurrent(
        snapshot: SemanticDependencySnapshot,
        request: RelationRequest,
    ): SemanticNamedRelationLookup {
        when (val owner = admitOwner(snapshot.authority)) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return SemanticNamedRelationLookup.Rejected(owner.failure)
        }
        if (
            request.position != RelationReadPosition.Start ||
                (request.meaning != RelationMeaning.Callers && request.meaning != RelationMeaning.Callees)
        )
            return SemanticNamedRelationLookup.Missing
        if (
            request.meaning == RelationMeaning.Callers &&
                snapshot.inventory.closure.modules != snapshot.inventory.closure.graph.modules
        )
            return SemanticNamedRelationLookup.Rejected(SemanticCallbackStoreFailure.CallerUniverseIncomplete)
        if (request.subject.lease !== snapshot.authority)
            return SemanticNamedRelationLookup.Rejected(SemanticCallbackStoreFailure.BasisMismatch)
        val key = NamedKey(request.subject.valueIdentity, request.meaning, request.scopeFingerprint)
        val entry = namedEntries[key] ?: return SemanticNamedRelationLookup.Missing
        if (!entry.partition.admits(request)) return SemanticNamedRelationLookup.Missing
        return when (val proof = snapshot.reuseFrom(entry.snapshot)) {
            is Refinement.Refined -> SemanticNamedRelationLookup.Reusable(entry.partition, proof.value)
            is Refinement.Rejected -> {
                namedEntries.remove(key)
                retainedBytes -= entry.bytes
                SemanticNamedRelationLookup.Invalidated(proof.failure)
            }
        }
    }

    @Synchronized
    fun retire() {
        lifetime = Lifetime.Retired
        entries.clear()
        supplierEntries.clear()
        namedEntries.clear()
        retainedBytes = 0
    }

    private fun CallbackParameterIdentity.key() =
        FormalKey(callable.valueIdentity, parameter, position, callable.scope, callable.constraints)
}

sealed interface SemanticCallbackStoreFailure {
    data object CallerUniverseIncomplete : SemanticCallbackStoreFailure

    data object SupplierUniverseIncomplete : SemanticCallbackStoreFailure

    data object Retired : SemanticCallbackStoreFailure

    data object BasisMismatch : SemanticCallbackStoreFailure

    data object SourceOutsideInventory : SemanticCallbackStoreFailure

    data class Authority(val cause: LiveSemanticReadFailure) : SemanticCallbackStoreFailure
}
