package io.github.amichne.kast.topology.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.topology.contract.TopologyCandidateSet
import io.github.amichne.kast.topology.contract.TopologyExtractionFailure
import io.github.amichne.kast.topology.contract.TopologySourceFile
import io.github.amichne.kast.topology.contract.TopologySymbol
import io.github.amichne.kast.topology.contract.TopologyWorkspaceIdentity
import java.nio.file.Path

/** Exact content-identified candidate generation authorized to share detached projections. */
internal data class TopologyProjectionRegistryKey internal constructor(
    val candidates: TopologyCandidateSet,
) {
    val workspace: TopologyWorkspaceIdentity = candidates.workspace
    val files: List<TopologySourceFile> = candidates.files

    companion object {
        /**
         * Proof transition: `TopologyCandidateSet -> TopologyProjectionRegistryKey`.
         *
         * Preserves the exact candidate-set capability whose workspace identity, canonical file
         * ownership, and content hashes authorize reuse of detached compiler projections. Key
         * comparison is constant-time capability identity; a separately enumerated set must earn
         * a new registry. Raw candidate extraction remains at the admitted-root enumerator
         * boundary.
         */
        fun from(candidates: TopologyCandidateSet): TopologyProjectionRegistryKey =
            TopologyProjectionRegistryKey(candidates)
    }
}

/** Detached compiler symbols projected once for one exact candidate generation. */
internal class TopologyProjectionRegistry private constructor(
    val key: TopologyProjectionRegistryKey,
    private val symbolsByLocation: Map<TopologyDeclarationLocation, TopologySymbol>,
    private val filesByAbsolutePath: Map<Path, TopologySourceFile>,
) {
    /**
     * Proof transition: `(TopologySourceFile, Int, Int) -> TopologyRegistrySymbolLookup`.
     *
     * Found establishes the detached symbol for the exact admitted declaration range;
     * Unavailable closes unsupported declarations and Rejected closes an invalid range.
     * Raw PSI offsets may enter only from the request-local IntelliJ extraction boundary.
     */
    fun symbolAt(
        file: TopologySourceFile,
        rawStartInclusive: Int,
        rawEndExclusive: Int,
    ): TopologyRegistrySymbolLookup {
        val range = when (
            val parsed = ExactDeclarationTextRange.parse(rawStartInclusive, rawEndExclusive)
        ) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return TopologyRegistrySymbolLookup.Rejected
        }
        val symbol = symbolsByLocation[TopologyDeclarationLocation(file, range)]
        return if (symbol == null) TopologyRegistrySymbolLookup.Unavailable
        else TopologyRegistrySymbolLookup.Found(symbol)
    }

    /**
     * Proof transition: `Path -> TopologyRegistryFileLookup`.
     *
     * Found establishes the exact admitted content-identified file for one absolute PSI path;
     * Unavailable closes paths outside this candidate generation. Raw PSI paths may enter only
     * from the request-local IntelliJ extraction boundary.
     */
    fun fileAt(path: Path): TopologyRegistryFileLookup {
        val file = filesByAbsolutePath[path.toAbsolutePath().normalize()]
        return if (file == null) TopologyRegistryFileLookup.Unavailable
        else TopologyRegistryFileLookup.Found(file)
    }

    companion object {
        /**
         * Proof transition: `(TopologyProjectionRegistryKey, List<TopologySymbol>) ->
         * Refinement<TopologyProjectionRegistry, TopologyProjectionRegistryFailure>`.
         *
         * Establishes exact declaration-location and absolute-file lookup capabilities for one
         * candidate-set identity without overwriting symbols at a duplicate declaration location.
         * [TopologyProjectionRegistryFailure] is the closed expected failure. Raw path extraction
         * remains inside this IntelliJ adapter.
         */
        fun from(
            key: TopologyProjectionRegistryKey,
            symbols: List<TopologySymbol>,
        ): Refinement<TopologyProjectionRegistry, TopologyProjectionRegistryFailure> {
            if (symbols.any { it.file !in key.files }) {
                return Refinement.Rejected(
                    TopologyProjectionRegistryFailure.SYMBOL_OUTSIDE_CANDIDATE_SET,
                )
            }
            val symbolGroups = symbols.groupBy { symbol ->
                TopologyDeclarationLocation(symbol.file, symbol.evidence.range)
            }
            if (symbolGroups.any { it.value.size > 1 }) {
                return Refinement.Rejected(
                    TopologyProjectionRegistryFailure.DUPLICATE_DECLARATION_LOCATION,
                )
            }
            return Refinement.Refined(
                TopologyProjectionRegistry(
                    key,
                    symbolGroups.mapValues { it.value.single() },
                    filesByAbsolutePath(key),
                ),
            )
        }

        internal fun empty(key: TopologyProjectionRegistryKey): TopologyProjectionRegistry =
            TopologyProjectionRegistry(key, emptyMap(), filesByAbsolutePath(key))
    }
}

