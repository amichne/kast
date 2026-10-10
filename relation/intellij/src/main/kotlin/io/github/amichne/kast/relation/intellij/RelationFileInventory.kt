package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.call
import java.nio.file.Path

/** One native attempt's bounded metadata walk; it never opens source or external dependency streams. */
internal class RelationFileInventory(
    private val resources: ResourceBudget,
    private val allowance: IntellijRelationAllowance,
    private val limits: ReadLimits,
    private val observation: IntellijReadObservation,
    private val lookup: RelationNativeFileLookupPort,
) {
    private val available = (resources.workUnitLimit.value - allowance.examined).coerceAtLeast(0L) / 4L
    private val began = allowance.examined
    private val retained = linkedMapOf<RelationNativeFileId, VirtualFile>()
    private val visited = mutableSetOf<Path>()

    fun read(
        plan: RelationFileEnumerationPlan.Selected
    ): Refinement<Map<RelationNativeFileId, VirtualFile>, RelationFileEnumerationDecline> {
        for (root in plan.roots) {
            when (val admission = step()) {
                is Refinement.Rejected -> return admission
                is Refinement.Refined -> Unit
            }
            val found =
                when (val result = observation.call(IntellijReadCall.VFS_FIND_FILE) { lookup.find(root.path) }) {
                    is RelationNativeFileLookup.Found -> result.file
                    RelationNativeFileLookup.Unavailable ->
                        return Refinement.Rejected(RelationFileEnumerationDecline.ROOT_UNAVAILABLE)
                }
            if (found.isDirectory != (root is RelationFileEnumerationRoot.Directory)) {
                return Refinement.Rejected(RelationFileEnumerationDecline.ROOT_KIND_MISMATCH)
            }
            when (val result = walk(root, found, plan.admitsPath)) {
                is Refinement.Rejected -> return result
                is Refinement.Refined -> Unit
            }
        }
        return Refinement.Refined(retained.toMap())
    }

    private fun step(): Refinement<Unit, RelationFileEnumerationDecline> {
        ProgressManager.checkCanceled()
        if (allowance.elapsedLimitReached(resources))
            return Refinement.Rejected(RelationFileEnumerationDecline.TIME_LIMIT)
        if (allowance.examined - began >= available)
            return Refinement.Rejected(RelationFileEnumerationDecline.WORK_LIMIT)
        allowance.examine()
        return Refinement.Refined(Unit)
    }

    private fun walk(
        root: RelationFileEnumerationRoot,
        found: VirtualFile,
        admitsPath: (Path) -> Boolean,
    ): Refinement<Unit, RelationFileEnumerationDecline> {
        val pending = ArrayDeque<RelationInventoryEntry>()
        pending.add(RelationInventoryEntry(root.path, found))
        while (pending.isNotEmpty()) {
            when (val admission = step()) {
                is Refinement.Rejected -> return admission
                is Refinement.Refined -> Unit
            }
            val entry = pending.removeLast()
            val node =
                when (val result = inspect(entry, admitsPath)) {
                    is Refinement.Rejected -> return result
                    is Refinement.Refined -> result.value
                }
            if (node != RelationInventoryNode.DIRECTORY) continue
            when (val children = children(entry, root, pending.size)) {
                is Refinement.Rejected -> return children
                is Refinement.Refined -> pending.addAll(children.value)
            }
        }
        return Refinement.Refined(Unit)
    }

    private fun inspect(
        entry: RelationInventoryEntry,
        admitsPath: (Path) -> Boolean,
    ): Refinement<RelationInventoryNode, RelationFileEnumerationDecline> {
        observation.count(IntellijReadCounter.RELATION_FILE_ENUMERATION_NODES)
        if (!entry.file.isValid) return Refinement.Rejected(RelationFileEnumerationDecline.FILE_INVALID)
        when (val path = absolutePath(entry.file)) {
            is Refinement.Rejected -> return path
            is Refinement.Refined ->
                if (path.value != entry.path) return Refinement.Rejected(RelationFileEnumerationDecline.PATH_MISMATCH)
        }
        if (!visited.add(entry.path)) return Refinement.Refined(RelationInventoryNode.FINISHED)
        if (entry.file.isDirectory) return Refinement.Refined(RelationInventoryNode.DIRECTORY)
        if (admitsPath(entry.path)) {
            when (val result = retain(entry.file)) {
                is Refinement.Rejected -> return result
                is Refinement.Refined -> Unit
            }
        }
        return Refinement.Refined(RelationInventoryNode.FINISHED)
    }

    private fun children(
        entry: RelationInventoryEntry,
        root: RelationFileEnumerationRoot,
        pendingSize: Int,
    ): Refinement<List<RelationInventoryEntry>, RelationFileEnumerationDecline> {
        val children = observation.call(IntellijReadCall.VFS_CHILDREN) { entry.file.children }
        // Bound pending storage before copying any SDK child array into our inventory.
        if (children.size.toLong() + pendingSize > available - (allowance.examined - began)) {
            return Refinement.Rejected(RelationFileEnumerationDecline.WORK_LIMIT)
        }
        val selected = mutableListOf<RelationInventoryEntry>()
        for (child in children) {
            when (val admission = step()) {
                is Refinement.Rejected -> return admission
                is Refinement.Refined -> Unit
            }
            observation.count(IntellijReadCounter.RELATION_FILE_ENUMERATION_CHILDREN)
            val path =
                when (val result = absolutePath(child)) {
                    is Refinement.Rejected -> return result
                    is Refinement.Refined -> result.value
                }
            if (path.parent != entry.path) return Refinement.Rejected(RelationFileEnumerationDecline.PATH_MISMATCH)
            if (
                root is RelationFileEnumerationRoot.Directory &&
                    root.traversal == RelationDirectoryTraversal.DIRECT &&
                    child.isDirectory
            )
                continue
            selected.add(RelationInventoryEntry(path, child))
        }
        return Refinement.Refined(selected)
    }

    private fun retain(file: VirtualFile): Refinement<Unit, RelationFileEnumerationDecline> {
        val id =
            when (val admitted = RelationNativeFileId.admit(file)) {
                is Refinement.Refined -> admitted.value
                is Refinement.Rejected -> return admitted
            }
        if (id in retained && retained.getValue(id) !== file)
            return Refinement.Rejected(RelationFileEnumerationDecline.FILE_ID_COLLISION)
        if (id !in retained && retained.size >= limits[ReadLimitParameter.DISCOVERY_FILES].value) {
            return Refinement.Rejected(RelationFileEnumerationDecline.FILE_CAPACITY)
        }
        retained[id] = file
        observation.count(IntellijReadCounter.RELATION_FILE_ENUMERATION_FILES)
        return Refinement.Refined(Unit)
    }

    private fun absolutePath(file: VirtualFile): Refinement<Path, RelationFileEnumerationDecline> =
        when (val native = relationNativePath(file)) {
            is IntellijRelationNativePath.Absolute -> Refinement.Refined(native.value)
            IntellijRelationNativePath.Relative,
            IntellijRelationNativePath.Unavailable ->
                Refinement.Rejected(RelationFileEnumerationDecline.PATH_UNAVAILABLE)
        }
}

private data class RelationInventoryEntry(val path: Path, val file: VirtualFile)

private enum class RelationInventoryNode {
    DIRECTORY,
    FINISHED,
}
