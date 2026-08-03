package io.github.amichne.kast.api.contract.result

import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.docs.DocField
import java.util.Collections
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class MutationSemanticGeneration(
    @DocField(description = "Semantic source generation that must still be current when the rename is used.")
    val value: Long,
) {
    init {
        require(value >= 0) { "Mutation semantic generation must be non-negative" }
    }
}

@Serializable
enum class RenameOccurrenceProvenance {
    COMPILER,
}

@Serializable
data class ExactRenameOccurrence(
    @DocField(description = "Exact source occurrence that must be rewritten by the rename.")
    val reference: ReferenceOccurrence,
    @DocField(description = "Compiler-resolved declaration identity to which this occurrence binds.")
    val resolvedTarget: SymbolIdentity,
    @DocField(description = "Authority that established the occurrence and its target binding.")
    val provenance: RenameOccurrenceProvenance,
)

@Serializable
class ExactRenameProof private constructor(
    @DocField(description = "Exact compiler-resolved identity of the declaration being renamed.")
    val target: SymbolIdentity,
    @DocField(description = "Semantic source generation required by this rename proof.")
    val requiredGeneration: MutationSemanticGeneration,
    @DocField(description = "Complete relationship coverage and exact occurrence cardinality.")
    @Serializable(with = RelationshipResultEvidence.CompleteSerializer::class)
    val evidence: RelationshipResultEvidence.Complete,
    @SerialName("occurrences")
    @DocField(description = "Compiler-proven reference occurrences bound to the target declaration.")
    private val storedOccurrences: List<ExactRenameOccurrence>,
) {
    val occurrences: List<ExactRenameOccurrence>
        get() = Collections.unmodifiableList(storedOccurrences)

    init {
        require(evidence.cardinality.totalCount == storedOccurrences.size) {
            "Exact rename cardinality must match the occurrence count"
        }
        require(storedOccurrences.all { occurrence -> occurrence.resolvedTarget == target }) {
            "Every exact rename occurrence must resolve to the target identity"
        }
        require(storedOccurrences.all { occurrence -> occurrence.provenance == RenameOccurrenceProvenance.COMPILER }) {
            "Every exact rename occurrence must have compiler provenance"
        }
        require(storedOccurrences.none { occurrence ->
            occurrence.reference.containingSymbol is ContainingSymbolEvidence.Unavailable
        }) {
            "Exact rename occurrences require compiler-visible containing-symbol evidence"
        }
        require(
            storedOccurrences.map { occurrence -> occurrence.reference.location.sourceRangeKey() }.distinct().size ==
                storedOccurrences.size,
        ) { "Exact rename occurrences must have unique source ranges" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExactRenameProof) return false

        return target == other.target &&
            requiredGeneration == other.requiredGeneration &&
            evidence == other.evidence &&
            storedOccurrences == other.storedOccurrences
    }

    override fun hashCode(): Int {
        var result = target.hashCode()
        result = 31 * result + requiredGeneration.hashCode()
        result = 31 * result + evidence.hashCode()
        result = 31 * result + storedOccurrences.hashCode()
        return result
    }

    override fun toString(): String =
        "ExactRenameProof(" +
            "target=$target, " +
            "requiredGeneration=$requiredGeneration, " +
            "evidence=$evidence, " +
            "occurrences=$storedOccurrences" +
            ")"

    companion object {
        fun of(
            target: SymbolIdentity,
            requiredGeneration: MutationSemanticGeneration,
            evidence: RelationshipResultEvidence.Complete,
            occurrences: List<ExactRenameOccurrence>,
        ): ExactRenameProof = ExactRenameProof(
            target = target,
            requiredGeneration = requiredGeneration,
            evidence = evidence,
            storedOccurrences = occurrences.toList(),
        )
    }
}

private fun io.github.amichne.kast.api.contract.Location.sourceRangeKey(): Triple<String, Int, Int> =
    Triple(filePath, startOffset, endOffset)
