package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.NonBlankString
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.PositiveInt
import io.github.amichne.kast.api.contract.Symbol
import io.github.amichne.kast.api.contract.selector.SelectorHandleAuthority
import io.github.amichne.kast.api.contract.selector.SelectorHandle
import io.github.amichne.kast.api.contract.skill.KastReadEvidence

enum class PublicSymbolReadMatch {
    FUZZY,
    EXACT_NAME,
}

enum class PublicSymbolReadProjection {
    BASIC,
    DECLARATION_SCOPE,
    DOCUMENTATION,
    DECLARATION_SCOPE_AND_DOCUMENTATION,
}

data class PublicSymbolReadQuery(
    val workspaceRoot: NormalizedPath,
    val pattern: NonBlankString,
    val maxResults: PositiveInt,
    val match: PublicSymbolReadMatch,
    val projection: PublicSymbolReadProjection,
    val kind: io.github.amichne.kast.api.contract.SymbolKind? = null,
)

enum class NativePublicSymbolReadFailure {
    WORKSPACE_ROOT_MISMATCH,
    RUNTIME_OR_SEMANTIC_UNAVAILABLE,
    PROJECT_MODEL_UNAVAILABLE,
    NATIVE_READ_UNAVAILABLE,
    INTERNAL_INVARIANT,
}

sealed interface NativePublicSymbolReadResult {
    data class Definition(
        val symbol: Symbol,
        val selectorHandle: SelectorHandle,
    )

    data class Completed(
        val definitions: List<Definition>,
        val evidence: KastReadEvidence.NativeIntellij,
    ) : NativePublicSymbolReadResult

    data class Rejected(
        val failure: NativePublicSymbolReadFailure,
    ) : NativePublicSymbolReadResult
}

fun interface NativePublicSymbolReader {
    /**
     * Proof transition:
     * `PublicSymbolReadQuery -> NativePublicSymbolReadResult`.
     *
     * A completed result establishes detached symbol definitions and generation-coherent selector
     * handles from one exact workspace generation with explicit completeness, stage, work, and byte
     * evidence.
     * [NativePublicSymbolReadFailure] is the closed expected failure. Live IntelliJ state may be
     * consumed only by the injected physical implementation.
     */
    suspend fun read(query: PublicSymbolReadQuery): NativePublicSymbolReadResult
}

sealed interface PublicSymbolReadBinding {
    data object LegacyAnalysisBackend : PublicSymbolReadBinding

    data class Native(
        val workspaceRoot: NormalizedPath,
        val selectorHandles: SelectorHandleAuthority,
        val reader: NativePublicSymbolReader,
    ) : PublicSymbolReadBinding
}
