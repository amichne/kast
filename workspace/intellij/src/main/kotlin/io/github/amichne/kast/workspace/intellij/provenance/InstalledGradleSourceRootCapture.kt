package io.github.amichne.kast.workspace.intellij.provenance

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ExternalProjectInfo
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ContentRootData
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData
import org.jetbrains.plugins.gradle.util.gradlePathOrNull
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Closed reason that exact source-root provenance could not be proven. */
internal enum class GradleSourceRootProvenanceFailure {
    MISSING_PRODUCER_EVIDENCE,
    CONFLICTING_PRODUCER_EVIDENCE,
}

/** Closed result of combining producer evidence with one imported source-root entry. */
internal sealed interface GradleSourceRootProvenanceResolution {
    data class Proven(
        val provenance: WorkspaceSourceRootProvenance,
    ) : GradleSourceRootProvenanceResolution

    data class Unknown(
        val failure: GradleSourceRootProvenanceFailure,
    ) : GradleSourceRootProvenanceResolution
}

/** Closed reason that imported Gradle source-root boundaries could not be detached safely. */
internal enum class InstalledGradleSourceRootCaptureFailure {
    PROJECT_STRUCTURE_UNAVAILABLE,
    PROJECT_OWNERSHIP_UNAVAILABLE,
    INVALID_LINKED_BUILD_ROOT,
    INVALID_SOURCE_ROOT,
}

/** Closed result of detaching source-root boundaries from an imported Gradle model. */
internal sealed interface InstalledGradleSourceRootCapture {
    data class Captured(
        val boundaries: List<WorkspaceSourceRootBoundary>,
    ) : InstalledGradleSourceRootCapture

    data class Rejected(
        val failure: InstalledGradleSourceRootCaptureFailure,
    ) : InstalledGradleSourceRootCapture
}

private sealed interface ImportedGradlePathCapture {
    data class Captured(
        val path: Path,
    ) : ImportedGradlePathCapture

    data class Rejected(
        val failure: InstalledGradleSourceRootCaptureFailure,
    ) : ImportedGradlePathCapture
}

/** Exact-path authority over Gradle producer evidence retained before IntelliJ projection. */
internal class GradleSourceRootProvenanceAuthority private constructor(
    private val imports: List<GradleSourceRootProducerImport>,
) {
    /**
     * Proof transition: `(Path, ExternalSystemSourceType) ->
     * GradleSourceRootProvenanceResolution`.
     *
     * Establishes authored/generated provenance only from exact Gradle producer evidence or an
     * explicit generated source type. Ordinary source types provide no authored proof. Missing or
     * contradictory producer observations return the closed [GradleSourceRootProvenanceFailure].
     * Raw source-type extraction is permitted only at the imported content-root boundary.
     */
    fun resolve(
        sourceRoot: Path,
        sourceType: ExternalSystemSourceType,
    ): GradleSourceRootProvenanceResolution {
        val exactRoot = sourceRoot.toAbsolutePath().normalize()
        val producerProvenance = imports.asSequence()
            .filterIsInstance<GradleSourceRootProducerImport.Captured>()
            .flatMap { capture -> capture.entries.asSequence() }
            .filter { evidence -> evidence.sourceRoot.toPath() == exactRoot }
            .map(GradleSourceRootProducerEvidence::provenance)
            .toSet()

        if (producerProvenance.size > 1) {
            return GradleSourceRootProvenanceResolution.Unknown(
                GradleSourceRootProvenanceFailure.CONFLICTING_PRODUCER_EVIDENCE,
            )
        }
        val producer = producerProvenance.singleOrNull()
        if (sourceType.isGenerated) {
            return if (producer == GradleSourceRootProducerProvenance.AUTHORED) {
                GradleSourceRootProvenanceResolution.Unknown(
                    GradleSourceRootProvenanceFailure.CONFLICTING_PRODUCER_EVIDENCE,
                )
            } else {
                GradleSourceRootProvenanceResolution.Proven(
                    WorkspaceSourceRootProvenance.GENERATED,
                )
            }
        }
        return when (producer) {
            GradleSourceRootProducerProvenance.AUTHORED ->
                GradleSourceRootProvenanceResolution.Proven(
                    WorkspaceSourceRootProvenance.AUTHORED,
                )
            GradleSourceRootProducerProvenance.GENERATED ->
                GradleSourceRootProvenanceResolution.Proven(
                    WorkspaceSourceRootProvenance.GENERATED,
                )
            null -> GradleSourceRootProvenanceResolution.Unknown(
                GradleSourceRootProvenanceFailure.MISSING_PRODUCER_EVIDENCE,
            )
        }
    }

    companion object {
        /**
         * Proof transition: `Iterable<GradleSourceRootProducerImport> ->
         * GradleSourceRootProvenanceAuthority`.
         *
         * Establishes an immutable exact-path authority over all captured Gradle module evidence.
         * Rejected module captures remain present so an ordinary root without positive evidence
         * cannot acquire authored provenance. Raw import nodes may be extracted only by
         * [sourceRootBoundaries].
         */
        fun compile(
            imports: Iterable<GradleSourceRootProducerImport>,
        ): GradleSourceRootProvenanceAuthority = GradleSourceRootProvenanceAuthority(
            imports.toList(),
        )
    }
}

