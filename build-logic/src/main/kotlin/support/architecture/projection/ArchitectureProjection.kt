package support.architecture.projection

import support.architecture.EffectObservation
import support.architecture.LegacyAllowance
import support.architecture.LegacyViolationKey
import support.architecture.MutationDeliveryOwner
import support.architecture.ModuleRoleConventionRequirement
import support.architecture.ValidatedArchitecturePolicy
import support.architecture.ValidatedLegacyImplementationBridge
import support.architecture.ValidatedLegacyMigrationEdge
import support.architecture.process.MutationRuntimeAdmission

object ArchitectureProjection {
    fun render(policy: ValidatedArchitecturePolicy): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 8,\n")
        append("  \"policyAuthority\": \"KOTLIN\",\n")
        append("  \"policySource\": \"build-logic/src/main/kotlin/support/architecture\",\n")
        append("  \"enforcementScope\": \"REPOSITORY_WIDE\",\n")
        append("  \"workflowScope\": \"CLEAN_SLATE_DELIVERY\",\n")
        append("  \"targetModules\": [\n")
        policy.targetModuleOrder.forEachIndexed { index, id ->
            val module = policy.targetModules.getValue(id)
            append("    {\n")
            append("      \"id\": ").appendQuoted(id.name).append(",\n")
            append("      \"projectPath\": ").appendQuoted(id.projectPath).append(",\n")
            append("      \"lifecycle\": ").appendQuoted(module.lifecycle.name).append(",\n")
            append("      \"role\": ").appendQuoted(module.role.name).append(",\n")
            append("      \"cost\": ").appendQuoted(module.cost.name).append(",\n")
            append("      \"roleConvention\": ")
                .appendConventionRequirement(module.conventionRequirement)
                .append(",\n")
            append("      \"allowedProjectDependencies\": ")
                .appendStringArray(module.allowedProjectDependencies.map { it.projectPath }.sorted())
                .append(",\n")
            append("      \"allowedEffects\": ")
                .appendStringArray(module.allowedEffects.map(Enum<*>::name).sorted())
                .append("\n")
            append("    }").appendComma(index, policy.targetModuleOrder.lastIndex).append("\n")
        }
        append("  ],\n")
        append("  \"modules\": [\n")
        policy.moduleOrder.forEachIndexed { index, id ->
            val module = policy.modules.getValue(id)
            append("    {\n")
            append("      \"id\": ").appendQuoted(id.name).append(",\n")
            append("      \"projectPath\": ").appendQuoted(id.projectPath).append(",\n")
            append("      \"lifecycle\": ").appendQuoted(module.lifecycle.name).append(",\n")
            append("      \"role\": ").appendQuoted(module.role.name).append(",\n")
            append("      \"cost\": ").appendQuoted(module.cost.name).append(",\n")
            append("      \"roleConvention\": ")
                .appendConventionRequirement(module.conventionRequirement)
                .append(",\n")
            append("      \"allowedProjectDependencies\": ")
                .appendStringArray(module.allowedProjectDependencies.map { it.projectPath }.sorted())
                .append(",\n")
            append("      \"allowedEffects\": ")
                .appendStringArray(module.allowedEffects.map(Enum<*>::name).sorted())
                .append("\n")
            append("    }").appendComma(index, policy.moduleOrder.lastIndex).append("\n")
        }
        append("  ],\n")
        append("  \"mutationRuntimeProcesses\": [\n")
        policy.mutationRuntimeProcessOrder.forEachIndexed { index, id ->
            val process = policy.mutationRuntimeProcesses.getValue(id)
            append("    {\n")
            append("      \"id\": ").appendQuoted(id.name).append(",\n")
            append("      \"name\": ").appendQuoted(process.name).append(",\n")
            append("      \"orderingDependencies\": ")
                .appendStringArray(process.admission.orderingDependencies.map(Enum<*>::name).sorted())
                .append(",\n")
            append("      \"admission\": ").appendAdmission(process.admission).append(",\n")
            append("      \"owners\": ")
                .appendStringArray(process.owners.map { it.projectPath }.sorted())
                .append(",\n")
            append("      \"effects\": ")
                .appendStringArray(process.effects.map(Enum<*>::name).sorted())
                .append(",\n")
            append("      \"cost\": ").appendQuoted(process.cost).append("\n")
            append("    }").appendComma(index, policy.mutationRuntimeProcessOrder.lastIndex).append("\n")
        }
        append("  ],\n")
        append("  \"mutationDeliveryTasks\": [\n")
        policy.mutationDeliveryOrder.forEachIndexed { index, id ->
            val task = policy.mutationDeliveryTasks.getValue(id)
            append("    {\n")
            append("      \"id\": ").appendQuoted(id.name).append(",\n")
            append("      \"phase\": ").appendQuoted(task.phase.name).append(",\n")
            append("      \"name\": ").appendQuoted(task.name).append(",\n")
            append("      \"lifecycle\": ").appendQuoted(task.lifecycle.name).append(",\n")
            append("      \"dependsOn\": ")
                .appendStringArray(task.dependsOn.map(Enum<*>::name).sorted())
                .append(",\n")
            append("      \"owner\": ").appendOwner(task.owner).append("\n")
            append("    }").appendComma(index, policy.mutationDeliveryOrder.lastIndex).append("\n")
        }
        append("  ],\n")
        append("  \"legacyMigrationEdges\": [\n")
        val migrations = policy.legacyMigrationEdges.values.sortedWith(
            compareBy(
                { migration -> migration.dependency.consumer.name },
                { migration -> migration.dependency.dependency.name },
            ),
        )
        migrations.forEachIndexed { index, migration ->
            append("    ").appendMigration(migration)
                .appendComma(index, migrations.lastIndex)
                .append("\n")
        }
        append("  ],\n")
        append("  \"legacyImplementationBridges\": [\n")
        val bridges = policy.legacyImplementationBridges.values.sortedWith(
            compareBy(
                { bridge -> bridge.dependency.consumer.name },
                { bridge -> bridge.dependency.dependency.name },
            ),
        )
        bridges.forEachIndexed { index, bridge ->
            append("    ").appendImplementationBridge(bridge)
                .appendComma(index, bridges.lastIndex)
                .append("\n")
        }
        append("  ],\n")
        append("  \"legacyAllowances\": [\n")
        val allowances = policy.legacyAllowances.sortedBy(::allowanceSortKey)
        allowances.forEachIndexed { index, allowance ->
            append("    ").appendAllowance(allowance)
                .appendComma(index, allowances.lastIndex)
                .append("\n")
        }
        append("  ]\n")
        append("}\n")
    }
}

