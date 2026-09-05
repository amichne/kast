package lifecycle.convergence

/** Public shell concepts after lifecycle convergence. */
sealed interface PublicCommand {
    data object Inspect : PublicCommand
    data object Start : PublicCommand
    data object Stop : PublicCommand
    data class Semantic(val operation: PublicSemanticOperation) : PublicCommand
}

enum class PublicSemanticOperation {
    SymbolDiscover,
    SymbolInspect,
    SourceRead,
    RelationRead,
    TraversalRun,
    DiagnosticCheck,
    ChangePlan,
    ChangeApply,
    ChangeRecover,
}

/** Maintenance remains real but cannot be scheduled by an external caller. */
enum class InternalPreparation {
    WorkspaceSynchronize,
    TopologyEnsure,
}

sealed interface RuntimeDemand {
    data object ExplicitStart : RuntimeDemand
    data class Semantic(val operation: PublicSemanticOperation) : RuntimeDemand
}

@JvmInline
value class ExactRoot(val value: String)

@JvmInline
value class RuntimeIdentity(val value: String)

/** Possession establishes that one exact-root runtime may accept the admitted semantic demand. */
data class ReadyRuntime private constructor(
    val root: ExactRoot,
    val identity: RuntimeIdentity,
    val demand: RuntimeDemand,
) {
    companion object {
        fun admitted(root: ExactRoot, identity: RuntimeIdentity, demand: RuntimeDemand) =
            ReadyRuntime(root, identity, demand)
    }
}

sealed interface RuntimeReadiness {
    data class Ready(val runtime: ReadyRuntime) : RuntimeReadiness
    data class Rejected(val failure: RuntimeReadinessFailure) : RuntimeReadiness
}

enum class RuntimeReadinessFailure {
    RootUnavailable,
    RuntimeUnavailable,
    ImportRejected,
    IndexingRejected,
    UnsupportedOperation,
}

/**
 * The single lifecycle owner used by both direct CLI and Codex-backed calls.
 *
 * Codex never starts a second Kast service. Its provider executes the same semantic CLI command;
 * that command demands/reuses the exact-root runtime before crossing the wire.
 */
fun interface RuntimeReadinessCoordinator {
    fun demand(root: ExactRoot, demand: RuntimeDemand): RuntimeReadiness
}

sealed interface SemanticInvocation<out T> {
    data class Complete<T>(val value: T) : SemanticInvocation<T>
    data class Qualified<T>(val value: T, val reason: String) : SemanticInvocation<T>
    data class Rejected(val failure: SemanticInvocationFailure) : SemanticInvocation<Nothing>
}

enum class SemanticInvocationFailure {
    RuntimeUnavailable,
    OperationRejected,
}

class SemanticCommandExecutor(
    private val readiness: RuntimeReadinessCoordinator,
) {
    fun <T> execute(
        root: ExactRoot,
        operation: PublicSemanticOperation,
        dispatch: (ReadyRuntime) -> SemanticInvocation<T>,
    ): SemanticInvocation<T> = when (
        val prepared = readiness.demand(root, RuntimeDemand.Semantic(operation))
    ) {
        is RuntimeReadiness.Ready -> dispatch(prepared.runtime)
        is RuntimeReadiness.Rejected -> SemanticInvocation.Rejected(
            SemanticInvocationFailure.RuntimeUnavailable,
        )
    }
}

/**
 * Codex projection: no lifecycle tools are published. The catalog contains semantic tools only;
 * cold-start behavior is an implementation invariant of SemanticCommandExecutor.
 */
val codexToolSurface: Set<PublicSemanticOperation> = PublicSemanticOperation.entries.toSet()
