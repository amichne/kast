package io.github.amichne.kast.symbol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.IdeReadHostLifetime
import io.github.amichne.kast.workspace.contract.LiveSemanticReadAuthority
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path

private const val EXACT_REVALIDATION_DIGEST_LENGTH = 64

/** A bounded hash of saved, committed owning-file bytes. Equality is a precondition, never compiler authority. */
@JvmInline
value class ExactRevalidationTextIdentity private constructor(val sha256: String) {
    companion object {
        fun parse(raw: String): Refinement<ExactRevalidationTextIdentity, ExactRevalidationRejection> =
            if (raw.length == EXACT_REVALIDATION_DIGEST_LENGTH && raw.all { it in '0'..'9' || it in 'a'..'f' })
                Refinement.Refined(ExactRevalidationTextIdentity(raw))
            else Refinement.Rejected(ExactRevalidationRejection.CAPTURE_UNAVAILABLE)
    }
}

/** Detached issuance facts. Contains neither an old selector nor a read capability. */
class ExactRevalidationLocator
private constructor(
    val root: CanonicalWorkspaceRoot,
    val host: IdeReadHostLifetime,
    val scope: SymbolSearchScope,
    val constraints: SymbolDiscoveryConstraints,
    val evidence: CompilerGroundedSymbolEvidence,
    val owner: ModelOwnedSourceRoot,
    val text: ExactRevalidationTextIdentity,
) {
    companion object {
        fun capture(
            selector: SymbolSelector,
            owner: ModelOwnedSourceRoot,
            text: ExactRevalidationTextIdentity,
        ): Refinement<ExactRevalidationLocator, ExactRevalidationRejection> {
            val live =
                selector.lease as? LiveSemanticReadAuthority
                    ?: return Refinement.Rejected(ExactRevalidationRejection.CAPTURE_UNAVAILABLE)
            val file =
                selector.file as? SymbolDiscoveryFileIdentity.Workspace
                    ?: return Refinement.Rejected(ExactRevalidationRejection.UNSUPPORTED_DECLARATION)
            if (
                !Path.of(file.path.value).startsWith(Path.of(owner.sourceRoot.value)) ||
                    owner.provenance != WorkspaceSourceRootProvenance.AUTHORED
            )
                return Refinement.Rejected(ExactRevalidationRejection.UNSUPPORTED_DECLARATION)
            return Refinement.Refined(
                ExactRevalidationLocator(
                    live.workspaceRoot,
                    live.reference.host,
                    selector.scope,
                    selector.constraints,
                    CompilerGroundedSymbolEvidence.fromSelector(selector),
                    owner,
                    text,
                )
            )
        }
    }
}

enum class ExactRevalidationRejection {
    WRONG_KIND,
    UNRETAINED,
    EXPIRED,
    CAPACITY,
    RETIRED,
    CAPTURE_UNAVAILABLE,
    WORKSPACE_MISMATCH,
    OWNER_MISMATCH,
    WORKSPACE_NOT_READY,
    BASIS_MOVED,
    CONTENT_CHANGED,
    CONTENT_UNCOMMITTED,
    SCOPE_REJECTED,
    DECLARATION_MISSING,
    UNSUPPORTED_DECLARATION,
    AMBIGUOUS,
    COMPILER_IDENTITY_CHANGED,
    COMPILER_UNAVAILABLE,
}

sealed interface ExactRevalidationCompilation {
    data class Confirmed(val evidence: CompilerGroundedSymbolEvidence) : ExactRevalidationCompilation

    data class Rejected(val reason: ExactRevalidationRejection) : ExactRevalidationCompilation
}

/** Compiler adapter checks content and original model ownership before one fresh exact lookup. */
fun interface ExactRevalidationCompilerPort {
    suspend fun confirm(locator: ExactRevalidationLocator, current: SemanticReadAuthority): ExactRevalidationCompilation
}

sealed interface ExactRevalidationResult {
    data class Reacquired(val selector: SymbolSelector) : ExactRevalidationResult

    data class Rejected(val reason: ExactRevalidationRejection) : ExactRevalidationResult
}

fun interface ExactRevalidationOperations {
    suspend fun revalidate(locator: ExactRevalidationLocator, current: SemanticReadAuthority): ExactRevalidationResult
}
