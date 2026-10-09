package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.CompleteSemanticModuleSources
import io.github.amichne.kast.topology.contract.SemanticDependencyClosure
import io.github.amichne.kast.topology.contract.SemanticDependencySource
import io.github.amichne.kast.topology.contract.SemanticResolutionInputs
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSourcePath
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.call
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments

internal class SemanticNativeModuleInputs(
    project: Project,
    private val model: WorkspaceSearchScopeModel,
    private val closure: SemanticDependencyClosure,
    private val limits: ReadLimits,
    private val budget: DependencyCaptureBudget,
    private val memo: SemanticNativeFileMemo,
) {
    private val files = SemanticNativeFiles(project, limits, budget, memo)
    private val configuration = SemanticNativeConfiguration(project, limits, budget, files)

    fun module(
        identity: WorkspaceModuleIdentity,
        module: Module,
    ): SemanticCapture<NativeModuleInputs> {
        val sdkDigest = SemanticInputDigest()
        val compilerDigest = SemanticInputDigest()
        val classpathDigest = SemanticInputDigest()
        val arguments =
            when (val captured = configuration.arguments(module, compilerDigest)) {
                is Refinement.Refined -> captured.value
                is Refinement.Rejected -> return captured
            }
        when (val captured = configuration.passivePlugins(identity, arguments, compilerDigest, classpathDigest)) {
            is Refinement.Rejected -> return captured
            is Refinement.Refined -> Unit
        }
        val manager = ModuleRootManager.getInstance(module)
        val roots =
            when (val captured = roots(identity, manager, compilerDigest)) {
                is Refinement.Refined -> captured.value
                is Refinement.Rejected -> return captured
            }
        when (val captured = sdk(identity, manager, arguments, sdkDigest)) {
            is Refinement.Rejected -> return captured
            is Refinement.Refined -> Unit
        }
        when (val captured = classpath(identity, manager, arguments, classpathDigest)) {
            is Refinement.Rejected -> return captured
            is Refinement.Refined -> Unit
        }
        return when (val captured = sources(identity, roots)) {
            is Refinement.Rejected -> captured
            is Refinement.Refined ->
                Refinement.Refined(
                    NativeModuleInputs(
                        captured.value,
                        SemanticResolutionInputs(sdkDigest.finish(), compilerDigest.finish(), classpathDigest.finish()),
                    )
                )
        }
    }

    private fun sdk(
        identity: WorkspaceModuleIdentity,
        manager: ModuleRootManager,
        arguments: K2JVMCompilerArguments,
        sdkDigest: SemanticInputDigest,
    ): SemanticCapture<Unit> {
        val sdk =
            manager.sdk ?: return captureRejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE)
        if (!arguments.jdkHome.isNullOrBlank() && arguments.jdkHome != sdk.homePath)
            return captureRejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE)
        val version =
            sdk.versionString
                ?: return captureRejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE)
        sdkDigest.text("MODULE")
        sdkDigest.text(identity.value)
        sdkDigest.text(sdk.name)
        sdkDigest.text(sdk.sdkType.name)
        sdkDigest.text(version)
        return files.roots(
            budget.observation
                .call(IntellijReadCall.SDK_CLASS_ROOTS) { sdk.rootProvider.getFiles(OrderRootType.CLASSES) }
                .toList(),
            sdkDigest,
        )
    }

    private fun classpath(
        identity: WorkspaceModuleIdentity,
        manager: ModuleRootManager,
        arguments: K2JVMCompilerArguments,
        classpathDigest: SemanticInputDigest,
    ): SemanticCapture<Unit> {
        classpathDigest.text("MODULE")
        classpathDigest.text(identity.value)
        when (
            val hashed =
                files.roots(
                    budget.observation
                        .call(IntellijReadCall.RECURSIVE_CLASSPATH_ROOTS) {
                            manager.orderEntries().recursively().classes().roots
                        }
                        .toList(),
                    classpathDigest,
                )
        ) {
            is Refinement.Rejected -> return hashed
            is Refinement.Refined -> Unit
        }
        val extraPaths =
            arguments.classpath.orEmpty().split(File.pathSeparatorChar).filter { it.isNotEmpty() } +
                arguments.friendPaths.orEmpty() +
                arguments.javaSourceRoots.orEmpty() +
                arguments.javaModulePath.orEmpty().split(File.pathSeparatorChar).filter { it.isNotEmpty() }
        if (extraPaths.size > limits[ReadLimitParameter.MODEL_CLASSPATH_ENTRIES_PER_MODULE].value)
            return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        if (extraPaths.isEmpty()) return Refinement.Refined(Unit)
        val roots = mutableListOf<VirtualFile>()
        for (path in extraPaths) {
            if (!Path.of(path).isAbsolute)
                return captureRejected(SemanticDependencyCaptureFailure.INPUT_PROVIDER_UNSUPPORTED)
            roots +=
                budget.observation.call(IntellijReadCall.VFS_FIND_FILE) {
                    LocalFileSystem.getInstance().findFileByPath(path)
                } ?: return captureRejected(SemanticDependencyCaptureFailure.INPUT_UNAVAILABLE)
        }
        return files.roots(roots, classpathDigest)
    }

    private fun roots(
        identity: WorkspaceModuleIdentity,
        manager: ModuleRootManager,
        compilerDigest: SemanticInputDigest,
    ): SemanticCapture<NativeSourceRoots> {
        val admitted =
            closure.sourceRoots.filter { it.module == identity }.mapTo(linkedSetOf()) { Path.of(it.sourceRoot.value) }
        val native =
            budget.observation
                .call(IntellijReadCall.MODULE_SOURCE_ROOTS) { manager.sourceRoots }
                .mapTo(linkedSetOf()) { Path.of(it.path) }
        if (!admitted.containsAll(native))
            return captureRejected(SemanticDependencyCaptureFailure.SOURCE_ROOT_INVENTORY_MISMATCH)
        val directories = linkedMapOf<Path, VirtualFile>()
        val observations = linkedMapOf<Path, SemanticSourceRootPresence>()
        for (path in admitted) {
            when (val spent = budget.step()) {
                is Refinement.Rejected -> return spent
                is Refinement.Refined -> Unit
            }
            val file =
                budget.observation.call(IntellijReadCall.VFS_FIND_FILE) {
                    LocalFileSystem.getInstance().findFileByPath(path.toString())
                }
            val presence = presence(path, file)
            observations[path] = presence
            if (presence == SemanticSourceRootPresence.DIRECTORY) directories[path] = checkNotNull(file)
        }
        return when (val roots = CompleteSemanticSourceRoots.admit(admitted, native, observations)) {
            is Refinement.Refined -> {
                roots.value.appendTo(compilerDigest)
                Refinement.Refined(NativeSourceRoots(roots.value, directories))
            }
            is Refinement.Rejected -> roots
        }
    }

    private fun presence(path: Path, file: VirtualFile?): SemanticSourceRootPresence =
        when {
            file != null && file.isValid && file.isDirectory -> SemanticSourceRootPresence.DIRECTORY
            file == null && Files.notExists(path, LinkOption.NOFOLLOW_LINKS) -> SemanticSourceRootPresence.ABSENT
            else -> SemanticSourceRootPresence.UNAVAILABLE
        }

    private fun sources(
        identity: WorkspaceModuleIdentity,
        captured: NativeSourceRoots,
    ): SemanticCapture<CompleteSemanticModuleSources> {
        val sources = mutableListOf<SemanticDependencySource>()
        for (root in closure.sourceRoots.filter { it.module == identity }.sortedBy { it.sourceRoot.value }) {
            val path = Path.of(root.sourceRoot.value)
            if (captured.inventory.roots[path] == SemanticSourceRootPresence.ABSENT) continue
            val directory =
                captured.directories[path]
                    ?: return captureRejected(SemanticDependencyCaptureFailure.SOURCE_UNAVAILABLE)
            when (val read = files.walk(directory) { file -> source(root, file, sources) }) {
                is Refinement.Rejected -> return read
                is Refinement.Refined -> Unit
            }
        }
        return when (val admitted = CompleteSemanticModuleSources.fromCompiler(closure.graph, identity, sources)) {
            is Refinement.Refined -> admitted
            is Refinement.Rejected -> captureRejected(SemanticDependencyCaptureFailure.SOURCE_MODULE_INVENTORY_REJECTED)
        }
    }

    private fun source(
        root: ModelOwnedSourceRoot,
        file: VirtualFile,
        result: MutableList<SemanticDependencySource>,
    ): SemanticCapture<Unit> {
        if (file.extension == "kts")
            return captureRejected(SemanticDependencyCaptureFailure.COMPILER_CONFIGURATION_UNAVAILABLE)
        val path =
            when (
                val parsed =
                    WorkspaceSourcePath.parse(
                        Path.of(model.workspaceRoot.value).relativize(Path.of(file.path)).toString()
                    )
            ) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected -> return captureRejected(SemanticDependencyCaptureFailure.SOURCE_UNAVAILABLE)
            }
        return when (val hash = files.hash(file)) {
            is Refinement.Refined -> {
                result += SemanticDependencySource(root, path, hash.value)
                Refinement.Refined(Unit)
            }
            is Refinement.Rejected -> hash
        }
    }
}

private class NativeSourceRoots(val inventory: CompleteSemanticSourceRoots, val directories: Map<Path, VirtualFile>)
