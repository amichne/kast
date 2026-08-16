package io.github.amichne.kast.symbol.contract

/** Finite host-neutral reasons the compiler port cannot produce discovery evidence. */
enum class SymbolCompilerRejection {
    SCOPE_REJECTED,
    WORKSPACE_INDEX_UNAVAILABLE,
    PROVIDER_UNAVAILABLE,
    INTERNAL_INVARIANT,
}

/** Closed output of one bounded scope-compiled symbol query. */
sealed interface SymbolCompilation {
    data class Compiled(
        val outcome: SymbolDiscoveryOutcome,
    ) : SymbolCompilation

    data class Rejected(
        val reason: SymbolCompilerRejection,
    ) : SymbolCompilation
}

/** Host-neutral boundary implemented by one native scope compiler and index adapter. */
fun interface SymbolCompilerPort {
    /**
     * Proof transition: `SymbolDiscoveryRequest -> SymbolCompilation`.
     *
     * A compiled result establishes scope-first bounded index work and detached candidates bound
     * to the request lease. [SymbolCompilerRejection] is the closed expected failure. Native
     * project, scope, index, VFS, and PSI values may exist only inside the implementation call.
     */
    suspend fun compile(request: SymbolDiscoveryRequest): SymbolCompilation
}

/** Finite public rejections for `symbol.discover`. */
enum class SymbolDiscoveryRejection {
    WORKSPACE_NOT_READY,
    STALE_GENERATION,
    SCOPE_REJECTED,
    WORKSPACE_INDEX_UNAVAILABLE,
    PROVIDER_UNAVAILABLE,
    COMPILER_CONTRACT_VIOLATION,
}

/** Closed public result of `symbol.discover`. */
sealed interface SymbolDiscoveryResult {
    data class Discovered(
        val outcome: SymbolDiscoveryOutcome,
    ) : SymbolDiscoveryResult

    data class Rejected(
        val reason: SymbolDiscoveryRejection,
    ) : SymbolDiscoveryResult
}

/** Public operation boundary for `symbol.discover`. */
fun interface SymbolDiscoveryOperations {
    /**
     * Proof transition: `SymbolDiscoveryRequest -> SymbolDiscoveryResult`.
     *
     * A discovered result establishes current-generation admission plus bounded, detached,
     * scope-compiled candidates. [SymbolDiscoveryRejection] is the closed expected failure. Raw
     * transport values may enter only before the typed request is constructed.
     */
    suspend fun discover(request: SymbolDiscoveryRequest): SymbolDiscoveryResult
}
