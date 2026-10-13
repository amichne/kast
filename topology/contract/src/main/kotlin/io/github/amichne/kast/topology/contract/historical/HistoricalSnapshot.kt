package io.github.amichne.kast.topology.contract.historical

import io.github.amichne.kast.kernel.Refinement
import java.util.Collections

enum class HistoricalAdmissionFailure {
    INVALID_TEXT,
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

class HistoricalSnapshot private constructor(input: HistoricalSnapshotInput, val identity: HistoricalDigest) {
    val header = input.header
    val modules = immutable(input.modules)
    val dependencies = immutable(input.dependencies)
    val files = immutable(input.files)
    val declarations = immutable(input.declarations)
    val occurrences = immutable(input.occurrences)
    val coverage = immutable(input.coverage)

    fun reference(id: HistoricalDeclarationId): Refinement<HistoricalDeclarationReference, HistoricalSelectionFailure> =
        if (declarations.any { it.id == id }) Refinement.Refined(HistoricalDeclarationReference(identity, id))
        else Refinement.Rejected(HistoricalSelectionFailure.DECLARATION_NOT_FOUND)

    companion object {
        fun admit(input: HistoricalSnapshotInput): Refinement<HistoricalSnapshot, HistoricalAdmissionFailure> {
            when (val admitted = HistoricalSnapshotAdmission.admit(input)) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return admitted
            }
            val ordered =
                input.copy(
                    modules = input.modules.sortedBy { it.id },
                    dependencies = input.dependencies.sortedWith(compareBy({ it.source }, { it.target })),
                    files = input.files.sortedBy { it.path },
                    declarations = input.declarations.sortedBy { it.id },
                    occurrences = input.occurrences.sortedWith(HistoricalCanonicalEncoding.occurrenceOrder),
                    coverage = input.coverage.sortedWith(compareBy({ it.module }, { it.family })),
                )
            return when (val identity = HistoricalCanonicalEncoding.digest(HistoricalCanonicalEncoding.rows(ordered))) {
                is Refinement.Refined -> Refinement.Refined(HistoricalSnapshot(ordered, identity.value))
                is Refinement.Rejected -> Refinement.Rejected(HistoricalAdmissionFailure.INVALID_TEXT)
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
