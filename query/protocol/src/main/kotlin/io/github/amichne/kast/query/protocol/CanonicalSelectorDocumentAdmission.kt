package io.github.amichne.kast.query.protocol

import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.*
import io.github.amichne.kast.symbol.contract.CandidateSelector
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
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
import io.github.amichne.kast.workspace.contract.*
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
 * Proof transition: `CandidateSelectorDocument -> SelectorDocumentAdmission<CandidateSelector>`.
 *
 * Admission dispatches the closed token discriminator, establishes its lease and workspace-file
 * identity, and restores exactly the facts carried by that variant. Primitive fields leave only
 * while calling their owning contract refinements.
 */
internal fun CandidateSelectorDocument.admitCandidateSelector(current: SemanticReadAuthority? = null):
    SelectorDocumentAdmission<CandidateSelector> {
    return when (this) {
        is CandidateSelectorDocument.Declaration -> when (val admitted = admitSelection(current)) {
            is SelectorDocumentAdmission.Admitted -> when (
                val restored = CandidateSelector.declaration(admitted.value)
            ) {
                is Refinement.Refined -> SelectorDocumentAdmission.Admitted(restored.value)
                is Refinement.Rejected -> SelectorDocumentAdmission.Rejected
            }
            SelectorDocumentAdmission.Rejected -> SelectorDocumentAdmission.Rejected
        }
        is CandidateSelectorDocument.File -> {
            val lease = when (val admitted = admitAuthority(root, generation, live, current)) {
                is SelectorDocumentAdmission.Admitted -> admitted.value
                SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
            }
            val fileIdentity = when (
                val admitted = admitFile(WORKSPACE_FILE, file, lease.workspaceRoot)
            ) {
                is SelectorDocumentAdmission.Admitted -> admitted.value
                SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
            }
            val workspaceFile = fileIdentity as? SymbolDiscoveryFileIdentity.Workspace
                ?: return SelectorDocumentAdmission.Rejected
            val admittedScope = when (val admitted = readScope.admitReadScope(lease.workspaceRoot, workspaceFile)) {
                is SelectorDocumentAdmission.Admitted -> admitted.value
                SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
            }
            SelectorDocumentAdmission.Admitted(
                CandidateSelector.restoreFile(lease, workspaceFile, admittedScope.scope, admittedScope.constraints),
            )
        }
        is CandidateSelectorDocument.Range -> {
            val lease = when (val admitted = admitAuthority(root, generation, live, current)) {
                is SelectorDocumentAdmission.Admitted -> admitted.value
                SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
            }
            val fileIdentity = when (
                val admitted = admitFile(WORKSPACE_FILE, file, lease.workspaceRoot)
            ) {
                is SelectorDocumentAdmission.Admitted -> admitted.value
                SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
            }
            val workspaceFile = fileIdentity as? SymbolDiscoveryFileIdentity.Workspace
                ?: return SelectorDocumentAdmission.Rejected
            val admittedScope = when (val admitted = readScope.admitReadScope(lease.workspaceRoot, workspaceFile)) {
                is SelectorDocumentAdmission.Admitted -> admitted.value
                SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
            }
            when (
                val restored = CandidateSelector.restoreRange(
                    lease,
                    workspaceFile,
                    startInclusive,
                    endExclusive,
                    admittedScope.scope,
                    admittedScope.constraints,
                )
            ) {
                is Refinement.Refined -> SelectorDocumentAdmission.Admitted(restored.value)
                is Refinement.Rejected -> SelectorDocumentAdmission.Rejected
            }
        }
    }
}

