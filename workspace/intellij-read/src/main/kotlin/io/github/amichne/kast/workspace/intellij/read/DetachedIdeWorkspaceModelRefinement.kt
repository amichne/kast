package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import java.nio.file.Path

/**
 * Non-empty, bounded detached modules with unique names and unambiguous source-root ownership.
 *
 * Construction is confined to [refine], which also deterministically orders the retained values.
 */
internal class RefinedDetachedModules private constructor(
    values: List<DetachedIdeModule>,
) {
    val values: List<DetachedIdeModule> = java.util.Collections.unmodifiableList(ArrayList(values))

    companion object {
        /**
         * Proof transition: `(Path, List<DetachedModuleBoundary>) ->
         * Refinement<RefinedDetachedModules, DetachedModelCaptureFailure>`.
         *
         * Establishes a non-empty, bounded, deterministically ordered aggregate of refined
         * modules with unique names and exactly one module owner per source-root location. The
         * closed expected failure is [DetachedModelCaptureFailure]. Raw module extraction is
         * permitted only at the live IntelliJ observation boundary.
         */
        internal fun refine(
            root: Path,
            boundaries: List<DetachedModuleBoundary>,
        ): Refinement<RefinedDetachedModules, DetachedModelCaptureFailure> {
            if (boundaries.isEmpty()) return rejected(DetachedModelCaptureFailure.NO_MODULES)
            if (boundaries.size > DetachedModelLimits.MAX_MODULES) {
                return rejected(DetachedModelCaptureFailure.TOO_MANY_MODULES)
            }
            val modules = ArrayList<DetachedIdeModule>(boundaries.size)
            for (boundary in boundaries) {
                when (val refined = refineModule(root, boundary)) {
                    is Refinement.Refined -> modules += refined.value
                    is Refinement.Rejected -> return refined
                }
            }
            if (modules.map(DetachedIdeModule::name).distinct().size != modules.size) {
                return rejected(DetachedModelCaptureFailure.DUPLICATE_MODULE)
            }
            val ownersByRoot = linkedMapOf<DetachedWorkspaceRelativePath, DetachedModuleName>()
            for (module in modules) {
                for (sourceRoot in module.sourceRoots) {
                    val existingOwner = ownersByRoot.putIfAbsent(sourceRoot.location, module.name)
                    if (existingOwner != null && existingOwner != module.name) {
                        return rejected(DetachedModelCaptureFailure.AMBIGUOUS_SOURCE_ROOT_OWNER)
                    }
                }
            }
            return Refinement.Refined(
                RefinedDetachedModules(modules.sortedBy { module -> module.name.value }),
            )
        }
    }
}

/**
 * Proof transition: `(CanonicalWorkspaceRoot, AdmittedIdeHostCompatibility,
 * DetachedModelBoundary) -> DetachedModelCapture`.
 *
 * Establishes an immutable, exact-root, bounded, deterministic detached model. The closed
 * expected failure is [DetachedModelCaptureFailure]. Raw platform values may be extracted only
 * by the live adapter before this refinement boundary.
 */
internal fun refineDetachedModel(
    expectedRoot: CanonicalWorkspaceRoot,
    compatibility: AdmittedIdeHostCompatibility,
    boundary: DetachedModelBoundary,
): DetachedModelCapture {
    if (boundary.disposed) return rejectedCapture(DetachedModelCaptureFailure.PROJECT_DISPOSED)
    if (!boundary.smart) return rejectedCapture(DetachedModelCaptureFailure.PROJECT_DUMB)
    if (!boundary.gradleModelComplete) {
        return rejectedCapture(DetachedModelCaptureFailure.GRADLE_MODEL_INCOMPLETE)
    }
    val exactRoot = when (
        val rootMatch = ExactObservedWorkspaceRoot.refineObservedRoot(
            boundary.projectRoot,
            expectedRoot,
        )
    ) {
        is Refinement.Refined -> rootMatch.value
        is Refinement.Rejected -> return rejectedCapture(rootMatch.failure)
    }
    val root = Path.of(exactRoot.canonicalRoot.value)
    val modules = when (val refined = RefinedDetachedModules.refine(root, boundary.modules)) {
        is Refinement.Refined -> refined.value
        is Refinement.Rejected -> return rejectedCapture(refined.failure)
    }
    return DetachedModelCapture.Captured(
        DetachedIdeWorkspaceModel.captured(
            exactRoot,
            compatibility,
            modules,
        ),
    )
}

