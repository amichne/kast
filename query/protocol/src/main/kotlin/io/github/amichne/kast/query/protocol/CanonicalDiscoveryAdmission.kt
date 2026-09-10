package io.github.amichne.kast.query.protocol

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
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.SemanticReadIdentity
import java.nio.file.Path
import java.nio.file.InvalidPathException

internal sealed interface DiscoveryRequestAdmission {
    data class Admitted(
        val request: io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest,
    ) : DiscoveryRequestAdmission

    data object Rejected : DiscoveryRequestAdmission
}

/**
 * Proof transition: `(SemanticReadAuthority, SymbolDiscoverRequest, SymbolDiscoveryBudget) -> DiscoveryRequestAdmission`.
 *
 * Establishes one closed discovery target, exact current lease, compiled-scope request, and
 * resource limits. Boundary primitives do not survive this transition.
 */
internal fun admitDiscoveryRequest(
    current: SemanticReadAuthority,
    request: SymbolDiscoverRequest,
    maximum: SymbolDiscoveryBudget,
): DiscoveryRequestAdmission {
    val results = ResultLimit.parse(minOf(request.limit.value, maximum.resources.resultLimit.value)).refinedOrNull()
        ?: return DiscoveryRequestAdmission.Rejected
    val budget = maximum.copy(resources = maximum.resources.copy(resultLimit = results))
    val workspaceScope = specialistDiscoveryWorkspaceScope(current.identity)
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
            io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest(
                SymbolSearchScopeRequest(current, workspaceScope),
                SymbolDiscoveryTarget.Name(kind, pattern, match),
                budget,
            )
        }
        is SymbolDiscoverTargetDocument.Location -> {
            val file = workspaceFile(current, target.file.value)
                ?: return DiscoveryRequestAdmission.Rejected
            val offset = SymbolDiscoverySourceOffset.parse(target.offset.value).refinedOrNull()
                ?: return DiscoveryRequestAdmission.Rejected
            io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest(
                SymbolSearchScopeRequest(current, exactFileScope(file)),
                SymbolDiscoveryTarget.Location(file, offset),
                budget,
            )
        }
        is SymbolDiscoverTargetDocument.Text -> {
            val pattern = SymbolDiscoveryPattern.parse(target.query.value).refinedOrNull()
                ?: return DiscoveryRequestAdmission.Rejected
            val scope = when (val textScope = target.scope) {
                SymbolTextScopeDocument.Workspace -> workspaceScope
                is SymbolTextScopeDocument.File -> {
                    val file = workspaceFile(current, textScope.file.value)
                        ?: return DiscoveryRequestAdmission.Rejected
                    exactFileScope(file)
                }
            }
            io.github.amichne.kast.symbol.contract.SymbolDiscoveryRequest(
                SymbolSearchScopeRequest(current, scope),
                SymbolDiscoveryTarget.Text(pattern),
                budget,
            )
        }
    }
    return DiscoveryRequestAdmission.Admitted(admitted)
}

/** Live specialists currently support authored project sources; published readers retain their scope. */
internal fun specialistDiscoveryWorkspaceScope(authority: SemanticReadIdentity): SymbolSearchScope.Workspace =
    SymbolSearchScope.Workspace(
        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        SymbolGeneratedSourcePolicy.EXCLUDE,
        when (authority) {
            is SemanticReadIdentity.Published -> SymbolLibraryPolicy.INCLUDE
            is SemanticReadIdentity.Live -> SymbolLibraryPolicy.EXCLUDE
        },
    )

private fun exactFileScope(file: CanonicalWorkspaceFilePath): SymbolSearchScope.ExactFile =
    SymbolSearchScope.ExactFile(
        file,
        SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
        SymbolGeneratedSourcePolicy.EXCLUDE,
    )

private fun workspaceFile(
    current: SemanticReadAuthority,
    raw: String,
): CanonicalWorkspaceFilePath? {
    val root = Path.of(current.workspaceRoot.value)
    val supplied = try { Path.of(raw) } catch (_: InvalidPathException) { return null }
    val absolute = if (supplied.isAbsolute) supplied else root.resolve(supplied)
    return CanonicalWorkspaceFilePath.fromCanonicalPath(
        current.workspaceRoot,
        absolute.normalize(),
    ).refinedOrNull()
}

private fun <Value, Failure> Refinement<Value, Failure>.refinedOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}
