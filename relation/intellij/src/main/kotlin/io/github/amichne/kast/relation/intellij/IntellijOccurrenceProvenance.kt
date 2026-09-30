package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.relation.contract.RelationProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijGeneratedSourceState
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectFileClassification
import io.github.amichne.kast.workspace.intellij.read.IntellijProjectFileIndexClassifier

internal fun com.intellij.openapi.vfs.VirtualFile.relationOccurrenceProvenance(
    project: Project,
    limits: ReadLimits,
): OccurrenceProvenance =
    when (val classification = IntellijProjectFileIndexClassifier.classify(project, this, limits)) {
        is IntellijProjectFileClassification.Source ->
            when (classification.generated) {
                IntellijGeneratedSourceState.AUTHORED ->
                    OccurrenceProvenance.Found(RelationProvenance.K2_AUTHORED_SOURCE)
                IntellijGeneratedSourceState.GENERATED ->
                    OccurrenceProvenance.Found(RelationProvenance.K2_GENERATED_SOURCE)
            }
        is IntellijProjectFileClassification.Library ->
            OccurrenceProvenance.Found(RelationProvenance.K2_PROJECT_LIBRARY)
        is IntellijProjectFileClassification.NotSource,
        is IntellijProjectFileClassification.Rejected -> OccurrenceProvenance.Unsupported
    }
