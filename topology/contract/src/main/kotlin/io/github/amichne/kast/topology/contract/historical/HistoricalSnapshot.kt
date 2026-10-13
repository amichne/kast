package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

enum class HistoricalAdmissionFailure {
    DUPLICATE_MODULE,
    DUPLICATE_FILE,
    DUPLICATE_DECLARATION,
    DUPLICATE_OCCURRENCE,
    DUPLICATE_DEPENDENCY,
    UNKNOWN_MODULE,
    ROOTLESS_FILE,
    UNKNOWN_FILE,
    INVALID_ENDPOINT,
    OWNER_MISMATCH,
    COVERAGE_DOMAIN,
    PROFILE_MISMATCH,
    INVENTORY_INCOMPLETE,
}

/** Detached producer inputs. Admission proves internal consistency, not an authentic compiler execution. */
data class HistoricalSnapshotInput(
    val header: HistoricalHeader,
    val modules: List<HistoricalModule>,
    val dependencies: List<HistoricalDependency>,
    val files: List<HistoricalFile>,
    val declarations: List<HistoricalDeclaration>,
    val occurrences: List<HistoricalOccurrence>,
    val coverage: List<HistoricalCoverage>,
) {
    fun admit(): Refinement<HistoricalSnapshot, HistoricalAdmissionFailure> = HistoricalSnapshot.admit(this)
}

class HistoricalSnapshot private constructor(input: HistoricalSnapshotInput) {
    val header = input.header
    val modules = immutable(input.modules.sortedBy { it.id })
    val dependencies = immutable(input.dependencies.sortedWith(compareBy({ it.source }, { it.target })))
    val files = immutable(input.files.sortedBy { it.path })
    val declarations = immutable(input.declarations.sortedBy { it.id })
    val occurrences = immutable(input.occurrences.sortedWith(HistoricalCanonicalEncoding.occurrenceOrder))
    val coverage = immutable(input.coverage.sortedWith(compareBy({ it.module }, { it.family })))
    val identity: HistoricalDigest = HistoricalCanonicalEncoding.digest(this)

    fun reference(id: HistoricalDeclarationId): Refinement<HistoricalDeclarationReference, HistoricalSelectionFailure> =
        if (declarations.any { it.id == id }) Refinement.Refined(HistoricalDeclarationReference(identity, id))
        else Refinement.Rejected(HistoricalSelectionFailure.DECLARATION_NOT_FOUND)

    companion object {
        fun admit(input: HistoricalSnapshotInput): Refinement<HistoricalSnapshot, HistoricalAdmissionFailure> {
            return when (val admitted = HistoricalSnapshotAdmission.admit(input)) {
                is Refinement.Refined -> Refinement.Refined(HistoricalSnapshot(input))
                is Refinement.Rejected -> admitted
            }
        }
    }
}

enum class HistoricalSelectionFailure {
    DECLARATION_NOT_FOUND,
    SNAPSHOT_MISMATCH,
}

/** A historical reference has no conversion to a live selector, lease, or mutation capability. */
data class HistoricalDeclarationReference
internal constructor(
    val snapshot: HistoricalDigest,
    val declaration: HistoricalDeclarationId,
) {
    companion object {
        /** The storage adapter calls this only after exact row admission in the named artifact. */
        fun fromStored(snapshot: HistoricalDigest, declaration: HistoricalDeclaration): HistoricalDeclarationReference =
            HistoricalDeclarationReference(snapshot, declaration.id)
    }
}

private fun <T> immutable(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
