@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.ArchiveFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.LibraryArtifactObservation
import io.github.amichne.kast.relation.contract.LibraryModelDeclarationOrigin
import io.github.amichne.kast.relation.contract.LibraryModelModuleName
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.ReviewedLibraryBinding
import io.github.amichne.kast.relation.contract.ReviewedValueModel
import io.github.amichne.kast.relation.contract.ValueExecutionOwner
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueInvocation
import io.github.amichne.kast.symbol.contract.DetachedVirtualFileUrl
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule

/** Receiver and argument metadata share the same current artifact read and final binding factory. */
internal class NativeValueReviewedArtifact(
    private val caller: ValueExecutionOwner,
    private val scope: CompiledRelationScope,
    private val projection: IntellijK2RelationProjection,
    private val permit: () -> Refinement<Unit, ValueFlowUnsupportedCause>,
) {
    fun bind(
        target: NativeValueReviewedTarget,
        expression: PsiElement,
        model: ReviewedValueModel,
    ): Refinement<ReviewedLibraryBinding, NativeValueModelFailure> {
        val invocation =
            when (val actual = projectInvocation(target, expression)) {
                is Refinement.Refined -> actual.value
                is Refinement.Rejected -> return actual
            }
        return observedBinding(target, invocation, model)
    }

    private fun observedBinding(
        target: NativeValueReviewedTarget,
        invocation: ValueInvocation,
        model: ReviewedValueModel,
    ): Refinement<ReviewedLibraryBinding, NativeValueModelFailure> {
        val content =
            when (
                val read =
                    NativeValueArtifactRead(
                            file = NativeValueArtifactVirtualFile(target.local, projection.observation),
                            reviewed = model.artifact,
                            observation = projection.observation,
                            permit = permit,
                        )
                        .read()
            ) {
                is Refinement.Refined -> read.value
                is Refinement.Rejected -> return read
            }
        val observation =
            LibraryArtifactObservation(
                basis = caller.basis,
                root = target.root,
                moduleName = target.module,
                binaryDigest = content.digest,
                version = content.version,
                origin = LibraryModelDeclarationOrigin.LIBRARY,
            )
        return when (
            val binding =
                ReviewedLibraryBinding.fromCompiler(
                    invocation,
                    model,
                    observation,
                )
        ) {
            is Refinement.Refined -> {
                projection.observation.count(IntellijReadCounter.VALUE_MODEL_ARTIFACT_ADMITTED)
                binding
            }
            is Refinement.Rejected -> artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED)
        }
    }

    fun target(
        module: KaLibraryModule,
        declaration: PsiNamedElement,
    ): Refinement<NativeValueReviewedTarget, NativeValueModelFailure> {
        val file =
            declaration.containingFile?.virtualFile ?: return artifactRejected(NativeValueArtifactFailure.MISSING)
        val root =
            when (val selected = selectRoot(module, file)) {
                is Refinement.Refined -> selected.value
                is Refinement.Rejected -> return selected
            }
        val archive =
            (root.fileSystem as? ArchiveFileSystem)?.getLocalByEntry(root)
                ?: return artifactRejected(NativeValueArtifactFailure.UNAVAILABLE)
        val detached =
            when (val parsed = DetachedVirtualFileUrl.parse(root.url)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return artifactRejected(NativeValueArtifactFailure.METADATA_INVALID)
            }
        val name =
            when (val parsed = LibraryModelModuleName.parse(module.libraryName)) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return artifactRejected(NativeValueArtifactFailure.METADATA_INVALID)
            }
        return Refinement.Refined(
            NativeValueReviewedTarget(
                declaration = declaration,
                local = archive,
                root = detached,
                module = name,
            )
        )
    }

    private fun selectRoot(
        module: KaLibraryModule,
        file: VirtualFile,
    ): Refinement<VirtualFile, NativeValueModelFailure> {
        var selected: VirtualFile? = null
        for (root in module.binaryVirtualFiles) {
            when (val admitted = permit()) {
                is Refinement.Refined -> Unit
                is Refinement.Rejected -> return Refinement.Rejected(NativeValueModelFailure.Limited(admitted.failure))
            }
            if (!VfsUtilCore.isAncestor(root, file, true)) continue
            if (selected != null && selected != root) return artifactRejected(NativeValueArtifactFailure.AMBIGUOUS_ROOT)
            selected = root
        }
        return selected?.let { Refinement.Refined(it) } ?: artifactRejected(NativeValueArtifactFailure.MISSING)
    }

    private fun projectInvocation(
        target: NativeValueReviewedTarget,
        expression: PsiElement,
    ): Refinement<ValueInvocation, NativeValueModelFailure> {
        when (scope.admitProviderSite(target.declaration.containingFile?.virtualFile)) {
            RelationProviderScopeAdmission.ADMITTED,
            RelationProviderScopeAdmission.LIBRARY_POLICY_EXCLUDED -> Unit
            RelationProviderScopeAdmission.SOURCE_DOMAIN_EXCLUDED,
            RelationProviderScopeAdmission.UNAVAILABLE ->
                return artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED)
        }
        when (val admitted = permit()) {
            is Refinement.Refined -> Unit
            is Refinement.Rejected -> return Refinement.Rejected(NativeValueModelFailure.Limited(admitted.failure))
        }
        val declaration =
            when (val actual = projection.project(target.declaration)) {
                is IntellijRelationDeclarationProjection.Projected -> actual.evidence
                IntellijRelationDeclarationProjection.Unsupported ->
                    return artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED)
            }
        val endpoint =
            when (
                val admitted =
                    RelationEndpoint.resolve(
                        lease = caller.enclosing.lease,
                        scope = scope.request.searchScope,
                        evidence = declaration,
                        constraints = scope.request.searchConstraints,
                    )
            ) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED)
            }
        return when (
            val admitted =
                ValueInvocation.fromCompiler(
                    enclosing = caller.enclosing,
                    range = sourceValueRange(expression),
                    callable = endpoint,
                    executionOwner = caller,
                )
        ) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> artifactRejected(NativeValueArtifactFailure.BINDING_REJECTED)
        }
    }
}

internal data class NativeValueReviewedTarget(
    val declaration: PsiNamedElement,
    val local: VirtualFile,
    val root: DetachedVirtualFileUrl,
    val module: LibraryModelModuleName,
)
