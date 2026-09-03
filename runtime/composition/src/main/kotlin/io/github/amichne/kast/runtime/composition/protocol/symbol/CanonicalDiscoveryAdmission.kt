package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.protocol.contract.SymbolDiscoverRequest
import io.github.amichne.kast.protocol.contract.SymbolDiscoverTargetDocument
import io.github.amichne.kast.protocol.contract.SymbolDiscoveryMatchDocument
import io.github.amichne.kast.protocol.contract.SymbolNameKindDocument
import io.github.amichne.kast.protocol.contract.SymbolTextScopeDocument
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryBudget
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryByteLimit
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryMatch
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryPattern
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryTarget
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolNameDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSearchScopeRequest
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.PublishedWorkspace
import java.nio.file.Path

internal sealed interface DiscoveryRequestAdmission {
    data class Admitted(
        val request: io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest,
    ) : DiscoveryRequestAdmission

    data object Rejected : DiscoveryRequestAdmission
}

/**
 * Proof transition: `(PublishedWorkspace, SymbolDiscoverRequest) -> DiscoveryRequestAdmission`.
 *
 * Establishes one closed discovery target, exact current lease, compiled-scope request, and
 * resource limits. Boundary primitives do not survive this transition.
 */
internal fun admitDiscoveryRequest(
    workspace: PublishedWorkspace,
    request: SymbolDiscoverRequest,
): DiscoveryRequestAdmission {
    val results = ResultLimit.parse(request.limit.value).refinedOrNull()
        ?: return DiscoveryRequestAdmission.Rejected
    val work = WorkUnitLimit.parse(SYMBOL_DISCOVERY_WORK_LIMIT).refinedOrNull()
        ?: return DiscoveryRequestAdmission.Rejected
    val elapsed = ElapsedTimeLimitMillis.parse(SYMBOL_DISCOVERY_TIME_MILLIS).refinedOrNull()
        ?: return DiscoveryRequestAdmission.Rejected
    val bytes = SymbolDiscoveryByteLimit.parse(SYMBOL_DISCOVERY_RETURNED_BYTES).refinedOrNull()
        ?: return DiscoveryRequestAdmission.Rejected
    val budget = SymbolDiscoveryBudget(ResourceBudget(results, work, elapsed), bytes)
    val workspaceScope = SymbolSearchScope.Workspace(
        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        SymbolGeneratedSourcePolicy.EXCLUDE,
        SymbolLibraryPolicy.INCLUDE,
    )
    val admitted = when (val target = request.target) {
        is SymbolDiscoverTargetDocument.Name -> {
            val pattern = SymbolDiscoveryPattern.parse(target.query.value).refinedOrNull()
                ?: return DiscoveryRequestAdmission.Rejected
            val kind = when (target.kind) {
                SymbolNameKindDocument.FILE -> SymbolNameDiscoveryKind.FILE
                SymbolNameKindDocument.CLASS -> SymbolNameDiscoveryKind.CLASS
                SymbolNameKindDocument.SYMBOL -> SymbolNameDiscoveryKind.SYMBOL
            }
            val match = when (target.match) {
                SymbolDiscoveryMatchDocument.FUZZY -> SymbolDiscoveryMatch.FUZZY
                SymbolDiscoveryMatchDocument.EXACT_NAME -> SymbolDiscoveryMatch.EXACT_NAME
            }
            workspaceScope to SymbolDiscoveryTarget.Name(kind, pattern, match)
        }
        is SymbolDiscoverTargetDocument.Location -> {
            val file = workspaceFile(workspace, target.file.value)
                ?: return DiscoveryRequestAdmission.Rejected
            val offset = SymbolDiscoverySourceOffset.parse(target.offset.value).refinedOrNull()
                ?: return DiscoveryRequestAdmission.Rejected
            exactFileScope(file) to SymbolDiscoveryTarget.Location(file, offset)
        }
        is SymbolDiscoverTargetDocument.Text -> {
            val pattern = SymbolDiscoveryPattern.parse(target.query.value).refinedOrNull()
                ?: return DiscoveryRequestAdmission.Rejected
            val scope = when (val textScope = target.scope) {
                SymbolTextScopeDocument.Workspace -> workspaceScope
                is SymbolTextScopeDocument.File -> {
                    val file = workspaceFile(workspace, textScope.file.value)
                        ?: return DiscoveryRequestAdmission.Rejected
                    exactFileScope(file)
                }
            }
            scope to SymbolDiscoveryTarget.Text(pattern)
        }
    }
    return DiscoveryRequestAdmission.Admitted(
        io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest(
            SymbolSearchScopeRequest(workspace.readLease, admitted.first),
            admitted.second,
            budget,
        ),
    )
}

private fun exactFileScope(file: CanonicalWorkspaceFilePath): SymbolSearchScope.ExactFile =
    SymbolSearchScope.ExactFile(
        file,
        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        SymbolGeneratedSourcePolicy.EXCLUDE,
    )

private fun workspaceFile(
    workspace: PublishedWorkspace,
    raw: String,
): CanonicalWorkspaceFilePath? {
    val root = Path.of(workspace.root.value)
    val supplied = runCatching { Path.of(raw) }.getOrNull() ?: return null
    val absolute = if (supplied.isAbsolute) supplied else root.resolve(supplied)
    return CanonicalWorkspaceFilePath.fromCanonicalPath(
        workspace.root,
        absolute.normalize(),
    ).refinedOrNull()
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}

private const val SYMBOL_DISCOVERY_WORK_LIMIT = 100_000L
private const val SYMBOL_DISCOVERY_TIME_MILLIS = 30_000L
private const val SYMBOL_DISCOVERY_RETURNED_BYTES = 1_048_576L
