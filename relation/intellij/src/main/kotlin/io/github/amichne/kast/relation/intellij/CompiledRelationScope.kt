package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReadPosition
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.nio.file.Path

/** Request-local proof that subject scope and imported model compiled before native work. */
internal class CompiledRelationScope
internal constructor(
    val request: RelationRequest,
    val sourceRoots: List<ModelOwnedSourceRoot>,
    val nativeScope: GlobalSearchScope,
    private val sourceDomain: RelationSourceDomainMembership,
    private val libraryMembership: (VirtualFile) -> Boolean,
    private val libraries: SymbolLibraryPolicy,
    private val observation: IntellijReadObservation,
    private val fileEnumerationPlan: RelationFileEnumerationPlan,
    val selectedScope: io.github.amichne.kast.symbol.contract.SymbolSearchScope = request.searchScope,
    val constraints: io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints = request.searchConstraints,
) {
    val fingerprint: io.github.amichne.kast.relation.contract.RelationScopeFingerprint =
        io.github.amichne.kast.relation.contract.RelationScopeFingerprint.from(
            request.subject,
            io.github.amichne.kast.relation.contract.RelationSearchBoundary.Explicit(
                selectedScope,
                constraints.directory,
                constraints.sourceSets,
            ),
        )

    fun prepareFileEnumeration(
        allowance: IntellijRelationAllowance,
        limits: io.github.amichne.kast.kernel.ReadLimits,
        lookup: RelationNativeFileLookupPort = RelationNativeFileLookupPort.Local,
    ): CompiledRelationScope {
        when (request.position) {
            RelationReadPosition.Start -> Unit
            is RelationReadPosition.Resume ->
                when (request.meaning) {
                    // These producers restore retained locators; they issue no new native reference search.
                    RelationMeaning.References,
                    RelationMeaning.TypeUses -> return this
                    RelationMeaning.Callers,
                    RelationMeaning.Callees,
                    RelationMeaning.Implementations,
                    RelationMeaning.Inheritors,
                    RelationMeaning.Overrides -> Unit
                }
        }
        return when (
            val prepared =
                CompleteRelationFileUniverse.prepare(
                    fileEnumerationPlan,
                    request.budget.resources,
                    allowance,
                    limits,
                    observation,
                    lookup,
                )
        ) {
            is RelationFileEnumerationPreparation.Declined -> this
            is RelationFileEnumerationPreparation.Complete ->
                CompiledRelationScope(
                    request,
                    sourceRoots,
                    EnumeratedRelationScope(nativeScope, prepared.universe, observation),
                    sourceDomain,
                    libraryMembership,
                    libraries,
                    observation,
                    fileEnumerationPlan,
                    selectedScope,
                    constraints,
                )
        }
    }

    /** Membership is decided before native candidate capacity or locator detachment. */
    fun admitProviderSite(file: VirtualFile?): RelationProviderScopeAdmission =
        when {
            file == null -> RelationProviderScopeAdmission.UNAVAILABLE
            nativeScope.contains(file) -> RelationProviderScopeAdmission.ADMITTED
            libraryMembership(file) ->
                if (libraries == SymbolLibraryPolicy.EXCLUDE) RelationProviderScopeAdmission.LIBRARY_POLICY_EXCLUDED
                else RelationProviderScopeAdmission.UNAVAILABLE
            else -> sourceDomain.classifyExcluded(relationNativePath(file))
        }.observed(observation)

    /** Captured ownership and selected paths can prove exclusion without native file effects. */
    fun excludesKnownSource(file: SymbolDiscoveryFileIdentity): Boolean =
        when (file) {
            is SymbolDiscoveryFileIdentity.Workspace ->
                sourceDomain.classifyExcluded(IntellijRelationNativePath.Absolute(Path.of(file.path.value))) ==
                    RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED
            is SymbolDiscoveryFileIdentity.External -> false
        }
}
