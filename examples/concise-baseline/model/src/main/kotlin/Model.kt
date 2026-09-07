package kast.baseline.model

/** Examples are detached models, not substitutes for Kast's compiler-issued identities. */
sealed interface Refinement<out T, out F> {
    data class Refined<T>(val value: T) : Refinement<T, Nothing>
    data class Rejected<F>(val failure: F) : Refinement<Nothing, F>
}

enum class InputFailure { INVALID_ID, INVALID_GENERATION, INVALID_BUDGET }

@JvmInline
value class WorkspaceId private constructor(val value: String) {
    companion object {
        fun parse(raw: String): Refinement<WorkspaceId, InputFailure> =
            if (Regex("[a-z][a-z0-9-]{0,63}").matches(raw)) Refinement.Refined(WorkspaceId(raw))
            else Refinement.Rejected(InputFailure.INVALID_ID)
    }
}

@JvmInline
value class Generation private constructor(val value: Long) {
    companion object {
        fun parse(raw: Long): Refinement<Generation, InputFailure> =
            if (raw > 0) Refinement.Refined(Generation(raw))
            else Refinement.Rejected(InputFailure.INVALID_GENERATION)
    }
}

@JvmInline
value class PreparationBudget private constructor(val milliseconds: Long) {
    companion object {
        fun parse(raw: Long): Refinement<PreparationBudget, InputFailure> =
            if (raw in 1..1_020_000) Refinement.Refined(PreparationBudget(raw))
            else Refinement.Rejected(InputFailure.INVALID_BUDGET)
    }
}

data class Coordinates(val workspace: WorkspaceId, val generation: Generation)
data class Selector(val coordinates: Coordinates)
enum class Failure { RUNTIME_ABSENT, PREPARATION_REJECTED, STALE_SELECTOR, WRONG_WORKSPACE,
    GENERATION_CHANGED, TOPOLOGY_REJECTED, RECOVERY_REQUIRED }
enum class Qualification { RESULT_LIMIT }
sealed interface Outcome<out T> {
    data class Complete<T>(val value: T) : Outcome<T>
    data class Qualified<T>(val value: T, val reason: Qualification) : Outcome<T>
    data class Rejected(val reason: Failure) : Outcome<Nothing>
}

/** Operation exposure is not inferred from the existence of an internal implementation. */
sealed interface Exposure {
    data object Baseline : Exposure
    data class Command(val words: List<String>) : Exposure
    data object Internal : Exposure
    data object Integration : Exposure
}
enum class Effect { OBSERVE, PREPARE, DERIVED_WRITE, SOURCE_WRITE }
enum class Cost { LOCAL, SEMANTIC_READ, INDEX_SYNCHRONIZATION, TOPOLOGY_BUILD }
enum class Operation(val exposure: Exposure, val effect: Effect, val cost: Cost) {
    INSPECT(Exposure.Baseline, Effect.OBSERVE, Cost.LOCAL),
    START(Exposure.Command(listOf("start")), Effect.PREPARE, Cost.INDEX_SYNCHRONIZATION),
    STOP(Exposure.Command(listOf("stop")), Effect.PREPARE, Cost.LOCAL),
    SYMBOL_DISCOVER(Exposure.Command(listOf("symbol", "discover")), Effect.OBSERVE, Cost.SEMANTIC_READ),
    SYMBOL_INSPECT(Exposure.Command(listOf("symbol", "inspect")), Effect.OBSERVE, Cost.SEMANTIC_READ),
    SOURCE_READ(Exposure.Command(listOf("source", "read")), Effect.OBSERVE, Cost.SEMANTIC_READ),
    RELATION_READ(Exposure.Command(listOf("relation", "read")), Effect.OBSERVE, Cost.SEMANTIC_READ),
    TRAVERSAL_RUN(Exposure.Command(listOf("traversal", "run")), Effect.DERIVED_WRITE, Cost.TOPOLOGY_BUILD),
    DIAGNOSTIC_CHECK(Exposure.Command(listOf("diagnostic", "check")), Effect.OBSERVE, Cost.SEMANTIC_READ),
    CHANGE_PLAN(Exposure.Command(listOf("change", "plan")), Effect.OBSERVE, Cost.SEMANTIC_READ),
    CHANGE_APPLY(Exposure.Command(listOf("change", "apply")), Effect.SOURCE_WRITE, Cost.SEMANTIC_READ),
    CHANGE_RECOVER(Exposure.Command(listOf("change", "recover")), Effect.SOURCE_WRITE, Cost.SEMANTIC_READ),
    INDEX_SYNC(Exposure.Internal, Effect.PREPARE, Cost.INDEX_SYNCHRONIZATION),
    TOPOLOGY_BUILD(Exposure.Internal, Effect.DERIVED_WRITE, Cost.TOPOLOGY_BUILD),
    BROKER_SERVE(Exposure.Integration, Effect.PREPARE, Cost.LOCAL);
}

fun Outcome<Coordinates>.atCoordinates(expected: Coordinates): Outcome<Coordinates> {
    val actual = when (this) {
        is Outcome.Complete -> value
        is Outcome.Qualified -> value
        is Outcome.Rejected -> return this
    }
    return when {
        actual.workspace != expected.workspace -> Outcome.Rejected(Failure.WRONG_WORKSPACE)
        actual.generation != expected.generation -> Outcome.Rejected(Failure.GENERATION_CHANGED)
        else -> this
    }
}
