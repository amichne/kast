package support.architecture.projection

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import support.architecture.ModuleRoleConventionRequirement
import support.architecture.ValidatedArchitecturePolicy

internal val architectureProjectionJson = Json {
    classDiscriminator = "kind"
}

@Serializable
internal data class ArchitectureProjectionDocument(
    val schemaVersion: Int,
    val modules: List<ArchitectureModuleDocument>,
)

@Serializable
internal data class ArchitectureModuleDocument(
    val id: String,
    val projectPath: String,
    val lifecycle: String,
    val role: String,
    val cost: String,
    val roleConvention: ModuleRoleConventionDocument,
    val allowedProjectDependencies: List<String>,
    val allowedEffects: List<String>,
)

@Serializable
internal sealed interface ModuleRoleConventionDocument {
    @Serializable
    @SerialName("UNMARKED_LEGACY")
    data object UnmarkedLegacy : ModuleRoleConventionDocument

    @Serializable
    @SerialName("REQUIRED")
    data class Required(val pluginId: String) : ModuleRoleConventionDocument
}

object ArchitectureProjection {
    fun render(policy: ValidatedArchitecturePolicy): String {
        val document = ArchitectureProjectionDocument(
            schemaVersion = 1,
            modules = policy.moduleOrder.map { id ->
                val module = policy.modules.getValue(id)
                ArchitectureModuleDocument(
                    id = id.name,
                    projectPath = id.projectPath,
                    lifecycle = module.lifecycle.name,
                    role = module.role.name,
                    cost = module.cost.name,
                    roleConvention = module.conventionRequirement.toDocument(),
                    allowedProjectDependencies = module.allowedProjectDependencies
                        .map { it.projectPath }
                        .sorted(),
                    allowedEffects = module.allowedEffects.map(Enum<*>::name).sorted(),
                )
            },
        )
        return architectureProjectionJson.encodeToString(
            ArchitectureProjectionDocument.serializer(),
            document,
        ) + "\n"
    }
}

private fun ModuleRoleConventionRequirement.toDocument(): ModuleRoleConventionDocument = when (this) {
    ModuleRoleConventionRequirement.UnmarkedLegacy -> ModuleRoleConventionDocument.UnmarkedLegacy
    is ModuleRoleConventionRequirement.Required ->
        ModuleRoleConventionDocument.Required(convention.pluginId)
}
