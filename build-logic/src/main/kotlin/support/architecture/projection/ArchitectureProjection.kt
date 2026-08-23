package support.architecture.projection

import support.architecture.ModuleRoleConventionRequirement
import support.architecture.ValidatedArchitecturePolicy

object ArchitectureProjection {
    fun render(policy: ValidatedArchitecturePolicy): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
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
        append("  ]\n")
        append("}\n")
    }
}

private fun StringBuilder.appendStringArray(values: List<String>): StringBuilder =
    append(values.joinToString(prefix = "[", postfix = "]") { value -> "\"${value.jsonEscape()}\"" })

private fun StringBuilder.appendQuoted(value: String): StringBuilder =
    append('"').append(value.jsonEscape()).append('"')

private fun StringBuilder.appendConventionRequirement(
    requirement: ModuleRoleConventionRequirement,
): StringBuilder = when (requirement) {
    ModuleRoleConventionRequirement.UnmarkedLegacy -> append("{\"kind\": \"UNMARKED_LEGACY\"}")
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