private fun StringBuilder.appendImplementationBridge(
    bridge: ValidatedLegacyImplementationBridge.Active,
): StringBuilder {
    append("{\"consumer\": ").appendQuoted(bridge.dependency.consumer.projectPath)
        .append(", \"dependency\": ").appendQuoted(bridge.dependency.dependency.projectPath)
        .append(", \"lifecycle\": \"ACTIVE\"")
        .append(", \"retirementTask\": ").appendQuoted(bridge.retirementTask.name)
    return append("}")
}

private fun StringBuilder.appendMigration(
    migration: ValidatedLegacyMigrationEdge,
): StringBuilder {
    append("{\"consumer\": ").appendQuoted(migration.dependency.consumer.projectPath)
        .append(", \"dependency\": ").appendQuoted(migration.dependency.dependency.projectPath)
        .append(", \"lifecycle\": ").appendQuoted(migration.lifecycleName())
        .append(", \"retirementTask\": ").appendQuoted(migration.retirementTask.name)
    return append("}")
}

private fun ValidatedLegacyMigrationEdge.lifecycleName(): String = when (this) {
    is ValidatedLegacyMigrationEdge.Planned -> "PLANNED"
    is ValidatedLegacyMigrationEdge.Active -> "ACTIVE"
}

private fun StringBuilder.appendAdmission(admission: MutationRuntimeAdmission): StringBuilder = when (admission) {
    MutationRuntimeAdmission.Entry -> append("{\"kind\": \"ENTRY\"}")
    is MutationRuntimeAdmission.After ->
        append("{\"kind\": \"AFTER\", \"predecessor\": ")
            .appendQuoted(admission.predecessor.name)
            .append("}")
    is MutationRuntimeAdmission.ApplyLane ->
        append("{\"kind\": \"APPLY_LANE\", \"lane\": ")
            .appendQuoted(admission.lane.name)
            .append(", \"process\": ")
            .appendQuoted(admission.lane.processId.name)
            .append(", \"predecessor\": ")
            .appendQuoted(admission.predecessor.name)
            .append("}")
    MutationRuntimeAdmission.SelectedApplyLaneJoin -> {
        val lanes = MutationRuntimeAdmission.SelectedApplyLaneJoin.lanes.sortedBy(Enum<*>::name)
        append("{\"kind\": \"SELECTED_APPLY_LANE_JOIN\", \"lanes\": [")
        lanes.forEachIndexed { index, lane ->
            append("{\"lane\": ").appendQuoted(lane.name)
                .append(", \"process\": ").appendQuoted(lane.processId.name)
                .append("}")
                .appendComma(index, lanes.lastIndex)
        }
        append("]}")
    }
    MutationRuntimeAdmission.AllApplyLanesJoin ->
        append("{\"kind\": \"ALL_APPLY_LANES_JOIN\"}")
    is MutationRuntimeAdmission.RecoveryInterruptAfterPreparation -> {
        val failurePoints = admission.failurePoints.sortedBy(Enum<*>::name)
        val terminalOutcomes = admission.terminalOutcomes.sortedBy(Enum<*>::name)
        append("{\"kind\": \"RECOVERY_INTERRUPT_AFTER_PREPARATION\", \"preparedBy\": ")
            .appendQuoted(admission.preparedBy.name)
            .append(", \"failurePoints\": ")
            .appendStringArray(failurePoints.map(Enum<*>::name))
            .append(", \"terminalOutcomes\": ")
            .appendStringArray(terminalOutcomes.map(Enum<*>::name))
            .append("}")
    }
}