/**
 * Proof transition: `ExternalProjectInfo -> InstalledGradleSourceRootCapture`.
 *
 * Captured establishes exact Gradle source-set ownership plus producer-authoritative provenance
 * for each supported source root. [InstalledGradleSourceRootCaptureFailure] closes missing model
 * structure, ownership, and malformed imported paths. Missing or conflicting provenance is
 * retained as `UNKNOWN`, which the workspace contract rejects before publication. Live [DataNode]
 * and content-root values are extracted only inside this imported-model boundary.
 */
internal fun ExternalProjectInfo.sourceRootBoundaries(): InstalledGradleSourceRootCapture {
    val structure = externalProjectStructure
                    ?: return InstalledGradleSourceRootCapture.Rejected(
                        InstalledGradleSourceRootCaptureFailure.PROJECT_STRUCTURE_UNAVAILABLE,
                    )
    val producerImports = mutableListOf<GradleSourceRootProducerImport>()
    structure.visit { node ->
        if (node.key == GRADLE_SOURCE_ROOT_PRODUCER_IMPORT_KEY) {
            (node.data as? GradleSourceRootProducerImport)?.let(producerImports::add)
        }
    }
    val authority = GradleSourceRootProvenanceAuthority.compile(producerImports)
    var capture: InstalledGradleSourceRootCapture =
        InstalledGradleSourceRootCapture.Captured(emptyList())
    structure.visit { node ->
        val sourceSet = node.data as? GradleSourceSetData ?: return@visit
        capture = when (val existing = capture) {
            is InstalledGradleSourceRootCapture.Rejected -> existing
            is InstalledGradleSourceRootCapture.Captured ->
                when (val next = sourceSet.sourceRootBoundaries(node, authority)) {
                    is InstalledGradleSourceRootCapture.Rejected -> next
                    is InstalledGradleSourceRootCapture.Captured ->
                        InstalledGradleSourceRootCapture.Captured(
                            existing.boundaries + next.boundaries,
                        )
                }
        }
    }
    return capture
}

/**
 * Proof transition: `(GradleSourceSetData, DataNode,
 * GradleSourceRootProvenanceAuthority) -> InstalledGradleSourceRootCapture`.
 *
 * Captured establishes detached exact project/source-set ownership and the strongest available
 * provenance for every supported content root below this source set. Missing ownership or
 * malformed linked-build/source-root paths return [InstalledGradleSourceRootCaptureFailure].
 * Missing or conflicting provenance is retained as `UNKNOWN`; raw content-root extraction is
 * permitted only inside [sourceRootBoundaries].
 */