internal enum class TopologyProjectionRegistryFailure {
    SYMBOL_OUTSIDE_CANDIDATE_SET,
    DUPLICATE_DECLARATION_LOCATION,
}

internal sealed interface TopologyRegistrySymbolLookup {
    data class Found(val symbol: TopologySymbol) : TopologyRegistrySymbolLookup
    data object Unavailable : TopologyRegistrySymbolLookup
    data object Rejected : TopologyRegistrySymbolLookup
}

internal sealed interface TopologyRegistryFileLookup {
    data class Found(val file: TopologySourceFile) : TopologyRegistryFileLookup
    data object Unavailable : TopologyRegistryFileLookup
}

internal sealed interface TopologyProjectionRegistryResolution {
    data class Ready(
        val registry: TopologyProjectionRegistry,
    ) : TopologyProjectionRegistryResolution

    data class Rejected(
        val failure: TopologyExtractionFailure,
    ) : TopologyProjectionRegistryResolution
}

/** Retains only detached registry evidence for the last exact candidate generation. */
internal class TopologyProjectionRegistryCache {
    private var state: TopologyProjectionRegistryState = TopologyProjectionRegistryState.Empty

    /**
     * Proof transition: `(TopologyProjectionRegistryKey, registry builder) ->
     * TopologyProjectionRegistryResolution`.
     *
     * Ready establishes that one exact candidate generation shares one detached registry.
     * Rejected preserves the builder's closed [TopologyExtractionFailure]. A changed key cannot
     * consume prior evidence, and no live Project, PSI, or K2 value is retained.
     */
    @Synchronized
    fun resolve(
        key: TopologyProjectionRegistryKey,
        build: () -> TopologyProjectionRegistryResolution,
    ): TopologyProjectionRegistryResolution {
        when (val current = state) {
            is TopologyProjectionRegistryState.Ready -> if (current.registry.key == key) {
                return TopologyProjectionRegistryResolution.Ready(current.registry)
            }
            TopologyProjectionRegistryState.Empty -> Unit
        }
        return when (val built = build()) {
            is TopologyProjectionRegistryResolution.Ready -> {
                state = TopologyProjectionRegistryState.Ready(built.registry)
                built
            }
            is TopologyProjectionRegistryResolution.Rejected -> built
        }
    }
}

private data class TopologyDeclarationLocation(
    val file: TopologySourceFile,
    val range: ExactDeclarationTextRange,
)

private fun filesByAbsolutePath(
    key: TopologyProjectionRegistryKey,
): Map<Path, TopologySourceFile> = key.files.associateBy { file ->
    Path.of(key.workspace.lease.workspaceRoot.value)
        .resolve(file.path.value)
        .toAbsolutePath()
        .normalize()
}

private sealed interface TopologyProjectionRegistryState {
    data object Empty : TopologyProjectionRegistryState
    data class Ready(
        val registry: TopologyProjectionRegistry,
    ) : TopologyProjectionRegistryState
}
