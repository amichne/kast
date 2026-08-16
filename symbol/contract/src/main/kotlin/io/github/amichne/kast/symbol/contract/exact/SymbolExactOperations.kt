package io.github.amichne.kast.symbol.contract

/** Strong request to refine one batch-owned discovery selection through compiler analysis. */
data class SymbolResolutionRequest(
    val selection: SymbolDiscoverySelection,
)

/** Strong request whose only input authority is an already exact selector. */
data class ExactSymbolRequest(
    val selector: SymbolSelector,
)

/** Exact symbol returned by `symbol.resolve`; no weaker candidate identity remains. */
data class ResolvedSymbol(
    val selector: SymbolSelector,
)

/** Finite host-neutral reasons native exact-symbol compilation cannot produce evidence. */
enum class SymbolExactCompilerRejection {
    WORKSPACE_ROOT_MISMATCH,
    GENERATION_MOVED,
    SCOPE_REJECTED,
    WORKSPACE_INDEX_UNAVAILABLE,
    STALE_LOCATION,
    OUTSIDE_SCOPE,
    AMBIGUOUS_DECLARATION,
    UNSUPPORTED_DECLARATION,
    COMPILER_IDENTITY_UNAVAILABLE,
    DECLARATION_MOVED_OR_CHANGED,
    INTERNAL_INVARIANT,
}

/** Closed compiler-port output for `symbol.resolve`. */
sealed interface SymbolResolutionCompilation {
    data class Resolved(
        val selector: SymbolSelector,
    ) : SymbolResolutionCompilation

    data class Rejected(
        val reason: SymbolExactCompilerRejection,
    ) : SymbolResolutionCompilation
}

/** Closed compiler-port output for `symbol.describe`. */
sealed interface SymbolDescriptionCompilation {
    data class Described(
        val description: SymbolDescription,
    ) : SymbolDescriptionCompilation

    data class Rejected(
        val reason: SymbolExactCompilerRejection,
    ) : SymbolDescriptionCompilation
}

/** Host-neutral port implemented by the request-local native compiler adapter. */
interface SymbolExactCompilerPort {
    /**
     * Proof transition: `SymbolResolutionRequest -> SymbolResolutionCompilation`.
     *
     * A resolved compilation establishes one compiler-grounded selector for the batch-owned
     * selection. [SymbolExactCompilerRejection] is the closed expected failure. Live project,
     * scope, PSI, VFS, and compiler objects may exist only inside the implementation call.
     */
    suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionCompilation

    /**
     * Proof transition: `ExactSymbolRequest -> SymbolDescriptionCompilation`.
     *
     * A described compilation establishes that the exact selector revalidated to identical
     * compiler evidence before detached projection. [SymbolExactCompilerRejection] is the closed
     * expected failure. Live project, scope, PSI, VFS, and compiler objects may exist only inside
     * the implementation call.
     */
    suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionCompilation
}

/** Finite public rejections shared by `symbol.resolve` and `symbol.describe`. */
enum class SymbolExactRejection {
    WORKSPACE_NOT_READY,
    WORKSPACE_ROOT_MISMATCH,
    STALE_GENERATION,
    SCOPE_REJECTED,
    WORKSPACE_INDEX_UNAVAILABLE,
    STALE_LOCATION,
    OUTSIDE_SCOPE,
    AMBIGUOUS_DECLARATION,
    UNSUPPORTED_DECLARATION,
    COMPILER_IDENTITY_UNAVAILABLE,
    DECLARATION_MOVED_OR_CHANGED,
    COMPILER_CONTRACT_VIOLATION,
}

/** Closed public result of `symbol.resolve`. */
sealed interface SymbolResolutionResult {
    data class Resolved(
        val symbol: ResolvedSymbol,
    ) : SymbolResolutionResult

    data class Rejected(
        val reason: SymbolExactRejection,
    ) : SymbolResolutionResult
}

/** Closed public result of `symbol.describe`. */
sealed interface SymbolDescriptionResult {
    data class Described(
        val description: SymbolDescription,
    ) : SymbolDescriptionResult

    data class Rejected(
        val reason: SymbolExactRejection,
    ) : SymbolDescriptionResult
}

/** Public operation boundary for `symbol.resolve` and `symbol.describe`. */
interface SymbolExactOperations {
    /**
     * Proof transition: `SymbolResolutionRequest -> SymbolResolutionResult`.
     *
     * A resolved result establishes current-generation admission plus compiler-grounded exact
     * identity. [SymbolExactRejection] is the closed expected failure. Raw ordinals may enter only
     * before the batch-owned [SymbolDiscoverySelection] is constructed.
     */
    suspend fun resolve(request: SymbolResolutionRequest): SymbolResolutionResult

    /**
     * Proof transition: `ExactSymbolRequest -> SymbolDescriptionResult`.
     *
     * A described result establishes current-generation revalidation of the exact selector and a
     * detached description. [SymbolExactRejection] is the closed expected failure. Raw selector
     * encodings may enter only before [ExactSymbolRequest] is constructed.
     */
    suspend fun describe(request: ExactSymbolRequest): SymbolDescriptionResult
}

/** Canonical public symbol surface; implementations may compose discovery and exact-read owners. */
interface SymbolOperations : SymbolDiscoveryOperations, SymbolExactOperations
