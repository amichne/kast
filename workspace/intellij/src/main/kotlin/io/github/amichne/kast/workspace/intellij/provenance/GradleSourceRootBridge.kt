package io.github.amichne.kast.workspace.intellij.provenance

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ModuleData
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaSourceDirectory
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension
import java.io.File
import java.io.Serializable

/** Gradle's producer-owned classification for one exact IDEA source directory. */
enum class GradleSourceRootProducerProvenance {
    AUTHORED,
    GENERATED,
}

/** Closed failure to capture Gradle's source-directory producer evidence. */
internal enum class GradleSourceRootProducerCaptureFailure {
    CONFLICTING_SOURCE_ROOT_EVIDENCE,
    INVALID_SOURCE_ROOT,
    PRODUCER_MODEL_UNAVAILABLE,
    SOURCE_ROOT_EVIDENCE_MISSING,
}

/** Exact normalized source-root evidence retained in the imported IntelliJ project model. */
data class GradleSourceRootProducerEvidence(
    val sourceRoot: File,
    val provenance: GradleSourceRootProducerProvenance,
) : Serializable

/** Closed imported-model result for one Gradle IDEA module's producer evidence. */
internal sealed interface GradleSourceRootProducerImport : Serializable {
    data class Captured internal constructor(
        val entries: List<GradleSourceRootProducerEvidence>,
    ) : GradleSourceRootProducerImport

    data class Rejected(
        val failure: GradleSourceRootProducerCaptureFailure,
    ) : GradleSourceRootProducerImport
}

/** Closed IntelliJ-side availability of the fetched Gradle producer model. */
internal sealed interface GradleSourceRootProducerModelRead {
    data class Available(
        val model: GradleSourceRootProducerModel,
    ) : GradleSourceRootProducerModelRead

    data object Unavailable : GradleSourceRootProducerModelRead
}

private sealed interface GradleSourceRootProducerEvidenceCapture {
    data class Captured(
        val evidence: GradleSourceRootProducerEvidence,
    ) : GradleSourceRootProducerEvidenceCapture

    data object Rejected : GradleSourceRootProducerEvidenceCapture
}

internal val GRADLE_SOURCE_ROOT_PRODUCER_IMPORT_KEY: Key<GradleSourceRootProducerImport> =
    Key.create(
        GradleSourceRootProducerImport::class.java,
        ProjectKeys.CONTENT_ROOT.processingWeight + 1,
    )

/**
 * Captures Gradle Tooling API producer evidence before IntelliJ content-root projection.
 *
 * The extension is stateless. Its typed child node survives with the imported external-project
 * structure and is consumed only by Kast's installed Gradle-model capture boundary.
 */
class KastGradleSourceRootProvenanceResolver : AbstractProjectResolverExtension() {
    override fun getExtraProjectModelClasses(): Set<Class<*>> =
        setOf(GradleSourceRootProducerModel::class.java)

    override fun getToolingExtensionsClasses(): Set<Class<*>> =
        setOf(GradleSourceRootProducerModelBuilder::class.java)

    override fun populateModuleContentRoots(
        gradleModule: IdeaModule,
        ideModule: DataNode<ModuleData>,
    ) {
        super.populateModuleContentRoots(gradleModule, ideModule)
        val producerModel = resolverCtx.getProjectModel(
            gradleModule,
            GradleSourceRootProducerModel::class.java,
        )?.let(GradleSourceRootProducerModelRead::Available)
                        ?: GradleSourceRootProducerModelRead.Unavailable
        ideModule.createChild(
            GRADLE_SOURCE_ROOT_PRODUCER_IMPORT_KEY,
            captureGradleSourceRootProducerImport(gradleModule, producerModel),
        )
    }
}