/**
 * Proof transition: `(Path, DetachedModuleBoundary) -> Refinement<DetachedIdeModule,
 * DetachedModelCaptureFailure>`. Establishes one exact Gradle-owned bounded module. The closed
 * expected failure is [DetachedModelCaptureFailure]. Raw module values may be extracted only at
 * the live IntelliJ and cached Gradle adapter boundary.
 */
private fun refineModule(
    root: Path,
    raw: DetachedModuleBoundary,
): Refinement<DetachedIdeModule, DetachedModelCaptureFailure> {
    if (raw.disposed) return rejected(DetachedModelCaptureFailure.MODULE_DISPOSED)
    if (!raw.gradleOwned) return rejected(DetachedModelCaptureFailure.NOT_GRADLE_OWNED)
    val name = when (val value = refineIdentity(raw.name)) {
        is Refinement.Refined -> DetachedModuleName(value.value.value)
        is Refinement.Rejected -> return rejected(value.failure.identityFailure())
    }
    val buildRoot = when (
        val value = refineWorkspacePath(
            raw.gradleBuildRoot,
            root,
            DetachedModelCaptureFailure.INVALID_GRADLE_BUILD_ROOT,
            DetachedModelCaptureFailure.GRADLE_BUILD_ROOT_OUTSIDE_WORKSPACE,
        )
    ) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> return value
    }
    val projectRoot = when (
        val value = refineWorkspacePath(
            raw.gradleProjectRoot,
            root,
            DetachedModelCaptureFailure.INVALID_GRADLE_PROJECT_ROOT,
            DetachedModelCaptureFailure.GRADLE_PROJECT_ROOT_OUTSIDE_WORKSPACE,
        )
    ) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> return value
    }
    val projectIdentity = when (val value = refineIdentity(raw.gradleProjectIdentity.orEmpty())) {
        is Refinement.Refined -> DetachedGradleProjectIdentity(value.value.value)
        is Refinement.Rejected -> return rejected(
            if (value.failure == TextFailure.TOO_LONG) {
                DetachedModelCaptureFailure.IDENTITY_TOO_LONG
            } else {
                DetachedModelCaptureFailure.INVALID_GRADLE_PROJECT_IDENTITY
            },
        )
    }
    val sourceRoots = when (val value = refineSourceRoots(root, raw.sourceRoots)) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> return value
    }
    val sdk = when (val value = refineSdk(raw.sdk)) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> return value
    }
    val classpath = when (val value = refineClasspath(raw.classpath)) {
        is Refinement.Refined -> value.value
        is Refinement.Rejected -> return value
    }
    return Refinement.Refined(
        DetachedIdeModule(
            name,
            DetachedGradleModuleOwner(buildRoot, projectRoot, projectIdentity),
            sourceRoots,
            sdk,
            classpath,
        ),
    )
}

/**
 * Proof transition: `List<DetachedSourceRootBoundary> ->
 * Refinement<List<DetachedIdeSourceRoot>, DetachedModelCaptureFailure>`. Establishes a bounded,
 * unique, workspace-owned root list with one coherent kind and explicit cached-model provenance
 * per location. The closed expected failure is [DetachedModelCaptureFailure]. Raw source-root
 * values may be extracted only at the live IntelliJ `SourceFolder` adapter boundary.
 */
private fun refineSourceRoots(
    root: Path,
    rawRoots: List<DetachedSourceRootBoundary>,
): Refinement<List<DetachedIdeSourceRoot>, DetachedModelCaptureFailure> {
    if (rawRoots.isEmpty()) return rejected(DetachedModelCaptureFailure.NO_SOURCE_ROOTS)
    if (rawRoots.size > DetachedModelLimits.MAX_SOURCE_ROOTS_PER_MODULE) {
        return rejected(DetachedModelCaptureFailure.TOO_MANY_SOURCE_ROOTS)
    }
    val roots = ArrayList<DetachedIdeSourceRoot>(rawRoots.size)
    for (raw in rawRoots) {
        val location = when (
            val value = refineWorkspacePath(
                raw.path,
                root,
                DetachedModelCaptureFailure.INVALID_SOURCE_ROOT,
                DetachedModelCaptureFailure.SOURCE_ROOT_OUTSIDE_WORKSPACE,
            )
        ) {
            is Refinement.Refined -> value.value
            is Refinement.Rejected -> return value
        }
        val kind = raw.kind ?: return rejected(DetachedModelCaptureFailure.INVALID_SOURCE_ROOT_KIND)
        val provenance = raw.provenance
            ?: return rejected(DetachedModelCaptureFailure.INVALID_SOURCE_ROOT_PROVENANCE)
        roots += DetachedIdeSourceRoot(location, kind, provenance)
    }
    val byLocation = roots.groupBy { sourceRoot -> sourceRoot.location }
    if (byLocation.values.any { entries -> entries.map { it.kind }.distinct().size > 1 }) {
        return rejected(DetachedModelCaptureFailure.CONFLICTING_SOURCE_ROOT_KIND)
    }
    if (byLocation.values.any { entries -> entries.map { it.provenance }.distinct().size > 1 }) {
        return rejected(DetachedModelCaptureFailure.CONFLICTING_SOURCE_ROOT_PROVENANCE)
    }
    if (byLocation.values.any { entries -> entries.size > 1 }) {
        return rejected(DetachedModelCaptureFailure.DUPLICATE_SOURCE_ROOT)
    }
    return Refinement.Refined(roots.sortedBy { sourceRoot -> sourceRoot.location.value })
}

