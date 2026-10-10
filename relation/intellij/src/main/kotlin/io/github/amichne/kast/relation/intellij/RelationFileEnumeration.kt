package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.psi.search.DelegatingGlobalSearchScope
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.impl.VirtualFileEnumeration
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.call
import java.nio.file.Path
import java.util.Collections

internal sealed interface RelationNativeFileLookup {
    data class Found(val file: VirtualFile) : RelationNativeFileLookup

    data object Unavailable : RelationNativeFileLookup
}

/** Narrow external observation; planning, traversal, admission and budget decisions remain production rules. */
internal fun interface RelationNativeFileLookupPort {
    fun find(path: Path): RelationNativeFileLookup

    companion object {
        val Local = RelationNativeFileLookupPort { path ->
            LocalFileSystem.getInstance().findFileByNioFile(path)?.let(RelationNativeFileLookup::Found)
                ?: RelationNativeFileLookup.Unavailable
        }
    }
}

internal sealed interface RelationFileEnumerationPreparation {
    data class Complete(val universe: CompleteRelationFileUniverse) : RelationFileEnumerationPreparation

    data class Declined(val reason: RelationFileEnumerationDecline) : RelationFileEnumerationPreparation
}

/** Positive persistent VFS identity, admitted only inside the native read. */
@JvmInline
internal value class RelationNativeFileId private constructor(val value: Int) {
    companion object {
        fun admit(file: VirtualFile): Refinement<RelationNativeFileId, RelationFileEnumerationDecline> {
            val native =
                file as? VirtualFileWithId
                    ?: return Refinement.Rejected(RelationFileEnumerationDecline.FILE_ID_UNAVAILABLE)
            val id = native.id
            if (id <= 0) return Refinement.Rejected(RelationFileEnumerationDecline.FILE_ID_UNAVAILABLE)
            return Refinement.Refined(RelationNativeFileId(id))
        }
    }
}

/** Complete superset of this admitted source scope, owned by exactly one read-action attempt. */
internal class CompleteRelationFileUniverse private constructor(files: Map<RelationNativeFileId, VirtualFile>) {
    private val files = files.toMap()
    private val ids = this.files.keys.map { it.value }.toIntArray()
    private val nativeIds = ids.toSet()

    fun contains(id: Int): Boolean = id in nativeIds

    fun ids(): IntArray = ids.copyOf()

    fun files(): Collection<VirtualFile> = Collections.unmodifiableCollection(files.values)

    companion object {
        /** Never publishes a partial inventory, including when optional work is declined. */
        fun prepare(
            plan: RelationFileEnumerationPlan,
            resources: ResourceBudget,
            allowance: IntellijRelationAllowance,
            limits: ReadLimits,
            observation: IntellijReadObservation,
            lookup: RelationNativeFileLookupPort = RelationNativeFileLookupPort.Local,
        ): RelationFileEnumerationPreparation =
            observation.call(IntellijReadCall.RELATION_FILE_ENUMERATION_PREPARATION) {
                fun declined(reason: RelationFileEnumerationDecline): RelationFileEnumerationPreparation.Declined {
                    observation.count(reason.counter())
                    return RelationFileEnumerationPreparation.Declined(reason)
                }
                val selected =
                    when (plan) {
                        is RelationFileEnumerationPlan.Unavailable -> return@call declined(plan.reason)
                        is RelationFileEnumerationPlan.Selected -> plan
                    }
                when (
                    val inventory =
                        RelationFileInventory(resources, allowance, limits, observation, lookup).read(selected)
                ) {
                    is Refinement.Rejected -> declined(inventory.failure)
                    is Refinement.Refined -> {
                        observation.count(IntellijReadCounter.RELATION_FILE_ENUMERATION_COMPLETE)
                        RelationFileEnumerationPreparation.Complete(CompleteRelationFileUniverse(inventory.value))
                    }
                }
            }
    }
}

/** An intersection only narrows the scope, so its original complete ID superset remains sound. */
internal class EnumeratedRelationScope(
    base: GlobalSearchScope,
    private val universe: CompleteRelationFileUniverse,
    private val observation: IntellijReadObservation,
) : DelegatingGlobalSearchScope(base, universe), VirtualFileEnumeration {
    override fun contains(fileId: Int): Boolean =
        observation.call(IntellijReadCall.RELATION_SCOPE_FILE_ID_MEMBERSHIP) {
            ProgressManager.checkCanceled()
            universe.contains(fileId).also {
                observation.count(
                    if (it) IntellijReadCounter.RELATION_SCOPE_FILE_IDS_ADMITTED
                    else IntellijReadCounter.RELATION_SCOPE_FILE_IDS_EXCLUDED
                )
            }
        }

    override fun asArray(): IntArray =
        observation.call(IntellijReadCall.RELATION_SCOPE_FILE_ID_ARRAY) {
            ProgressManager.checkCanceled()
            universe.ids()
        }

    override fun getFilesIfCollection(): Collection<VirtualFile> =
        observation.call(IntellijReadCall.RELATION_SCOPE_FILE_COLLECTION) {
            Collections.unmodifiableList(
                universe.files().filter { file ->
                    ProgressManager.checkCanceled()
                    delegate.contains(file)
                }
            )
        }

    override fun intersectWith(scope: GlobalSearchScope): GlobalSearchScope =
        EnumeratedRelationScope(super.intersectWith(scope), universe, observation)
}
