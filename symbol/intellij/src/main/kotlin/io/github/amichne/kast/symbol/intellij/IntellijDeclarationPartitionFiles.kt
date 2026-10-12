package io.github.amichne.kast.symbol.intellij

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import io.github.amichne.kast.symbol.contract.SemanticFilePartition
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity

/** VFS effects only. The existing source adapter owns admission and partition decisions. */
internal interface IntellijDeclarationPartitionFiles {
    fun find(partition: SemanticFilePartition): VirtualFile?

    fun children(directory: VirtualFile): List<VirtualFile>
}

internal object NativeIntellijDeclarationPartitionFiles : IntellijDeclarationPartitionFiles {
    override fun find(partition: SemanticFilePartition): VirtualFile? {
        val url =
            when (val identity = partition.location) {
                is SymbolDiscoveryFileIdentity.Workspace -> "file://${identity.path.value}"
                is SymbolDiscoveryFileIdentity.External -> identity.url.value
            }
        return VirtualFileManager.getInstance().findFileByUrl(url)
    }

    override fun children(directory: VirtualFile): List<VirtualFile> = directory.children.asList()
}
