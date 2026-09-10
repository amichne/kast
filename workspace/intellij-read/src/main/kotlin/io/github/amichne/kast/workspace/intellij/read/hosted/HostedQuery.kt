package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySourceOffset
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.intellij.read.DetachedIdeModule
import io.github.amichne.kast.workspace.intellij.read.DetachedIdeSourceRoot
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Explicit saved Kotlin name selection; no ambient editor or project lookup. */
class HostedKotlinSelection private constructor(
    val root: CanonicalWorkspaceRoot,
    val file: SymbolDiscoveryFileIdentity.Workspace,
    val nameOffset: SymbolDiscoverySourceOffset,
) {
    companion object {
        fun parse(root: CanonicalWorkspaceRoot, relativeFile: String, nameOffset: Int):
            Refinement<HostedKotlinSelection, HostedQueryFailure> {
            if (relativeFile.length > 4096 || !relativeFile.endsWith(".kt") ||
                relativeFile.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
                relativeFile.any { it.isISOControl() || it == '\\' }
            ) return Refinement.Rejected(HostedQueryFailure.INVALID_SELECTION)
            val path = try { Path.of(root.value).resolve(relativeFile) } catch (_: InvalidPathException) {
                return Refinement.Rejected(HostedQueryFailure.INVALID_SELECTION)
            }
            if (path.toString().toByteArray(Charsets.UTF_8).size > 4096) return Refinement.Rejected(HostedQueryFailure.INVALID_SELECTION)
            val file = when (val parsed = SymbolDiscoveryFileIdentity.fromBoundary(root, path, path.toUri().toString())) {
                is Refinement.Refined -> parsed.value as? SymbolDiscoveryFileIdentity.Workspace
                    ?: return Refinement.Rejected(HostedQueryFailure.INVALID_SELECTION)
                is Refinement.Rejected -> return Refinement.Rejected(HostedQueryFailure.INVALID_SELECTION)
            }
            val offset = when (val parsed = SymbolDiscoverySourceOffset.parse(nameOffset)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return Refinement.Rejected(HostedQueryFailure.INVALID_SELECTION)
            }
            return Refinement.Refined(HostedKotlinSelection(root, file, offset))
        }
    }
}

/** Request-local saved/committed IDE content evidence. This is not a disk hash or workspace lease. */
class HostedContentRevision private constructor(
    val documentStamp: HostedDocumentStamp,
    val vfsStamp: HostedVfsStamp,
) {
    companion object {
        internal fun observe(document: Long, vfs: Long): Refinement<HostedContentRevision, HostedQueryFailure> =
            if (document < 0 || vfs < 0) Refinement.Rejected(HostedQueryFailure.FILE_UNAVAILABLE)
            else Refinement.Refined(HostedContentRevision(HostedDocumentStamp(document), HostedVfsStamp(vfs)))
    }
}

@JvmInline value class HostedDocumentStamp internal constructor(val value: Long)
@JvmInline value class HostedVfsStamp internal constructor(val value: Long)

class HostedCompilerDeclaration internal constructor(
    val symbol: CompilerGroundedSymbolEvidence,
    val content: HostedContentRevision,
    val module: DetachedIdeModule,
    val sourceRoot: DetachedIdeSourceRoot,
)

/** A compiler-confirmed direct inheritor edge, detached inside one read and analysis lifetime. */
class HostedInheritorEvidence internal constructor(
    val supertype: HostedCompilerDeclaration,
    val inheritor: HostedCompilerDeclaration,
)

/** Publication of one result under its original endpoint and unchanged model/content epoch. */
class HostedQueryPublication internal constructor(
    val endpoint: HostedQueryEndpoint,
    val epoch: ProjectReadEpoch<*>,
    val model: io.github.amichne.kast.workspace.intellij.read.DetachedIdeWorkspaceModel,
    val relation: HostedInheritorEvidence,
)

sealed interface HostedQueryResult {
    data class Published(val publication: HostedQueryPublication) : HostedQueryResult
    data class Rejected(val failure: HostedQueryFailure, val stage: HostedQueryStage = HostedQueryStage.REQUEST_ADMISSION) : HostedQueryResult
}

internal sealed interface HostedSemanticRead {
    data class Resolved(val evidence: HostedInheritorEvidence) : HostedSemanticRead
    data class Rejected(val failure: HostedQueryFailure) : HostedSemanticRead
}
