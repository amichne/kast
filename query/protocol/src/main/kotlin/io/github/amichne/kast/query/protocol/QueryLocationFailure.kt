package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.QueryFromDocument
import io.github.amichne.kast.query.contract.QueryContainingDeclaration
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.workspace.contract.SemanticReadAuthority
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import java.nio.file.Path

internal enum class QueryLocationFailure {
    PATH,
    OFFSET,
}

internal fun QueryFromDocument.Location.admitLocation(
    authority: SemanticReadAuthority
): Refinement<QueryContainingDeclaration, QueryLocationFailure> {
    val relative =
        when (val parsed = WorkspaceSourcePath.parse(file.value)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(QueryLocationFailure.PATH)
        }
    if (relative.value != file.value || file.value.any(Char::isISOControl))
        return Refinement.Rejected(QueryLocationFailure.PATH)
    if (file.value.contains('\\') || Regex("^[A-Za-z]:").containsMatchIn(file.value))
        return Refinement.Rejected(QueryLocationFailure.PATH)
    val canonical =
        when (
            val parsed =
                CanonicalWorkspaceFilePath.fromCanonicalPath(
                    authority.workspaceRoot,
                    Path.of(authority.workspaceRoot.value).resolve(relative.value),
                )
        ) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(QueryLocationFailure.PATH)
        }
    val position =
        when (val parsed = SymbolDiscoverySourceOffset.parse(offset.value)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return Refinement.Rejected(QueryLocationFailure.OFFSET)
        }
    return Refinement.Refined(QueryContainingDeclaration(canonical, position))
}
