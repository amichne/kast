@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import java.nio.file.Path
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule

/** Exact module roots and VFS ancestry bind the resolved PSI to the separately hashed artifact. */
internal fun packetLibraryArtifactAffiliation(
    module: KaModule,
    declaration: PsiElement?,
    artifactPath: String,
): NativePacketLibraryArtifactAffiliation {
    val local = checkNotNull(VirtualFileManager.getInstance().findFileByNioPath(Path.of(artifactPath)))
    val artifact = checkNotNull(JarFileSystem.getInstance().getJarRootForLocalFile(local))
    val binary =
        when (module) {
            is KaLibraryModule -> module
            is KaLibrarySourceModule -> module.binaryLibrary
            else -> null
        }
    val roots = binary?.binaryVirtualFiles.orEmpty()
    val file = declaration?.containingFile?.virtualFile
    return NativePacketLibraryArtifactAffiliation(
        artifactRoot = artifact.url,
        moduleRoots = roots.map { it.url },
        declarationUrl = file?.url,
        containsDeclaration = artifact in roots && file != null && VfsUtilCore.isAncestor(artifact, file, true),
    )
}

internal data class NativePacketLibraryArtifactAffiliation(
    val artifactRoot: String,
    val moduleRoots: List<String>,
    val declarationUrl: String?,
    val containsDeclaration: Boolean,
)