private fun GradleSourceSetData.sourceRootBoundaries(
    node: DataNode<*>,
    authority: GradleSourceRootProvenanceAuthority,
): InstalledGradleSourceRootCapture {
    val projectPath = (node.parent?.data as? ModuleData)?.gradlePathOrNull
                      ?: return InstalledGradleSourceRootCapture.Rejected(
                          InstalledGradleSourceRootCaptureFailure.PROJECT_OWNERSHIP_UNAVAILABLE,
                      )
    val sourceSetName = externalName.substringAfterLast(':')
    val buildRoot = when (
        val path = captureImportedGradlePath(
            linkedExternalProjectPath,
            InstalledGradleSourceRootCaptureFailure.INVALID_LINKED_BUILD_ROOT,
        )
    ) {
        is ImportedGradlePathCapture.Captured -> path.path
        is ImportedGradlePathCapture.Rejected ->
            return InstalledGradleSourceRootCapture.Rejected(path.failure)
    }
    val contentRoots = mutableListOf<ContentRootData>()
    node.visit { child ->
        if (child.key != ProjectKeys.CONTENT_ROOT) return@visit
        (child.data as? ContentRootData)?.let(contentRoots::add)
    }
    val boundaries = mutableListOf<WorkspaceSourceRootBoundary>()
    for (content in contentRoots) {
        for (type in ExternalSystemSourceType.entries) {
            val kind = when (type) {
                ExternalSystemSourceType.SOURCE,
                ExternalSystemSourceType.SOURCE_GENERATED,
                    -> WorkspaceSourceRootKind.PRODUCTION
                ExternalSystemSourceType.TEST,
                ExternalSystemSourceType.TEST_GENERATED,
                    -> WorkspaceSourceRootKind.TEST
                ExternalSystemSourceType.EXCLUDED,
                ExternalSystemSourceType.RESOURCE,
                ExternalSystemSourceType.TEST_RESOURCE,
                ExternalSystemSourceType.RESOURCE_GENERATED,
                ExternalSystemSourceType.TEST_RESOURCE_GENERATED,
                    -> continue
            }
            for (sourceRoot in content.getPaths(type)) {
                val path = when (
                    val capture = captureImportedGradlePath(
                        sourceRoot.path,
                        InstalledGradleSourceRootCaptureFailure.INVALID_SOURCE_ROOT,
                    )
                ) {
                    is ImportedGradlePathCapture.Captured -> capture.path
                    is ImportedGradlePathCapture.Rejected -> {
                        return InstalledGradleSourceRootCapture.Rejected(capture.failure)
                    }
                }
                val provenance = when (val resolved = authority.resolve(path, type)) {
                    is GradleSourceRootProvenanceResolution.Proven -> resolved.provenance
                    is GradleSourceRootProvenanceResolution.Unknown ->
                        WorkspaceSourceRootProvenance.UNKNOWN
                }
                boundaries += WorkspaceSourceRootBoundary(
                    internalName,
                    buildRoot,
                    projectPath,
                    sourceSetName,
                    path,
                    kind,
                    provenance,
                )
            }
        }
    }
    return InstalledGradleSourceRootCapture.Captured(boundaries)
}

/**
 * Proof transition: `(String, InstalledGradleSourceRootCaptureFailure) ->
 * ImportedGradlePathCapture`.
 *
 * Captured establishes an absolute, lexically normalized imported Gradle path. The supplied closed
 * failure distinguishes linked-build and source-root rejection. Raw text extraction is permitted
 * only at the imported Gradle model boundary.
 */
private fun captureImportedGradlePath(
    raw: String,
    invalid: InstalledGradleSourceRootCaptureFailure,
): ImportedGradlePathCapture {
    val path = try {
        Path.of(raw)
    } catch (_: InvalidPathException) {
        return ImportedGradlePathCapture.Rejected(invalid)
    }
    if (!path.isAbsolute || path.normalize() != path) {
        return ImportedGradlePathCapture.Rejected(invalid)
    }
    return ImportedGradlePathCapture.Captured(path)
}