/**
 * Proof transition: `DetachedSdkBoundary? -> Refinement<DetachedIdeSdkIdentity,
 * DetachedModelCaptureFailure>`. Establishes three bounded SDK identity components. The closed
 * expected failure is [DetachedModelCaptureFailure]. Raw SDK values may be extracted only at the
 * live IntelliJ SDK adapter boundary.
 */
private fun refineSdk(
    raw: DetachedSdkBoundary?,
): Refinement<DetachedIdeSdkIdentity, DetachedModelCaptureFailure> {
    raw ?: return rejected(DetachedModelCaptureFailure.SDK_UNAVAILABLE)
    val name = when (val value = refineIdentity(raw.name)) {
        is Refinement.Refined -> DetachedSdkName(value.value.value)
        is Refinement.Rejected -> return rejected(value.failure.sdkFailure())
    }
    val type = when (val value = refineIdentity(raw.type)) {
        is Refinement.Refined -> DetachedSdkType(value.value.value)
        is Refinement.Rejected -> return rejected(value.failure.sdkFailure())
    }
    val version = when (val value = refineIdentity(raw.version.orEmpty())) {
        is Refinement.Refined -> DetachedSdkVersion(value.value.value)
        is Refinement.Rejected -> return rejected(value.failure.sdkFailure())
    }
    return Refinement.Refined(DetachedIdeSdkIdentity(name, type, version))
}

/**
 * Proof transition: `List<DetachedClasspathBoundary> ->
 * Refinement<List<DetachedIdeClasspathEntry>, DetachedModelCaptureFailure>`. Establishes bounded,
 * unique absolute URL identities. The closed expected failure is [DetachedModelCaptureFailure].
 * Raw classpath URLs may be extracted only from IntelliJ `VirtualFile.url` at the live adapter
 * boundary.
 */
private fun refineClasspath(
    rawEntries: List<DetachedClasspathBoundary>,
): Refinement<List<DetachedIdeClasspathEntry>, DetachedModelCaptureFailure> {
    if (rawEntries.isEmpty()) return rejected(DetachedModelCaptureFailure.NO_CLASSPATH)
    if (rawEntries.size > DetachedModelLimits.MAX_CLASSPATH_ENTRIES_PER_MODULE) {
        return rejected(DetachedModelCaptureFailure.TOO_MANY_CLASSPATH_ENTRIES)
    }
    val entries = ArrayList<DetachedIdeClasspathEntry>(rawEntries.size)
    for (raw in rawEntries) {
        when (val value = refineClasspathUrl(raw.url)) {
            is Refinement.Refined -> entries += DetachedIdeClasspathEntry(value.value)
            is Refinement.Rejected -> return value
        }
    }
    if (entries.distinct().size != entries.size) {
        return rejected(DetachedModelCaptureFailure.DUPLICATE_CLASSPATH_IDENTITY)
    }
    return Refinement.Refined(entries.sortedBy { entry -> entry.url.value })
}

private fun TextFailure.identityFailure(): DetachedModelCaptureFailure =
    if (this == TextFailure.TOO_LONG) {
        DetachedModelCaptureFailure.IDENTITY_TOO_LONG
    } else {
        DetachedModelCaptureFailure.INVALID_MODULE_NAME
    }

private fun TextFailure.sdkFailure(): DetachedModelCaptureFailure =
    if (this == TextFailure.TOO_LONG) {
        DetachedModelCaptureFailure.IDENTITY_TOO_LONG
    } else {
        DetachedModelCaptureFailure.INVALID_SDK_IDENTITY
    }

private fun rejectedCapture(failure: DetachedModelCaptureFailure): DetachedModelCapture =
    DetachedModelCapture.Rejected(failure)

private fun <Value> rejected(
    failure: DetachedModelCaptureFailure,
): Refinement<Value, DetachedModelCaptureFailure> = Refinement.Rejected(failure)
