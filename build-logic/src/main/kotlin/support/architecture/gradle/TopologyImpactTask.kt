package support.architecture.gradle

import java.nio.file.Files
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import support.architecture.ArchitectureObservationParser
import support.architecture.ArchitectureObservationValidation
import support.architecture.ArchitecturePolicyValidation
import support.architecture.ModuleImpact
import support.architecture.ModuleImpactAdmission

/** An explicit build report. This task accepts no source, bytecode, compiler, or dependency-resolution capability. */
@DisableCachingByDefault(because = "An explicit local report is inexpensive and must report rejected invocations.")
abstract class TopologyImpactTask : ModuleBuildFactsConsumer() {
    @get:Input abstract val selectedModules: ListProperty<String>
    @get:Input abstract val enabledSetting: Property<String>
    @get:OutputFile abstract val reportFile: RegularFileProperty

    init {
        selectedModules.convention(emptyList())
        enabledSetting.convention("true")
    }

    @TaskAction
    fun report() {
        val document = compute()
        val target = reportFile.get().asFile.toPath()
        Files.createDirectories(target.parent)
        Files.writeString(
            target, topologyImpactJson.encodeToString(TopologyImpactDocument.serializer(), document) + "\n",
        )
        logger.lifecycle("Topology impact report: $target")
        when (document) {
            is TopologyImpactDocument.Computed -> logger.lifecycle(
                "Declared dependency impact: ${document.selectedModules.size} selected, " +
                    "${document.affectedModules.size} consumers.",
            )
            is TopologyImpactDocument.Disabled -> logger.lifecycle("Topology impact is disabled.")
            is TopologyImpactDocument.Rejected -> throw GradleException(
                "Topology impact rejected: ${document.code}. See $target",
            )
        }
    }

    private fun compute(): TopologyImpactDocument {
        when (enabledSetting.get()) {
            "false" -> return TopologyImpactDocument.Disabled()
            "true" -> Unit
            else -> return TopologyImpactDocument.Rejected(
                TopologyImpactRejection.INVALID_SWITCH, listOf("kastTopologyEnabled"),
            )
        }
        val facts = moduleFacts()
        val policy = when (val result = canonicalArchitecturePolicy()) {
            is ArchitecturePolicyValidation.Valid -> result.architecture
            is ArchitecturePolicyValidation.Invalid -> return TopologyImpactDocument.Rejected(
                TopologyImpactRejection.INVALID_POLICY, result.failures.map { it.toString() },
            )
        }
        val graph = when (val result = ArchitectureObservationParser.parse(
            policy, facts.map { it.projectPath }, facts.flatMap { it.productionDependencies },
        )) {
            is ArchitectureObservationValidation.Valid -> result.graph
            is ArchitectureObservationValidation.Invalid -> return TopologyImpactDocument.Rejected(
                TopologyImpactRejection.INVALID_OBSERVATION, result.failures.map { it.toString() },
            )
        }
        return when (val admitted = ModuleImpact.admit(graph, selectedModules.get())) {
            is ModuleImpactAdmission.Accepted -> impactDocument(admitted.impact)
            is ModuleImpactAdmission.Rejected -> impactRejection(admitted.failure)
        }
    }
}
