package io.github.amichne.kast.change.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.SourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSourceContentHash
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity

/** Closed semantic change family. No raw text-edit intent can implement this interface. */
sealed interface ChangeIntent {
    class AddFile internal constructor(
        val target: CreatableKotlinFileTarget,
        val content: KotlinFileSourceText,
    ) : ChangeIntent

    class AddDeclaration internal constructor(
        val target: EditableMutationTarget,
        val declaration: AddDeclarationSourceText,
        val expectedDelta: ExpectedAddDeclarationDelta,
    ) : ChangeIntent

    class RenameSymbol internal constructor(
        val target: EditableMutationTarget,
        val newName: KotlinIdentifier,
        val occurrences: RenameSymbolOccurrenceSet,
    ) : ChangeIntent

    class ReplaceDeclaration internal constructor(
        val target: ReplaceDeclarationTarget,
        val replacement: ReplacementDeclarationSourceText,
    ) : ChangeIntent
}

/** Closed verification obligations retained by every semantic change plan. */
sealed interface ChangeVerificationObligation

enum class KotlinIdentifierFailure {
    INVALID,
    KEYWORD,
}

/** Conservative unquoted Kotlin identifier admitted for semantic mutation. */
@JvmInline
value class KotlinIdentifier private constructor(
    val value: String,
) {
    companion object {
        /**
         * Proof transition: `String -> Refinement<KotlinIdentifier, KotlinIdentifierFailure>`.
         *
         * Establishes a non-keyword unquoted Kotlin identifier. [KotlinIdentifierFailure] is the
         * closed expected failure. Raw text may enter only at a public change-intent boundary and
         * may leave only at an authority-bound IntelliJ mutation boundary.
         */
        fun parse(raw: String): Refinement<KotlinIdentifier, KotlinIdentifierFailure> = when {
            !IDENTIFIER.matches(raw) -> Refinement.Rejected(KotlinIdentifierFailure.INVALID)
            raw in KOTLIN_KEYWORDS -> Refinement.Rejected(KotlinIdentifierFailure.KEYWORD)
            else -> Refinement.Refined(KotlinIdentifier(raw))
        }
    }
}

enum class RenameSymbolOccurrenceFailure {
    RANGE_LENGTH_MISMATCH,
}

enum class RenameSymbolOccurrenceRole {
    DECLARATION,
    REFERENCE,
}

/** Exact compiler-grounded source occurrence of the symbol's current identifier. */
class RenameSymbolOccurrence private constructor(
    val source: SymbolDiscoveryFileIdentity.Workspace,
    val range: ExactDeclarationTextRange,
    val expectedName: KotlinIdentifier,
    val role: RenameSymbolOccurrenceRole,
) {
    companion object {
        /**
         * Proof transition: `(WorkspaceFile, ExactDeclarationTextRange, KotlinIdentifier,
         * RenameSymbolOccurrenceRole) -> Refinement<RenameSymbolOccurrence,
         * RenameSymbolOccurrenceFailure>`.
         *
         * Establishes an exact non-empty range whose UTF-16 length can contain precisely the
         * expected identifier. [RenameSymbolOccurrenceFailure] closes mismatch. Raw offsets may
         * enter only from compiler-grounded PSI evidence and leave only at mutation admission.
         */
        fun admit(
            source: SymbolDiscoveryFileIdentity.Workspace,
            range: ExactDeclarationTextRange,
            expectedName: KotlinIdentifier,
            role: RenameSymbolOccurrenceRole,
        ): Refinement<RenameSymbolOccurrence, RenameSymbolOccurrenceFailure> =
            if (range.endExclusive - range.startInclusive != expectedName.value.length) {
                Refinement.Rejected(RenameSymbolOccurrenceFailure.RANGE_LENGTH_MISMATCH)
            } else {
                Refinement.Refined(RenameSymbolOccurrence(source, range, expectedName, role))
            }
    }
}

enum class RenameSymbolOccurrenceSetFailure {
    EMPTY,
    DUPLICATE,
    OVERLAPPING,
    NON_DETERMINISTIC_ORDER,
    DECLARATION_OCCURRENCE_MISSING,
    DECLARATION_OCCURRENCE_AMBIGUOUS,
    DECLARATION_OCCURRENCE_OUTSIDE_TARGET,
    TARGET_NAME_MISMATCH,
    SOURCE_NOT_ADMITTED,
}

