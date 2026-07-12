package io.github.amichne.kast.shared.proofloss.model

import io.github.amichne.kast.api.contract.NonEmptyList

sealed interface ProofTextParseResult<out T> {
    data class Valid<T>(val value: T) : ProofTextParseResult<T>
    data object Blank : ProofTextParseResult<Nothing>
}

@JvmInline
value class PredicateId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ProofTextParseResult<PredicateId> = parseText(raw, ::PredicateId)
    }
}

@JvmInline
value class BoundaryId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ProofTextParseResult<BoundaryId> = parseText(raw, ::BoundaryId)
    }
}

@JvmInline
value class CallableIdKey private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ProofTextParseResult<CallableIdKey> = parseText(raw, ::CallableIdKey)
    }
}

@JvmInline
value class KotlinTypeKey private constructor(val value: String) {
    companion object {
        fun parse(raw: String): ProofTextParseResult<KotlinTypeKey> = parseText(raw, ::KotlinTypeKey)
    }
}

private inline fun <T> parseText(
    raw: String,
    construct: (String) -> T,
): ProofTextParseResult<T> =
    raw.trim().takeIf(String::isNotEmpty)?.let { ProofTextParseResult.Valid(construct(it)) }
    ?: ProofTextParseResult.Blank

sealed interface ArgumentIndexParseResult {
    data class Valid(val value: ArgumentIndex) : ArgumentIndexParseResult
    data class Negative(val value: Int) : ArgumentIndexParseResult
}

@JvmInline
value class ArgumentIndex private constructor(val value: Int) : Comparable<ArgumentIndex> {
    override fun compareTo(other: ArgumentIndex): Int = value.compareTo(other.value)

    companion object {
        fun parse(raw: Int): ArgumentIndexParseResult =
            if (raw >= 0) ArgumentIndexParseResult.Valid(ArgumentIndex(raw)) else ArgumentIndexParseResult.Negative(raw)
    }
}

enum class ProofCallableKind { FUNCTION }

data class ProofCallableKey(
    val callableId: CallableIdKey,
    val kind: ProofCallableKind,
    val receiverType: KotlinTypeKey?,
    val contextParameterTypes: List<KotlinTypeKey>,
    val valueParameterTypes: List<KotlinTypeKey>,
    val genericArity: Int,
) : Comparable<ProofCallableKey> {
    init {
        require(genericArity >= 0)
    }

    override fun compareTo(other: ProofCallableKey): Int = stableText().compareTo(other.stableText())
    fun stableText(): String = buildString {
        append(callableId.value).append('|').append(kind).append('|')
        append(receiverType?.value ?: "-").append('|')
        append(contextParameterTypes.joinToString(",") { it.value }).append('|')
        append(valueParameterTypes.joinToString(",") { it.value }).append('|').append(genericArity)
    }
}

sealed interface MaterializerDescriptor {
    val callable: ProofCallableKey

    data class Total(override val callable: ProofCallableKey) : MaterializerDescriptor
    data class NullableWithExit(override val callable: ProofCallableKey) : MaterializerDescriptor
}

data class PredicateDescriptor(
    val id: PredicateId,
    val callable: ProofCallableKey,
    val subjectArgumentIndex: ArgumentIndex,
    val materializers: Set<MaterializerDescriptor> = emptySet(),
)

data class ProofObligation(
    val argumentIndex: ArgumentIndex,
    val predicate: PredicateId,
)

data class BoundaryDescriptor(
    val id: BoundaryId,
    val callable: ProofCallableKey,
    val obligations: List<ProofObligation>,
)

sealed interface ProofModelViolation {
    data class DuplicatePredicateId(val id: PredicateId) : ProofModelViolation
    data class DuplicateBoundaryId(val id: BoundaryId) : ProofModelViolation
    data class DuplicatePredicateCallable(val callable: ProofCallableKey) : ProofModelViolation
    data class DuplicateBoundaryCallable(val callable: ProofCallableKey) : ProofModelViolation
    data class DuplicateObligation(
        val boundary: BoundaryId,
        val obligation: ProofObligation,
    ) : ProofModelViolation

    data class UnknownPredicate(
        val boundary: BoundaryId,
        val predicate: PredicateId,
    ) : ProofModelViolation

    data class ConflictingCallableRoles(
        val callable: ProofCallableKey,
        val roles: Set<String>,
    ) : ProofModelViolation

    data class ConflictingMaterializerPredicate(val callable: ProofCallableKey) : ProofModelViolation
}