/**
 * Proof transition: `(IdeaModule, GradleSourceRootProducerModelRead) ->
 * GradleSourceRootProducerImport`.
 *
 * [GradleSourceRootProducerImport.Captured] establishes an exact absolute normalized directory
 * plus Gradle task-output or [IdeaSourceDirectory.isGenerated] producer authority for every
 * production and test source directory in the complete producer model. Producer-only entries are
 * retained for source sets that IntelliJ projects outside the standard IDEA model. The closed
 * expected failure is [GradleSourceRootProducerCaptureFailure]. Raw Tooling API objects and [File]
 * extraction are permitted only at this resolver-extension boundary.
 */
internal fun captureGradleSourceRootProducerImport(
    module: IdeaModule,
    producerModel: GradleSourceRootProducerModelRead,
): GradleSourceRootProducerImport {
    val producerEntries = when (producerModel) {
        is GradleSourceRootProducerModelRead.Available -> producerModel.model.entries.map { entry ->
            GradleSourceRootProducerEvidence(entry.sourceRoot, entry.provenance)
        }
        GradleSourceRootProducerModelRead.Unavailable ->
            return GradleSourceRootProducerImport.Rejected(
                GradleSourceRootProducerCaptureFailure.PRODUCER_MODEL_UNAVAILABLE,
            )
    }
    val ideaEntries = mutableListOf<GradleSourceRootProducerEvidence>()
    for (contentRoot in module.contentRoots) {
        for (sourceDirectory in contentRoot.sourceDirectories) {
            when (val capture = sourceDirectory.producerEvidence()) {
                is GradleSourceRootProducerEvidenceCapture.Captured ->
                    ideaEntries += capture.evidence
                GradleSourceRootProducerEvidenceCapture.Rejected ->
                    return GradleSourceRootProducerImport.Rejected(
                        GradleSourceRootProducerCaptureFailure.INVALID_SOURCE_ROOT,
                    )
            }
        }
        for (sourceDirectory in contentRoot.testDirectories) {
            when (val capture = sourceDirectory.producerEvidence()) {
                is GradleSourceRootProducerEvidenceCapture.Captured ->
                    ideaEntries += capture.evidence
                GradleSourceRootProducerEvidenceCapture.Rejected ->
                    return GradleSourceRootProducerImport.Rejected(
                        GradleSourceRootProducerCaptureFailure.INVALID_SOURCE_ROOT,
                    )
            }
        }
    }
    return combineGradleSourceRootProducerEvidence(ideaEntries, producerEntries)
}

/**
 * Proof transition: `(Iterable<GradleSourceRootProducerEvidence>,
 * Iterable<GradleSourceRootProducerEvidence>) -> GradleSourceRootProducerImport`.
 *
 * Captured establishes one normalized exact-path provenance for each IDEA source directory after
 * combining the standard generated flag with the complete Gradle producer model. Producer entries
 * absent from the standard IDEA model remain available for source roots that IntelliJ projects
 * later, such as Gradle test fixtures. Conflicting exact classifications, invalid paths, and IDEA
 * roots missing from the producer model are the closed [GradleSourceRootProducerCaptureFailure]
 * outcomes. Raw [File] normalization is permitted only at this imported-model boundary.
 */
