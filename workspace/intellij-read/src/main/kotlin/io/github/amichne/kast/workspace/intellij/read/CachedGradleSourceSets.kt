package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.progress.ProgressManager
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import org.jetbrains.kotlin.idea.gradle.configuration.KotlinSourceSetData
import org.jetbrains.kotlin.idea.gradle.configuration.KotlinSourceSetInfo
import org.jetbrains.kotlin.idea.projectModel.KotlinCompilation
import org.jetbrains.kotlin.idea.projectModel.KotlinSourceSet
import org.jetbrains.plugins.gradle.model.DefaultExternalSourceDirectorySet
import org.jetbrains.plugins.gradle.model.DefaultExternalSourceSet
import org.jetbrains.plugins.gradle.model.ExternalSourceSet
import org.jetbrains.plugins.gradle.model.data.GradleSourceSetData

/** Read the imported Kotlin model before the legacy JVM projection; neither path resolves or refreshes Gradle. */
internal object CachedGradleSourceSets {
    fun capture(
        admitted: AdmittedSelectedBuildModule,
        node: DataNode<out ModuleData>?,
        legacy: () -> Map<String, ExternalSourceSet>,
        observation: IntellijReadObservation,
        limits: ReadLimits,
    ): Refinement<Map<String, ExternalSourceSet>, NamedGradleSourceScopeFailure> {
        val kotlin = node?.let { ExternalSystemApiUtil.getChildren(it, KotlinSourceSetData.KEY) }.orEmpty()
        if (node == null || kotlin.isEmpty()) return captureLegacy(legacy, observation)
        val info = kotlin.singleOrNull()?.data?.sourceSetInfo ?: return incoherent(observation)
        when (val ownership = validateOwnership(admitted, node, info, observation)) {
            is Refinement.Rejected -> return ownership
            is Refinement.Refined -> Unit
        }
        // Compilation names are main/test, not exact Gradle source-set names (e.g. jvmMain).
        // allSourceSets includes inherited roots, which this module does not own.
        val sources =
            when (val component = info.kotlinComponent) {
                is KotlinSourceSet -> listOf(component)
                is KotlinCompilation -> component.declaredSourceSets
            }
        return projectSources(sources, info.isTestModule, observation, limits)
    }

    private fun captureLegacy(
        legacy: () -> Map<String, ExternalSourceSet>,
        observation: IntellijReadObservation,
    ): Refinement.Refined<Map<String, ExternalSourceSet>> {
        val sources = legacy()
        observation.count(
            if (sources.isEmpty()) IntellijReadCounter.GRADLE_SOURCE_SETS_UNAVAILABLE
            else IntellijReadCounter.LEGACY_GRADLE_SOURCE_SETS
        )
        return Refinement.Refined(sources)
    }

    private fun validateOwnership(
        admitted: AdmittedSelectedBuildModule,
        node: DataNode<out ModuleData>,
        info: KotlinSourceSetInfo,
        observation: IntellijReadObservation,
    ): Refinement<Unit, NamedGradleSourceScopeFailure> {
        val parent = node.parent?.takeIf { it.key == ProjectKeys.MODULE } ?: return incoherent(observation)
        val owner = parent.data as? ModuleData ?: return incoherent(observation)
        if (
            node.key != GradleSourceSetData.KEY ||
                node.data.id != ExternalSystemApiUtil.getExternalProjectId(admitted.module) ||
                info.moduleId != node.data.id
        )
            return incoherent(observation)
        if (
            info.gradleModuleId != owner.id ||
                node.data.linkedExternalProjectPath != admitted.owner.project.projectDir.path ||
                owner.linkedExternalProjectPath != admitted.owner.project.projectDir.path
        )
            return incoherent(observation)
        if (info.isTestModule != info.kotlinComponent.isTestComponent) return incoherent(observation)
        return Refinement.Refined(Unit)
    }

    private fun projectSources(
        sources: Collection<KotlinSourceSet>,
        test: Boolean,
        observation: IntellijReadObservation,
        limits: ReadLimits,
    ): Refinement<Map<String, ExternalSourceSet>, NamedGradleSourceScopeFailure> {
        val capacity = limits[ReadLimitParameter.MODEL_SOURCE_ROOTS_PER_MODULE].value
        if (sources.size > capacity) return Refinement.Rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
        if (sources.isEmpty() || sources.map { it.name }.distinct().size != sources.size) return incoherent(observation)
        val captured = LinkedHashMap<String, ExternalSourceSet>()
        var rootCount = 0L
        for (source in sources) {
            ProgressManager.checkCanceled()
            if (source.isTestComponent != test) return incoherent(observation)
            if (maxOf(source.sourceDirs.size, source.generatedKotlinDirs.size, source.resourceDirs.size) > capacity)
                return Refinement.Rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
            rootCount +=
                source.sourceDirs.size.toLong() +
                    source.generatedKotlinDirs.count { it !in source.sourceDirs } +
                    source.resourceDirs.size
            if (rootCount > capacity) return Refinement.Rejected(NamedGradleSourceScopeFailure.CAPTURE_LIMIT)
            captured[source.name] = projectSource(source)
        }
        observation.count(IntellijReadCounter.KOTLIN_GRADLE_SOURCE_SETS, amount = captured.size)
        return Refinement.Refined(captured)
    }

    private fun projectSource(source: KotlinSourceSet): ExternalSourceSet =
        DefaultExternalSourceSet().apply {
            name = source.name
            fun roots(type: ExternalSystemSourceType, paths: Set<java.io.File>) {
                addSource(type, DefaultExternalSourceDirectorySet().apply { srcDirs = paths })
            }
            roots(
                if (source.isTestComponent) ExternalSystemSourceType.TEST else ExternalSystemSourceType.SOURCE,
                source.sourceDirs - source.generatedKotlinDirs,
            )
            roots(
                if (source.isTestComponent) ExternalSystemSourceType.TEST_GENERATED
                else ExternalSystemSourceType.SOURCE_GENERATED,
                source.generatedKotlinDirs,
            )
            roots(
                if (source.isTestComponent) ExternalSystemSourceType.TEST_RESOURCE
                else ExternalSystemSourceType.RESOURCE,
                source.resourceDirs,
            )
        }

    private fun incoherent(observation: IntellijReadObservation): Refinement.Rejected<NamedGradleSourceScopeFailure> {
        observation.count(IntellijReadCounter.KOTLIN_GRADLE_SOURCE_SETS_INCOHERENT)
        return Refinement.Rejected(NamedGradleSourceScopeFailure.IDE_ROOT_INCOHERENT)
    }
}
