package io.github.amichne.kast.runtime.composition.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSelectorFingerprint
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal sealed interface SelectorDocumentAdmission<out Value> {
    data class Admitted<Value>(val value: Value) : SelectorDocumentAdmission<Value>
    data object Rejected : SelectorDocumentAdmission<Nothing>
}

internal sealed interface SelectorScopeDocumentProjection {
    data class Projected(
        val kind: String,
        val file: String?,
        val libraries: String?,
    ) : SelectorScopeDocumentProjection

    data object Rejected : SelectorScopeDocumentProjection
}

internal data class SelectorFileDocumentProjection(val kind: String, val value: String)

/** Projects only selector-token-supported scope variants into their fixed document fields. */
internal fun SymbolSearchScope.selectorDocumentProjection(): SelectorScopeDocumentProjection =
    when (this) {
        is SymbolSearchScope.ExactFile -> SelectorScopeDocumentProjection.Projected(
            kind = EXACT_FILE_SCOPE,
            file = file.value,
            libraries = null,
        )
        is SymbolSearchScope.Workspace -> SelectorScopeDocumentProjection.Projected(
            kind = WORKSPACE_SCOPE,
            file = null,
            libraries = libraries.name,
        )
        is SymbolSearchScope.GradleProject,
        is SymbolSearchScope.Module,
        is SymbolSearchScope.SourceSet,
            -> SelectorScopeDocumentProjection.Rejected
    }

/** Projects one closed symbol-file identity into its fixed document discriminator and value. */
internal fun SymbolDiscoveryFileIdentity.selectorDocumentProjection():
    SelectorFileDocumentProjection = when (this) {
    is SymbolDiscoveryFileIdentity.Workspace -> SelectorFileDocumentProjection(
        WORKSPACE_FILE,
        path.value,
    )
    is SymbolDiscoveryFileIdentity.External -> SelectorFileDocumentProjection(
        EXTERNAL_FILE,
        url.value,
    )
}

/**
 * Proof transition: `CandidateSelectorDocument -> SelectorDocumentAdmission`.
 *
 * Admission establishes all domain types carried by a candidate selection; rejection closes every
 * malformed enum, path, scope, location, lease, candidate, and restore state. Primitive fields
 * leave only while calling their owning contract refinements.
 */