internal fun combineGradleSourceRootProducerEvidence(
    ideaEntries: Iterable<GradleSourceRootProducerEvidence>,
    producerEntries: Iterable<GradleSourceRootProducerEvidence>,
): GradleSourceRootProducerImport {
    val normalizedIdea = when (val capture = ideaEntries.normalizedEvidence()) {
        is GradleSourceRootEvidenceNormalization.Normalized -> capture.entries
        GradleSourceRootEvidenceNormalization.Rejected ->
            return GradleSourceRootProducerImport.Rejected(
                GradleSourceRootProducerCaptureFailure.INVALID_SOURCE_ROOT,
            )
    }
    val normalizedProducers = when (val capture = producerEntries.normalizedEvidence()) {
        is GradleSourceRootEvidenceNormalization.Normalized -> capture.entries
        GradleSourceRootEvidenceNormalization.Rejected ->
            return GradleSourceRootProducerImport.Rejected(
                GradleSourceRootProducerCaptureFailure.INVALID_SOURCE_ROOT,
            )
    }
    val ideaByRoot = normalizedIdea.groupBy(GradleSourceRootProducerEvidence::sourceRoot)
    val producerByRoot = normalizedProducers.groupBy(GradleSourceRootProducerEvidence::sourceRoot)
    if (
        ideaByRoot.values.any { exact -> exact.map { it.provenance }.distinct().size > 1 } ||
        producerByRoot.values.any { exact -> exact.map { it.provenance }.distinct().size > 1 }
    ) {
        return GradleSourceRootProducerImport.Rejected(
            GradleSourceRootProducerCaptureFailure.CONFLICTING_SOURCE_ROOT_EVIDENCE,
        )
    }
    if (ideaByRoot.keys.any { sourceRoot -> sourceRoot !in producerByRoot }) {
        return GradleSourceRootProducerImport.Rejected(
            GradleSourceRootProducerCaptureFailure.SOURCE_ROOT_EVIDENCE_MISSING,
        )
    }
    val entries = producerByRoot.map { (sourceRoot, exactProducerEntries) ->
        val producer = exactProducerEntries.single()
        val exactIdeaEntries = ideaByRoot[sourceRoot].orEmpty()
        GradleSourceRootProducerEvidence(
            sourceRoot = sourceRoot,
            provenance = if (
                producer.provenance == GradleSourceRootProducerProvenance.GENERATED ||
                exactIdeaEntries.any { idea ->
                    idea.provenance == GradleSourceRootProducerProvenance.GENERATED
                }
            ) {
                GradleSourceRootProducerProvenance.GENERATED
            } else {
                GradleSourceRootProducerProvenance.AUTHORED
            },
        )
    }
        .sortedWith(compareBy({ it.sourceRoot.path }, { it.provenance.name }))
    return GradleSourceRootProducerImport.Captured(entries)
}

private sealed interface GradleSourceRootEvidenceNormalization {
    data class Normalized(
        val entries: List<GradleSourceRootProducerEvidence>,
    ) : GradleSourceRootEvidenceNormalization

    data object Rejected : GradleSourceRootEvidenceNormalization
}

/**
 * Proof transition: `Iterable<GradleSourceRootProducerEvidence> ->
 * GradleSourceRootEvidenceNormalization`.
 *
 * Normalized establishes absolute lexically normalized evidence paths while retaining every
 * observed classification. Rejected is the closed relative-path failure. Raw [File] extraction
 * remains inside [combineGradleSourceRootProducerEvidence].
 */
private fun Iterable<GradleSourceRootProducerEvidence>.normalizedEvidence():
    GradleSourceRootEvidenceNormalization {
    val normalized = mutableListOf<GradleSourceRootProducerEvidence>()
    for (evidence in this) {
        val path = evidence.sourceRoot.toPath()
        if (!path.isAbsolute) return GradleSourceRootEvidenceNormalization.Rejected
        normalized += evidence.copy(sourceRoot = path.normalize().toFile())
    }
    return GradleSourceRootEvidenceNormalization.Normalized(normalized.distinct())
}

/**
 * Proof transition: `IdeaSourceDirectory -> GradleSourceRootProducerEvidenceCapture`.
 *
 * Captured establishes an absolute normalized source-root path carrying the standard Tooling API
 * generated flag. Rejected is the closed invalid-path outcome. Raw [File] extraction is permitted
 * only inside [captureGradleSourceRootProducerImport].
 */
private fun IdeaSourceDirectory.producerEvidence(): GradleSourceRootProducerEvidenceCapture {
    val path = directory.toPath()
    if (!path.isAbsolute || path.normalize() != path) {
        return GradleSourceRootProducerEvidenceCapture.Rejected
    }
    return GradleSourceRootProducerEvidenceCapture.Captured(
        GradleSourceRootProducerEvidence(
            sourceRoot = path.toFile(),
            provenance = if (isGenerated) {
                GradleSourceRootProducerProvenance.GENERATED
            } else {
                GradleSourceRootProducerProvenance.AUTHORED
            },
        ),
    )
}
