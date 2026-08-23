package support.pr633

internal class NonEmptyEvidence<T> private constructor(
    val first: T,
    val additional: List<T>,
) {
    val values: List<T> = listOf(first) + additional

    companion object {
        /**
         * Proof-preserving construction: `(T, List<T>) -> NonEmptyEvidence<T>`.
         *
         * The required first value makes empty evidence unrepresentable; no failure state exists.
         */
        fun <T> from(first: T, additional: List<T>): NonEmptyEvidence<T> =
            NonEmptyEvidence(first, additional)
    }
}

internal class CheckedTopologyContractAbi private constructor(
    val entries: List<TopologyContractAbiEntry>,
) {
    companion object {
        /**
         * Proof transition: `List<String> -> CheckedTopologyContractAbiAdmission`.
         *
         * Establishes a normalized, non-empty, duplicate-free manifest projection. Raw lines are
         * extracted only at the Gradle file-input boundary. Empty or duplicate entries produce
         * closed rejected states carrying non-empty failure evidence.
         */
        fun parse(lines: List<String>): CheckedTopologyContractAbiAdmission {
            val entries = lines.map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .map(::TopologyContractAbiEntry)
            val duplicates = entries.groupingBy { it }.eachCount()
                .filterValues { count -> count > 1 }
                .keys
                .sortedBy(TopologyContractAbiEntry::value)
            return if (entries.isEmpty()) {
                CheckedTopologyContractAbiAdmission.EmptyManifest
            } else if (duplicates.isEmpty()) {
                CheckedTopologyContractAbiAdmission.Admitted(CheckedTopologyContractAbi(entries))
            } else {
                CheckedTopologyContractAbiAdmission.Rejected(
                    NonEmptyEvidence.from(duplicates.first(), duplicates.drop(1)),
                )
            }
        }
    }
}

internal sealed interface CheckedTopologyContractAbiAdmission {
    data class Admitted(
        internal val abi: CheckedTopologyContractAbi,
    ) : CheckedTopologyContractAbiAdmission
    data class Rejected(
        val duplicates: NonEmptyEvidence<TopologyContractAbiEntry>,
    ) : CheckedTopologyContractAbiAdmission
    data object EmptyManifest : CheckedTopologyContractAbiAdmission
}

internal sealed interface TopologyContractApiProblem {
    data object EmptyManifest : TopologyContractApiProblem
    data class DuplicateManifestEntries(
        val entries: NonEmptyEvidence<TopologyContractAbiEntry>,
    ) : TopologyContractApiProblem
    data class MissingEntries(
        val entries: NonEmptyEvidence<TopologyContractAbiEntry>,
    ) : TopologyContractApiProblem
    data class UnexpectedEntries(
        val entries: NonEmptyEvidence<TopologyContractAbiEntry>,
    ) : TopologyContractApiProblem
    data class ForbiddenClasses(
        val classes: NonEmptyEvidence<JvmClassName>,
    ) : TopologyContractApiProblem
    data class ForbiddenMethods(
        val methods: NonEmptyEvidence<JvmMethodIdentity>,
    ) : TopologyContractApiProblem

    fun display(): String = when (this) {
        EmptyManifest -> "checked topology contract ABI manifest is empty"
        is DuplicateManifestEntries ->
            "duplicate manifest entries: ${entries.values.map { it.value }}"
        is MissingEntries -> "missing entries: ${entries.values.map { it.value }}"
        is UnexpectedEntries -> "unexpected entries: ${entries.values.map { it.value }}"
        is ForbiddenClasses ->
            ":topology:contract exposes forbidden classes: ${classes.values.map { it.value }}"
        is ForbiddenMethods ->
            ":topology:contract exposes forbidden methods: ${methods.values.map { it.display() }}"
    }
}

internal sealed interface TopologyContractApiVerification {
    data object Verified : TopologyContractApiVerification
    data class Rejected(
        val firstProblem: TopologyContractApiProblem,
        val additionalProblems: List<TopologyContractApiProblem>,
        val observed: CompiledTopologyContractApi,
    ) : TopologyContractApiVerification {
        val problems: List<TopologyContractApiProblem> = listOf(firstProblem) + additionalProblems
    }
}