internal fun CandidateSelectorDocument.admitCandidateSelection():
    SelectorDocumentAdmission<SymbolDiscoverySelection> {
    val lease = when (val admission = admitLease(root, generation)) {
        is SelectorDocumentAdmission.Admitted -> admission.value
        SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    val admittedScope = when (val admission = admitScope(this, lease.workspaceRoot)) {
        is SelectorDocumentAdmission.Admitted -> admission.value
        SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    val candidateKind = when (val admission = enumAdmission<SymbolDiscoveryKind>(kind)) {
        is SelectorDocumentAdmission.Admitted -> admission.value
        SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    if (candidateKind == SymbolDiscoveryKind.FILE || candidateKind == SymbolDiscoveryKind.TEXT) {
        return SelectorDocumentAdmission.Rejected
    }
    val fileIdentity = when (val admission = admitFile(fileType, file, lease.workspaceRoot)) {
        is SelectorDocumentAdmission.Admitted -> admission.value
        SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    val nativePath = when (fileIdentity) {
        is SymbolDiscoveryFileIdentity.Workspace -> Path.of(fileIdentity.path.value)
        is SymbolDiscoveryFileIdentity.External -> null
    }
    val url = when (fileIdentity) {
        is SymbolDiscoveryFileIdentity.Workspace -> Path.of(fileIdentity.path.value).toUri().toString()
        is SymbolDiscoveryFileIdentity.External -> fileIdentity.url.value
    }
    val candidate = when (
        val refined = SymbolDiscoveryCandidate.fromBoundary(
            candidateKind,
            name,
            lease,
            nativePath,
            url,
            offset,
        )
    ) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    return when (val restored = SymbolDiscoverySelection.restore(lease, admittedScope, candidate)) {
        is Refinement.Refined -> SelectorDocumentAdmission.Admitted(restored.value)
        is Refinement.Rejected -> SelectorDocumentAdmission.Rejected
    }
}

/**
 * Proof transition: `ExactSelectorDocument -> SelectorDocumentAdmission`.
 *
 * Admission establishes every domain type and deterministic fingerprint carried by an exact
 * selector. Rejection closes malformed enum, path, scope, evidence, and restore state. Primitive
 * fields leave only while calling their owning contract refinements.
 */
internal fun ExactSelectorDocument.admitExactSelector():
    SelectorDocumentAdmission<SymbolSelector> {
    val lease = when (val admission = admitLease(root, generation)) {
        is SelectorDocumentAdmission.Admitted -> admission.value
        SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    val admittedScope = when (val admission = admitScope(this, lease.workspaceRoot)) {
        is SelectorDocumentAdmission.Admitted -> admission.value
        SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    val fileIdentity = when (val admission = admitFile(fileType, file, lease.workspaceRoot)) {
        is SelectorDocumentAdmission.Admitted -> admission.value
        SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    val symbolKind = when (val admission = enumAdmission<CompilerSymbolKind>(kind)) {
        is SelectorDocumentAdmission.Admitted -> admission.value
        SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    val compiler = when (val refined = CompilerSymbolIdentity.parse(compilerIdentity)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    val evidence = when (
        val refined = CompilerGroundedSymbolEvidence.fromBoundary(
            fileIdentity,
            start,
            end,
            name,
            qualifiedIdentity,
            symbolKind,
            compiler,
        )
    ) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    val admittedFingerprint = when (val refined = SymbolSelectorFingerprint.parse(fingerprint)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    return when (
        val restored = SymbolSelector.restore(
            lease,
            admittedScope,
            evidence,
            admittedFingerprint,
        )
    ) {
        is Refinement.Refined -> SelectorDocumentAdmission.Admitted(restored.value)
        is Refinement.Rejected -> SelectorDocumentAdmission.Rejected
    }
}

/**
 * Proof transition: `String + Long -> SelectorDocumentAdmission<SemanticReadLease>`.
 *
 * Admission establishes a canonical workspace root and positive evidence generation. Rejection
 * closes malformed paths and failed contract refinement. Raw values leave only at those contract
 * boundaries.
 */
private fun admitLease(
    root: String,
    generation: Long,
): SelectorDocumentAdmission<SemanticReadLease> {
    val path = try {
        Path.of(root)
    } catch (_: InvalidPathException) {
        return SelectorDocumentAdmission.Rejected
    }
    val workspace = when (val refined = CanonicalWorkspaceRoot.fromCanonicalPath(path)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    val admittedGeneration = when (val refined = EvidenceGeneration.parse(generation)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    return SelectorDocumentAdmission.Admitted(SemanticReadLease(workspace, admittedGeneration))
}

/**
 * Proof transition: `SelectorScopeDocumentFields -> SelectorDocumentAdmission<SymbolSearchScope>`.
 *
 * Admission establishes one supported scope variant and its closed policies. Rejection closes
 * contradictory optional fields, unknown variants, enum values, and invalid exact-file paths.
 */
private fun admitScope(
    fields: SelectorScopeDocumentFields,
    root: CanonicalWorkspaceRoot,
): SelectorDocumentAdmission<SymbolSearchScope> {
    val sourceKinds = when (
        val admission = enumAdmission<SymbolSourceKindPolicy>(fields.sourceKinds)
    ) {
        is SelectorDocumentAdmission.Admitted -> admission.value
        SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    val generated = when (
        val admission = enumAdmission<SymbolGeneratedSourcePolicy>(fields.generatedSources)
    ) {
        is SelectorDocumentAdmission.Admitted -> admission.value
        SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    return when (fields.scope) {
        WORKSPACE_SCOPE -> admitWorkspaceScope(fields, sourceKinds, generated)
        EXACT_FILE_SCOPE -> admitExactFileScope(fields, root, sourceKinds, generated)
        else -> SelectorDocumentAdmission.Rejected
    }
}

/** Refines the workspace discriminator fields into the only library-bearing scope variant. */
private fun admitWorkspaceScope(
    fields: SelectorScopeDocumentFields,
    sourceKinds: SymbolSourceKindPolicy,
    generated: SymbolGeneratedSourcePolicy,
): SelectorDocumentAdmission<SymbolSearchScope> {
    if (fields.scopeFile != null) return SelectorDocumentAdmission.Rejected
    val libraries = when (
        val admission = fields.libraries?.let { enumAdmission<SymbolLibraryPolicy>(it) }
            ?: SelectorDocumentAdmission.Rejected
    ) {
        is SelectorDocumentAdmission.Admitted -> admission.value
        SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    return SelectorDocumentAdmission.Admitted(
        SymbolSearchScope.Workspace(sourceKinds, generated, libraries),
    )
}

/** Refines the exact-file discriminator fields into a canonical workspace-file scope. */
private fun admitExactFileScope(
    fields: SelectorScopeDocumentFields,
    root: CanonicalWorkspaceRoot,
    sourceKinds: SymbolSourceKindPolicy,
    generated: SymbolGeneratedSourcePolicy,
): SelectorDocumentAdmission<SymbolSearchScope> {
    if (fields.libraries != null) return SelectorDocumentAdmission.Rejected
    val path = when (val admission = admitPath(fields.scopeFile)) {
        is SelectorDocumentAdmission.Admitted -> admission.value
        SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    return when (val refined = CanonicalWorkspaceFilePath.fromCanonicalPath(root, path)) {
        is Refinement.Refined -> SelectorDocumentAdmission.Admitted(
            SymbolSearchScope.ExactFile(refined.value, sourceKinds, generated),
        )
        is Refinement.Rejected -> SelectorDocumentAdmission.Rejected
    }
}

/**
 * Proof transition: `file discriminator + value -> SelectorDocumentAdmission`.
 *
 * Admission establishes exactly one workspace or external discovery identity. Rejection closes
 * unknown variants and all path or URL refinement failures.
 */
private fun admitFile(
    kind: String,
    value: String,
    root: CanonicalWorkspaceRoot,
): SelectorDocumentAdmission<SymbolDiscoveryFileIdentity> = when (kind) {
    WORKSPACE_FILE -> when (val path = admitPath(value)) {
        is SelectorDocumentAdmission.Admitted -> when (
            val refined = CanonicalWorkspaceFilePath.fromCanonicalPath(root, path.value)
        ) {
            is Refinement.Refined -> SelectorDocumentAdmission.Admitted(
                SymbolDiscoveryFileIdentity.Workspace(refined.value),
            )
            is Refinement.Rejected -> SelectorDocumentAdmission.Rejected
        }
        SelectorDocumentAdmission.Rejected -> SelectorDocumentAdmission.Rejected
    }
    EXTERNAL_FILE -> when (val refined = DetachedVirtualFileUrl.parse(value)) {
        is Refinement.Refined -> SelectorDocumentAdmission.Admitted(
            SymbolDiscoveryFileIdentity.External(refined.value),
        )
        is Refinement.Rejected -> SelectorDocumentAdmission.Rejected
    }
    else -> SelectorDocumentAdmission.Rejected
}

/** Parses one optional document path into a closed admission without leaking platform failures. */
private fun admitPath(value: String?): SelectorDocumentAdmission<Path> {
    if (value == null) return SelectorDocumentAdmission.Rejected
    return try {
        SelectorDocumentAdmission.Admitted(Path.of(value))
    } catch (_: InvalidPathException) {
        SelectorDocumentAdmission.Rejected
    }
}

/** Refines one enum wire name into the requested closed enum family. */
private inline fun <reified Value : Enum<Value>> enumAdmission(
    value: String,
): SelectorDocumentAdmission<Value> = try {
    SelectorDocumentAdmission.Admitted(enumValueOf<Value>(value))
} catch (_: IllegalArgumentException) {
    SelectorDocumentAdmission.Rejected
}

private const val EXACT_FILE_SCOPE = "exact-file"
private const val WORKSPACE_SCOPE = "workspace"
private const val WORKSPACE_FILE = "workspace"
private const val EXTERNAL_FILE = "external"
