package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation

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
) {
    fun prepareFileEnumeration(
        allowance: IntellijRelationAllowance,
        limits: io.github.amichne.kast.kernel.ReadLimits,
        lookup: RelationNativeFileLookupPort = RelationNativeFileLookupPort.Local,
    ): CompiledRelationScope =
        when (
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
                )
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
}
