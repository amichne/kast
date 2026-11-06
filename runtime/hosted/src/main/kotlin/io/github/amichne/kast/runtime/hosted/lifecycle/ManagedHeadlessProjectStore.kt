package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.configurationStore.ProjectStoreDescriptor
import com.intellij.configurationStore.ProjectStorePathCustomizer
import com.intellij.configurationStore.ProjectStorePathManager
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.runtime.hosted.OwnedHostedEndpoint
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path

/** Native storage keeps the actual repository identity and all generated metadata in private state. */
class ManagedHeadlessProjectStore : ProjectStorePathCustomizer {
    override fun getStoreDirectoryPath(projectRoot: Path): ProjectStoreDescriptor? {
        val host = ManagedHeadlessHost.current() ?: return null
        val store = host.projectStore(projectRoot) ?: return null
        if (store.toFile().canonicalFile.toPath() != store) return null
        // The owned path excludes customizer selection, so the native descriptor owns every storage rule.
        val native = ProjectStorePathManager.getInstance().getStoreDescriptor(store)
        return object : ProjectStoreDescriptor by native {
            override val projectIdentityFile: Path = projectRoot
            override val historicalProjectBasePath: Path = projectRoot
            override val projectName: String = projectRoot.fileName.toString()
        }
    }
}

internal fun ManagedHeadlessHost.projectStore(projectRoot: Path): Path? {
    val state = environment.state
    val installation = environment.installation
    if (!projectRoot.isAbsolute || projectRoot.normalize() != projectRoot) return null
    if (projectRoot.startsWith(state) || state.startsWith(projectRoot)) return null
    if (projectRoot.startsWith(installation) || installation.startsWith(projectRoot)) return null
    val root =
        when (val admitted = CanonicalWorkspaceRoot.fromCanonicalPath(projectRoot)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return null
        }
    val identity = OwnedHostedEndpoint.directory(environment.system, root).fileName
    return environment.system.resolve("kast-projects").resolve(identity)
}

/** Descriptor admission precedes every native open and grants no import or readiness fact. */
internal fun ManagedHeadlessHost.admitProjectStore(
    root: CanonicalWorkspaceRoot
): Refinement<HeadlessProjectStore, IdeLifecycleFailure> {
    val repository = Path.of(root.value)
    val store = projectStore(repository) ?: return Refinement.Rejected(IdeLifecycleFailure.INVALID_REQUEST)
    val descriptor = ProjectStorePathManager.getInstance().getStoreDescriptor(repository)
    return when (
        val admitted =
            HeadlessProjectStore.admit(
                root,
                store.resolve(".idea"),
                descriptor.projectIdentityFile,
                descriptor.historicalProjectBasePath,
                descriptor.dotIdea,
                descriptor.isExternalStorageSupported,
            )
    ) {
        is Refinement.Refined -> {
            recordManagedHeadless(ManagedHeadlessObservation.StorageAdmitted)
            admitted
        }
        is Refinement.Rejected -> {
            recordManagedHeadless(ManagedHeadlessObservation.StorageRejected(admitted.failure))
            Refinement.Rejected(IdeLifecycleFailure.UNSUPPORTED_HOST_MODE)
        }
    }
}

/** A native descriptor establishes the repository identity and the exact private metadata directory together. */
internal class HeadlessProjectStore private constructor(val root: CanonicalWorkspaceRoot, val directory: Path) {
    companion object {
        fun admit(
            root: CanonicalWorkspaceRoot,
            expectedDirectory: Path,
            identity: Path,
            base: Path,
            directory: Path?,
            externalStorage: Boolean,
        ): Refinement<HeadlessProjectStore, HeadlessProjectStoreFailure> =
            when {
                identity != Path.of(root.value) || base != identity ->
                    Refinement.Rejected(HeadlessProjectStoreFailure.WRONG_PROJECT_IDENTITY)
                directory != expectedDirectory -> Refinement.Rejected(HeadlessProjectStoreFailure.WRONG_METADATA_PATH)
                !externalStorage -> Refinement.Rejected(HeadlessProjectStoreFailure.EXTERNAL_STORAGE_UNAVAILABLE)
                else -> Refinement.Refined(HeadlessProjectStore(root, expectedDirectory))
            }
    }
}
