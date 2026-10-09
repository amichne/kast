package io.github.amichne.kast.topology.intellij

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import io.github.amichne.kast.kernel.ReadLimitParameter
import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.topology.contract.SemanticModuleDependencies
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceModuleIdentity
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.call
import org.jetbrains.kotlin.idea.facet.KotlinFacet

internal class SemanticNativeModuleGraph(
    val selected: Map<WorkspaceModuleIdentity, Module>,
    val graph: SemanticModuleDependencies,
)

internal class SemanticNativeModuleGraphCapture(
    private val limits: ReadLimits,
    private val budget: DependencyCaptureBudget,
) {
    fun capture(project: Project, model: WorkspaceSearchScopeModel): SemanticCapture<SemanticNativeModuleGraph> {
        val selected =
            when (val modules = modules(project, model)) {
                is Refinement.Refined -> modules.value
                is Refinement.Rejected -> return modules
            }
        val identities = selected.keys.associateBy { it.value }
        val edges = linkedMapOf<WorkspaceModuleIdentity, Set<WorkspaceModuleIdentity>>()
        for ((identity, module) in selected) when (val captured = dependencies(module, identities)) {
            is Refinement.Refined -> edges[identity] = captured.value
            is Refinement.Rejected -> return captured
        }
        return when (val graph = SemanticModuleDependencies.fromCompiler(model, edges)) {
            is Refinement.Refined -> Refinement.Refined(SemanticNativeModuleGraph(selected, graph.value))
            is Refinement.Rejected -> captureRejected(SemanticDependencyCaptureFailure.DEPENDENCY_GRAPH_REJECTED)
        }
    }

    private fun modules(
        project: Project,
        model: WorkspaceSearchScopeModel,
    ): SemanticCapture<Map<WorkspaceModuleIdentity, Module>> {
        val modules =
            budget.observation.call(IntellijReadCall.MODULE_INVENTORY) { ModuleManager.getInstance(project).modules }
        if (modules.size > limits[ReadLimitParameter.MODEL_MODULES].value)
            return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        val byName = modules.groupBy(Module::getName)
        val selected = linkedMapOf<WorkspaceModuleIdentity, Module>()
        for (identity in model.sourceRoots.mapTo(linkedSetOf(), ModelOwnedSourceRoot::module)) {
            val matches =
                byName[identity.value] ?: return captureRejected(SemanticDependencyCaptureFailure.MODULE_UNAVAILABLE)
            if (matches.size != 1 || matches.single().isDisposed)
                return captureRejected(SemanticDependencyCaptureFailure.MODULE_UNAVAILABLE)
            selected[identity] = matches.single()
        }
        return Refinement.Refined(selected)
    }

    private fun dependencies(
        module: Module,
        identities: Map<String, WorkspaceModuleIdentity>,
    ): SemanticCapture<Set<WorkspaceModuleIdentity>> {
        when (val spent = budget.step()) {
            is Refinement.Rejected -> return spent
            is Refinement.Refined -> Unit
        }
        val names =
            budget.observation
                .call(IntellijReadCall.MODULE_DEPENDENCIES) {
                    ModuleRootManager.getInstance(module).dependencies
                }
                .mapTo(linkedSetOf(), Module::getName)
        budget.observation
            .call(IntellijReadCall.KOTLIN_FACET) { KotlinFacet.get(module) }
            ?.configuration
            ?.settings
            ?.let { settings ->
                names += settings.additionalVisibleModuleNames
                names += settings.dependsOnModuleNames
                names += settings.implementedModuleNames
            }
        if (names.size > limits[ReadLimitParameter.MODEL_MODULES].value)
            return captureRejected(SemanticDependencyCaptureFailure.CAPACITY_EXCEEDED)
        val dependencies = linkedSetOf<WorkspaceModuleIdentity>()
        for (name in names) dependencies +=
            identities[name] ?: return captureRejected(SemanticDependencyCaptureFailure.DEPENDENCY_MODULE_UNMODELED)
        return Refinement.Refined(dependencies)
    }
}