private fun StringBuilder.appendOwner(owner: MutationDeliveryOwner): StringBuilder = when (owner) {
    MutationDeliveryOwner.BuildLogic -> append("{\"kind\": \"BUILD_LOGIC\"}")
    MutationDeliveryOwner.EndToEndCorpus -> append("{\"kind\": \"END_TO_END_CORPUS\"}")
    is MutationDeliveryOwner.Modules -> {
        append("{\"kind\": \"MODULES\", \"modules\": ")
            .appendStringArray(owner.ids.map { it.projectPath }.sorted())
            .append("}")
    }
}

private fun StringBuilder.appendAllowance(allowance: LegacyAllowance): StringBuilder {
    append("{\"retirementTask\": ").appendQuoted(allowance.retirementTask.name)
    when (val violation = allowance.violation) {
        is LegacyViolationKey.UnapprovedProjectDependency -> {
            append(", \"kind\": \"UNAPPROVED_PROJECT_DEPENDENCY\", \"consumer\": ")
                .appendQuoted(violation.dependency.consumer.projectPath)
                .append(", \"dependency\": ")
                .appendQuoted(violation.dependency.dependency.projectPath)
        }
        is LegacyViolationKey.ForbiddenEffectUse -> {
            append(", \"kind\": \"FORBIDDEN_EFFECT\", \"effect\": ")
                .appendQuoted(violation.observation.effect.name)
                .append(", \"module\": ")
                .appendQuoted(violation.observation.module.projectPath)
                .append(", \"caller\": ")
                .appendMember(violation.observation)
        }
    }
    return append("}")
}

private fun StringBuilder.appendMember(observation: EffectObservation): StringBuilder =
    append("{\"owner\": ").appendQuoted(observation.caller.owner.internalName)
        .append(", \"name\": ").appendQuoted(observation.caller.name.value)
        .append(", \"descriptor\": ").appendQuoted(observation.caller.descriptor.value)
        .append(", \"targetOwner\": ").appendQuoted(observation.target.owner.internalName)
        .append(", \"targetName\": ").appendQuoted(observation.target.name.value)
        .append(", \"targetDescriptor\": ").appendQuoted(observation.target.descriptor.value)
        .append("}")

private fun allowanceSortKey(allowance: LegacyAllowance): String = when (val violation = allowance.violation) {
    is LegacyViolationKey.UnapprovedProjectDependency ->
        "dependency|${violation.dependency.consumer.name}|${violation.dependency.dependency.name}"
    is LegacyViolationKey.ForbiddenEffectUse -> with(violation.observation) {
        "effect|${module.name}|${effect.name}|${caller.owner.internalName}|${caller.name.value}|" +
        "${caller.descriptor.value}|${target.owner.internalName}|${target.name.value}|${target.descriptor.value}"
    }
}

private fun StringBuilder.appendStringArray(values: List<String>): StringBuilder =
    append(values.joinToString(prefix = "[", postfix = "]") { value -> "\"${value.jsonEscape()}\"" })

private fun StringBuilder.appendQuoted(value: String): StringBuilder =
    append('"').append(value.jsonEscape()).append('"')

private fun StringBuilder.appendConventionRequirement(
    requirement: ModuleRoleConventionRequirement,
): StringBuilder = when (requirement) {
    ModuleRoleConventionRequirement.UnmarkedLegacy ->
        append("{\"kind\": \"UNMARKED_LEGACY\"}")
    is ModuleRoleConventionRequirement.Required ->
        append("{\"kind\": \"REQUIRED\", \"pluginId\": ")
            .appendQuoted(requirement.convention.pluginId)
            .append("}")
}

private fun StringBuilder.appendComma(
    index: Int,
    lastIndex: Int,
): StringBuilder =
    apply { if (index < lastIndex) append(',') }

private fun String.jsonEscape(): String = buildString(length) {
    this@jsonEscape.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
}