/** Non-empty exact occurrence set for one admitted symbol and source snapshot. */
class RenameSymbolOccurrenceSet private constructor(
    occurrences: List<RenameSymbolOccurrence>,
) {
    val occurrences: List<RenameSymbolOccurrence> = occurrences.toList()
    val currentName: KotlinIdentifier = occurrences.first().expectedName

    companion object {
        /**
         * Proof transition: `(EditableMutationTarget, List<RenameSymbolOccurrence>) ->
         * Refinement<RenameSymbolOccurrenceSet, RenameSymbolOccurrenceSetFailure>`.
         *
         * Establishes a unique, deterministic set containing exactly one declaration occurrence
         * inside the selected symbol plus zero or more reference occurrences, all bound to its
         * current compiler name and admitted source snapshot.
         * [RenameSymbolOccurrenceSetFailure] is the closed expected failure. Occurrence extraction
         * is permitted only at the compiler-backed planning boundary.
         */
        fun admit(
            target: EditableMutationTarget,
            occurrences: List<RenameSymbolOccurrence>,
        ): Refinement<RenameSymbolOccurrenceSet, RenameSymbolOccurrenceSetFailure> {
            if (occurrences.isEmpty()) {
                return Refinement.Rejected(RenameSymbolOccurrenceSetFailure.EMPTY)
            }
            val keys = occurrences.map { occurrence ->
                Triple(occurrence.source.path.value, occurrence.range.startInclusive, occurrence.range.endExclusive)
            }
            if (keys.distinct().size != keys.size) {
                return Refinement.Rejected(RenameSymbolOccurrenceSetFailure.DUPLICATE)
            }
            if (keys != keys.sortedWith(
                    compareBy<Triple<String, Int, Int>>(
                        { it.first },
                        { it.second },
                        { it.third })
                )
            ) {
                return Refinement.Rejected(RenameSymbolOccurrenceSetFailure.NON_DETERMINISTIC_ORDER)
            }
            if (occurrences.zipWithNext().any { (left, right) ->
                    left.source == right.source &&
                    left.range.endExclusive > right.range.startInclusive
                }
            ) {
                return Refinement.Rejected(RenameSymbolOccurrenceSetFailure.OVERLAPPING)
            }
            if (occurrences.any { it.source != target.file }) {
                return Refinement.Rejected(RenameSymbolOccurrenceSetFailure.SOURCE_NOT_ADMITTED)
            }
            if (occurrences.any { it.expectedName.value != target.selector.name.value }) {
                return Refinement.Rejected(RenameSymbolOccurrenceSetFailure.TARGET_NAME_MISMATCH)
            }
            val declarations = occurrences.filter {
                it.role == RenameSymbolOccurrenceRole.DECLARATION
            }
            if (declarations.isEmpty()) {
                return Refinement.Rejected(
                    RenameSymbolOccurrenceSetFailure.DECLARATION_OCCURRENCE_MISSING,
                )
            }
            if (declarations.size > 1) {
                return Refinement.Rejected(
                    RenameSymbolOccurrenceSetFailure.DECLARATION_OCCURRENCE_AMBIGUOUS,
                )
            }
            val declaration = declarations.single()
            if (
                declaration.range.startInclusive < target.range.startInclusive ||
                declaration.range.endExclusive > target.range.endExclusive
            ) {
                return Refinement.Rejected(
                    RenameSymbolOccurrenceSetFailure.DECLARATION_OCCURRENCE_OUTSIDE_TARGET,
                )
            }
            return Refinement.Refined(RenameSymbolOccurrenceSet(occurrences))
        }
    }
}

/** Closed source transformations derived by semantic planning. */
sealed interface SourceTextMutation {
    @ConsistentCopyVisibility
    data class CreateFile internal constructor(
        val content: KotlinFileSourceText,
    ) : SourceTextMutation

    @ConsistentCopyVisibility
    data class InsertAfterDeclaration internal constructor(
        val anchor: ExactDeclarationTextRange,
        val declaration: AddDeclarationSourceText,
    ) : SourceTextMutation

    @ConsistentCopyVisibility
    data class Replace internal constructor(
        val range: ExactDeclarationTextRange,
        val expected: KotlinIdentifier,
        val replacement: KotlinIdentifier,
    ) : SourceTextMutation

    @ConsistentCopyVisibility
    data class ReplaceDeclaration internal constructor(
        val range: ExactDeclarationTextRange,
        val expected: ExistingDeclarationSourceText,
        val replacement: ReplacementDeclarationSourceText,
    ) : SourceTextMutation
}

/** Closed physical precondition established before a planned source mutation. */
sealed interface PlannedSourcePrecondition {
    data class Existing(
        val content: WorkspaceSourceContentHash,
    ) : PlannedSourcePrecondition

    data object Absent : PlannedSourcePrecondition
}

class PlannedMutationWrite internal constructor(
    val source: SymbolDiscoveryFileIdentity.Workspace,
    val sourceRoot: SourceRoot,
    val precondition: PlannedSourcePrecondition,
    mutations: List<SourceTextMutation>,
) {
    val mutations: List<SourceTextMutation> = mutations.toList()
}

class PlannedMutationWriteSet private constructor(
    entries: List<PlannedMutationWrite>,
) {
    val entries: List<PlannedMutationWrite> = entries.toList()

    companion object {
        internal fun singleton(entry: PlannedMutationWrite): PlannedMutationWriteSet =
            PlannedMutationWriteSet(listOf(entry))
    }
}

/** Detached semantic plan consumed by the sole apply and verify protocols. */
sealed interface ChangePlan {
    val planId: ChangePlanId
    val intent: ChangeIntent
    val priorLease: SemanticReadLease
    val workspaceState: WorkspaceStateIdentity
    val writes: PlannedMutationWriteSet
}

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

private val KOTLIN_KEYWORDS = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if",
    "in", "interface", "is", "null", "object", "package", "return", "super", "this",
    "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while",
)