sealed interface ProofModelBuildResult {
    data class Valid(val model: ProofModel) : ProofModelBuildResult
    data class Invalid(val violations: NonEmptyList<ProofModelViolation>) : ProofModelBuildResult
}

class ProofModel private constructor(
    val predicates: List<PredicateDescriptor>,
    val boundaries: List<BoundaryDescriptor>,
) {
    private val predicateById = predicates.associateBy { it.id }
    private val boundaryById = boundaries.associateBy { it.id }
    private val predicateByCallable = predicates.associateBy { it.callable }
    private val boundaryByCallable = boundaries.associateBy { it.callable }
    private val predicateByMaterializer = predicates.flatMap { p -> p.materializers.map { it.callable to p } }.toMap()

    fun predicate(id: PredicateId): PredicateDescriptor = requireNotNull(predicateById[id])
    fun boundary(id: BoundaryId): BoundaryDescriptor = requireNotNull(boundaryById[id])
    fun predicateForCallable(key: ProofCallableKey): PredicateDescriptor? = predicateByCallable[key]
    fun boundaryForCallable(key: ProofCallableKey): BoundaryDescriptor? = boundaryByCallable[key]
    fun predicateForMaterializer(key: ProofCallableKey): PredicateDescriptor? = predicateByMaterializer[key]
    fun materializer(key: ProofCallableKey): MaterializerDescriptor? =
        predicates.asSequence().flatMap { it.materializers.asSequence() }.firstOrNull { it.callable == key }

    companion object {
        fun build(
            predicates: List<PredicateDescriptor>,
            boundaries: List<BoundaryDescriptor>,
        ): ProofModelBuildResult {
            val copiedPredicates = predicates.map { it.copy(materializers = it.materializers.toSet()) }
            val copiedBoundaries = boundaries.map { it.copy(obligations = it.obligations.toList()) }
            val violations = mutableListOf<ProofModelViolation>()
            duplicates(
                copiedPredicates,
                { it.id }).forEach { violations += ProofModelViolation.DuplicatePredicateId(it) }
            duplicates(
                copiedBoundaries,
                { it.id }).forEach { violations += ProofModelViolation.DuplicateBoundaryId(it) }
            duplicates(
                copiedPredicates,
                { it.callable }).forEach { violations += ProofModelViolation.DuplicatePredicateCallable(it) }
            duplicates(
                copiedBoundaries,
                { it.callable }).forEach { violations += ProofModelViolation.DuplicateBoundaryCallable(it) }
            val known = copiedPredicates.mapTo(mutableSetOf()) { it.id }
            copiedBoundaries.forEach { boundary ->
                duplicates(boundary.obligations) { it }.forEach {
                    violations += ProofModelViolation.DuplicateObligation(boundary.id, it)
                }
                boundary.obligations.filter { it.predicate !in known }.forEach {
                    violations += ProofModelViolation.UnknownPredicate(boundary.id, it.predicate)
                }
            }
            val materializerOwners = copiedPredicates.flatMap { p -> p.materializers.map { it.callable to p.id } }
                .groupBy({ it.first }, { it.second })
            materializerOwners.filterValues { it.distinct().size > 1 }.keys.forEach {
                violations += ProofModelViolation.ConflictingMaterializerPredicate(it)
            }
            val roles = mutableMapOf<ProofCallableKey, MutableSet<String>>()
            copiedPredicates.forEach { p ->
                roles.getOrPut(p.callable, ::mutableSetOf) += "predicate"
                p.materializers.forEach { roles.getOrPut(it.callable, ::mutableSetOf) += "materializer" }
            }
            copiedBoundaries.forEach { roles.getOrPut(it.callable, ::mutableSetOf) += "boundary" }
            roles.filterValues { it.size > 1 }.forEach { (key, value) ->
                violations += ProofModelViolation.ConflictingCallableRoles(key, value.toSet())
            }
            return if (violations.isEmpty()) validModel(copiedPredicates, copiedBoundaries) else
                ProofModelBuildResult.Invalid(NonEmptyList(violations.toList()))
        }

        private fun validModel(
            p: List<PredicateDescriptor>,
            b: List<BoundaryDescriptor>,
        ) =
            ProofModelBuildResult.Valid(ProofModel(p, b))

        private fun <T, K> duplicates(
            values: List<T>,
            key: (T) -> K,
        ): Set<K> =
            values.groupingBy(key).eachCount().filterValues { it > 1 }.keys
    }
}