/** Restores the declaration variant's discovery selection without adding source authority. */
private fun CandidateSelectorDocument.Declaration.admitSelection(current: SemanticReadAuthority?):
    SelectorDocumentAdmission<SymbolDiscoverySelection> {
    val lease = when (val admission = admitAuthority(root, generation, live, current)) {
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
    return when (val restored = SymbolDiscoverySelection.restore(lease, admittedScope, candidate,
        constraints = when (val admitted = constraints.admitConstraints()) {
            is SelectorDocumentAdmission.Admitted -> admitted.value
            SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
        },
    )) {
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
internal fun ExactSelectorDocument.admitExactSelector(current: SemanticReadAuthority? = null):
    SelectorDocumentAdmission<SymbolSelector> {
    val lease = when (val admission = admitAuthority(root, generation, live, current)) {
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
    val signature = when (
        val refined = CanonicalCompilerSignature.restoreCanonicalEncoding(compilerSignature)
    ) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    val evidence = when (
        val refined = CompilerGroundedSymbolEvidence.restoreBoundary(
            fileIdentity,
            start,
            end,
            name,
            qualifiedIdentity,
            symbolKind,
            signature,
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
            constraints = when (val admitted = constraints.admitConstraints()) {
                is SelectorDocumentAdmission.Admitted -> admitted.value
                SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
            },
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
private fun admitAuthority(
    root: String,
    generation: Long?,
    live: LiveSelectorAuthorityDocument?,
    current: SemanticReadAuthority?,
): SelectorDocumentAdmission<SemanticReadAuthority> {
    if (live != null) {
        if (generation != null || current !is LiveSemanticReadAuthority) return SelectorDocumentAdmission.Rejected
        val reference = current.reference
        return if (root == reference.workspaceRoot.value && live.host == reference.host.value.toString() &&
            live.epoch == reference.epoch.value && live.contentView == reference.contentView.name &&
            live.version == reference.version && live.version == LiveSemanticReadReference.VERSION
        ) SelectorDocumentAdmission.Admitted(current) else SelectorDocumentAdmission.Rejected
    }
    if (generation == null) return SelectorDocumentAdmission.Rejected
    val path = try { Path.of(root) } catch (_: InvalidPathException) { return SelectorDocumentAdmission.Rejected }
    val workspace = when (val refined = CanonicalWorkspaceRoot.fromCanonicalPath(path)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    val admittedGeneration = when (val refined = EvidenceGeneration.parse(generation)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    val lease = SemanticReadLease(workspace, admittedGeneration)
    return if (current == null || current == lease) SelectorDocumentAdmission.Admitted(lease)
        else SelectorDocumentAdmission.Rejected
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

/** Detached live identity is compared with the already admitted original owner's reference. */
internal fun LiveSelectorAuthorityDocument.admitReference(
    root: String,
    current: LiveSemanticReadReference,
): Refinement<Unit, CanonicalSelectorDecodingFailure> = when {
    version != LiveSemanticReadReference.VERSION || version != current.version ->
        Refinement.Rejected(CanonicalSelectorDecodingFailure.UNSUPPORTED_REFERENCE_VERSION)
    root != current.workspaceRoot.value -> Refinement.Rejected(CanonicalSelectorDecodingFailure.INCOMPATIBLE_WORKSPACE)
    host != current.host.value.toString() -> Refinement.Rejected(CanonicalSelectorDecodingFailure.INCOMPATIBLE_AUTHORITY)
    epoch != current.epoch.value || contentView != current.contentView.name -> Refinement.Rejected(CanonicalSelectorDecodingFailure.STALE_AUTHORITY)
    else -> Refinement.Refined(Unit)
}

internal fun admitSelectorReference(
    root: String,
    generation: Long?,
    live: LiveSelectorAuthorityDocument?,
    current: SemanticReadAuthority?,
): CanonicalSelectorDecoding<SemanticReadAuthority> {
    if (live != null) {
        if (generation != null) return CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.INVALID_DOCUMENT)
        if (current !is LiveSemanticReadAuthority) return CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.LIVE_AUTHORITY_REQUIRED)
        return when (val admitted = live.admitReference(root, current.reference)) {
            is Refinement.Refined -> CanonicalSelectorDecoding.Decoded(current)
            is Refinement.Rejected -> CanonicalSelectorDecoding.Rejected(admitted.failure)
        }
    }
    return when (val published = admitAuthority(root, generation, null, null)) {
        is SelectorDocumentAdmission.Admitted -> when {
            current != null && published.value.workspaceRoot != current.workspaceRoot -> CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.INCOMPATIBLE_WORKSPACE)
            current != null && published.value != current -> CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.STALE_AUTHORITY)
            else -> CanonicalSelectorDecoding.Decoded(published.value)
        }
        SelectorDocumentAdmission.Rejected -> CanonicalSelectorDecoding.Rejected(CanonicalSelectorDecodingFailure.INVALID_DOCUMENT)
    }
}

internal fun SymbolDiscoveryConstraints.selectorDocument(): SelectorConstraintsDocument? =
    if (this == SymbolDiscoveryConstraints.None) null else SelectorConstraintsDocument(
        directory?.let { SelectorConstraintPathDocument(it.directory.value, it.containment.name) },
        packageName?.let { SelectorConstraintPathDocument(it.packageName.value, it.containment.name) },
        declarationKinds?.values?.map { it.name }?.sorted(),
        when (val sets = sourceSets) {
            SymbolDiscoverySourceSets.All -> null
            is SymbolDiscoverySourceSets.Exact -> sets.values.map { it.value }.sorted()
        },
    )

private fun SelectorConstraintsDocument?.admitConstraints(): SelectorDocumentAdmission<SymbolDiscoveryConstraints> {
    if (this == null) return SelectorDocumentAdmission.Admitted(SymbolDiscoveryConstraints.None)
    val directoryConstraint = directory?.let {
        val value = SymbolDiscoveryDirectory.parse(it.value).valueOrNull() ?: return SelectorDocumentAdmission.Rejected
        val containment = SymbolDiscoveryContainment.entries.singleOrNull { entry -> entry.name == it.containment }
            ?: return SelectorDocumentAdmission.Rejected
        SymbolDiscoveryDirectoryConstraint(value, containment)
    }
    val packageConstraint = packageName?.let {
        val value = SymbolDiscoveryPackage.parse(it.value).valueOrNull() ?: return SelectorDocumentAdmission.Rejected
        val containment = SymbolDiscoveryContainment.entries.singleOrNull { entry -> entry.name == it.containment }
            ?: return SelectorDocumentAdmission.Rejected
        SymbolDiscoveryPackageConstraint(value, containment)
    }
    val kinds = declarationKinds?.let { names ->
        if (names.distinct().size != names.size) return SelectorDocumentAdmission.Rejected
        val values = names.mapTo(linkedSetOf()) { name -> CompilerSymbolKind.entries.singleOrNull { it.name == name }
            ?: return SelectorDocumentAdmission.Rejected }
        SymbolDiscoveryDeclarationKinds.from(values).valueOrNull() ?: return SelectorDocumentAdmission.Rejected
    }
    val sets = sourceSets?.let { names ->
        if (names.distinct().size != names.size) return SelectorDocumentAdmission.Rejected
        val values = names.mapTo(linkedSetOf()) { WorkspaceSourceSetName.parse(it).valueOrNull()
            ?: return SelectorDocumentAdmission.Rejected }
        SymbolDiscoverySourceSets.Exact.from(values).valueOrNull() ?: return SelectorDocumentAdmission.Rejected
    } ?: SymbolDiscoverySourceSets.All
    return SelectorDocumentAdmission.Admitted(SymbolDiscoveryConstraints(directoryConstraint, packageConstraint, kinds, sets))
}

private fun <Value, Failure> Refinement<Value, Failure>.valueOrNull(): Value? = when (this) {
    is Refinement.Refined -> value
    is Refinement.Rejected -> null
}

private data class AdmittedCandidateReadScope(
    val scope: SymbolSearchScope,
    val constraints: SymbolDiscoveryConstraints,
)

private fun SelectorReadScopeDocument?.admitReadScope(
    root: CanonicalWorkspaceRoot,
    file: SymbolDiscoveryFileIdentity.Workspace,
): SelectorDocumentAdmission<AdmittedCandidateReadScope> {
    if (this == null) return SelectorDocumentAdmission.Admitted(AdmittedCandidateReadScope(
        SymbolSearchScope.ExactFile(file.path, SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            SymbolGeneratedSourcePolicy.INCLUDE), SymbolDiscoveryConstraints.None))
    val scope = when (val admitted = admitScope(this, root)) {
        is SelectorDocumentAdmission.Admitted -> admitted.value
        SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    if (scope is SymbolSearchScope.ExactFile && scope.file != file.path) return SelectorDocumentAdmission.Rejected
    val constraints = when (val admitted = constraints.admitConstraints()) {
        is SelectorDocumentAdmission.Admitted -> admitted.value
        SelectorDocumentAdmission.Rejected -> return SelectorDocumentAdmission.Rejected
    }
    return SelectorDocumentAdmission.Admitted(AdmittedCandidateReadScope(scope, constraints))
}
